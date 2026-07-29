# PRD: JEI 物品数量与可合成标识显示

## 背景

ExtendedAE Plus 已实现在 JEI 界面中 Shift+左键提取 AE2 网络物品、中键请求合成的功能。但目前存在一个核心痛点：

**用户在 JEI 界面看到某个物品时，完全不知道 AE2 网络中是否有存量，也不知道这个物品能否被自动合成。** 只有 Shift+点击后才能得知结果（要么提取成功、要么弹出合成界面、要么什么都不发生）。

## 目标

在 JEI 的物品列表面板和书签面板中，为每个物品叠加显示：
1. **存量数字** — 与 AE2 终端一致的缩写格式（如 `128`、`1.2K`、`2.1G`）
2. **可合成标识** — 当物品可被 AE2 网络自动合成时显示 `+` 或 `Craft` 标记

视觉效果应与 AE2 终端中的物品槽位保持一致（右下角白色数字 + 左上角 `+` 标记）。

## 现有代码分析

### 可复用的部分

| 组件 | 来源 | 说明 |
|------|------|------|
| 数量格式化 | AE2 `ReadableNumberConverter.format(long, int)` | K/M/G/T 缩写逻辑，宽度自适应 |
| 数量渲染 | AE2 `StackSizeRenderer.renderSizeLabel()` | 缩放绘制、阴影文字、右下角定位 |
| 网络连接 | EAEP `WirelessTerminalLocator.find(player)` | 定位玩家的无线终端，连接 AE2 grid |
| AE2 存储查询 | AE2 `MEStorage.getAvailableStacks()` / `ICraftingService.isCraftable()` | 查询存量和可合成性 |
| JEI Runtime | EAEP `JeiRuntimeProxy` | 已缓存 IJeiRuntime，可获取可见物品列表 |
| JEI Mixin 条件加载 | EAEP `MixinConditions` | 已有 JEI 存在性检测 |

### 需要新实现的部分

1. **JeiSyncManager** — 服务端 per-player 后台同步管理器（复用 AE2 的 serial + diff 模式，但每 20 tick 而非每 tick）
2. **客户端存量缓存** — 接收增量更新，维护 AEKey → (amount, craftable) 映射
3. **JEI 渲染 Mixin** — 注入 `IngredientListRenderer.render()` 在物品绘制后叠加数量/标记
4. **数据同步网络包** — 复用 AE2 的 serial 协议格式

## 技术方案

### AE2 终端原理（参考）

AE2 自己的终端（包括无线终端）是这样工作的：
1. **服务端**：`MEStorageMenu.broadcastChanges()` 每 tick 调用 `getAvailableStacks()` + `getCraftables()`，与上一 tick 做 diff
2. **增量同步**：通过 `IncrementalUpdateHelper` 分配 serial 序号，只发送变化项（新 key 发全量数据，已知 key 只发 serial+数量）
3. **客户端**：`Repo` 收到增量更新后在本地维护完整的 `BiMap<serial, GridInventoryEntry>`
4. **搜索/翻页全在客户端**：服务端推全量，客户端本地 filter + sort + scroll

**核心启示：AE2 也是每 tick diff 式"轮询"，只是因为绑定在打开的 Menu 上所以开销可控。**

### 架构概览

我们的场景与 AE2 终端的核心区别：JEI 面板不需要打开任何 Menu 就始终可见。所以不能照搬 `MEStorageMenu` 的"Menu 打开时同步"模式，需要一个**轻量级的后台同步通道**。

```
[Server] PlayerTickEvent → 检测无线终端 → JeiSyncManager(per-player)
             ↓                                    ↓
   getAvailableStacks() + getCraftables()     diff vs previous
             ↓                                    ↓
   有变化 → SyncNetworkInventoryS2CPacket (serial + amount + craftable)
             ↓
[Client] NetworkItemCache (Map<AEKey, Entry>)
             ↓
   JEI IngredientListRenderer.render() ← Mixin → 查 cache → 叠加渲染
```

### 1. 服务端：JeiSyncManager（Per-Player 后台同步）

复用 AE2 `IncrementalUpdateHelper` 的 serial + diff 模式，但不绑定 Menu：

- **触发时机**：`PlayerTickEvent`，每 N tick（可配置，默认 20 tick = 1 秒）执行一次 diff
- **前提检查**：玩家持有/装备有效无线终端 + 在范围内 + 有电（复用 `WirelessTerminalLocator`）
- **Diff 逻辑**：与 `MEStorageMenu.broadcastChanges()` 相同 —
  - `storage.getAvailableStacks()` 与上次快照做差集
  - `craftingService.getCraftables()` 与上次做 `Sets.difference()`
  - 有变化才发包
- **包格式**：复用 AE2 的 serial 机制 —
  - 首次：fullUpdate=true，发送所有 key + serial + amount + craftable
  - 后续：只发变化项的 serial + amount + craftable（已知 key 不重复发 AEKey 数据）
  - 移除项：serial + 0/0/false
- **频率控制**：不需要每 tick 都做（不像终端那样需要实时响应点击），1 秒一次足够体验流畅

**为什么不用事件监听？** — AE2 自己都没用。`MEStorage` 没有提供公开的变化监听 API 给外部消费者。AE2 终端是通过 `getAvailableStacks()` 每 tick 查全量再 diff 的。我们保持同样的模式，只是降低频率。

### 2. 客户端：NetworkItemCache

- `NetworkItemCache` 单例，结构与 AE2 `Repo` 类似但更轻量（不需要排序/过滤/翻页）
  - `Map<Long, CacheEntry>` — serial → {AEKey, amount, craftable}
  - `Map<AEKey, Long>` — 反向索引，用于 O(1) 查询
  - 提供 `getAmount(AEKey)` 和 `isCraftable(AEKey)` 
  - 处理 fullUpdate（清空重建）和增量更新（按 serial 更新/删除）
  - 玩家断开/离开世界/终端失效时收到一个 clear 包，清空缓存

### 3. 客户端：JEI 渲染注入

**方案：Mixin `IngredientListRenderer.render()`**

在 `render()` 方法的 TAIL 处注入：
1. 遍历 `this.slots` 获取每个可见的 `IngredientListSlot`
2. 对每个槽位通过 `IElement.getTypedIngredient()` 获取物品
3. 转换为 `AEItemKey` 后查询 `NetworkItemCache`
4. 如果有数据，使用 AE2 的 `ReadableNumberConverter.format(amount, 3)` 格式化
5. 调用 AE2 的 `StackSizeRenderer.renderSizeLabel()` 绘制：
   - **右下角**：白色带阴影的数量文字（0.5x 缩放）
   - **左上角**：`+` 标记（如果可合成且有存量）
   - 如果存量为 0 但可合成：显示 `Craft`

### 4. 同步触发条件

- 玩家必须持有/装备有效的无线终端（复用 `WirelessTerminalLocator`）
- 终端必须在 AE2 网络的通信范围内且有电量
- 不满足时：发一个 `clearCache` 标志包，客户端清除所有叠加显示
- 重新满足时：重新 fullUpdate

### 5. 性能对比

| 方面 | AE2 终端 | 我们的 JEI 叠加 |
|------|----------|-----------------|
| 触发条件 | Menu 打开时，每 tick | 无线终端在身，每 20 tick |
| 查询方式 | `getAvailableStacks()` 全量 diff | 同上 |
| 网络带宽 | 每 tick 可能发包 | 每秒最多一次，增量 |
| 客户端开销 | Repo 需要排序/过滤/分页 | 仅 HashMap 存储，渲染时 O(1) 查询 |
| 生命周期 | 关闭 Menu 就停止 | 只要终端在身就保持 |

比 AE2 终端本身更轻量（频率低 20 倍，客户端不排序），完全可控。

## 文件结构规划

```
src/main/java/com/extendedae_plus/
├── client/
│   ├── jei/
│   │   ├── NetworkItemCache.java          // 客户端存量缓存（serial-based）
│   │   └── JeiOverlayRenderer.java        // JEI 叠加层渲染逻辑
│   └── ...
├── mixin/jei/
│   ├── IngredientListRendererMixin.java   // Mixin 注入渲染
│   └── ...
├── network/
│   ├── jei/
│   │   └── SyncNetworkInventoryS2CPacket.java  // 服务端→客户端，serial + 增量
│   └── ...
├── server/
│   └── JeiSyncManager.java               // Per-player 后台同步（每 20 tick diff）
└── ...
```

## 验收标准

1. 打开 JEI 物品面板时，AE2 网络中有存量的物品右下角显示缩写数字
2. 可合成的物品左上角显示 `+` 标记
3. 存量为 0 但可合成的物品显示 `Craft` 文字
4. 数量变化在 ~1 秒内反映到 JEI 显示（20 tick 同步周期）
5. 无线终端不在范围/没电时，数量标记消失（不显示过期数据）
6. 不影响 JEI 的正常功能（搜索、配方查看、拖拽等）
7. 书签面板同样显示数量和可合成标记

## 非目标（本期不做）

- 不修改 JEI 搜索逻辑（如按存量排序）
- 不在 JEI tooltip 中显示详细信息
- 不支持流体/化学品的数量显示（仅 ItemStack）

## 风险

1. **JEI 内部类 Mixin 稳定性** — `IngredientListRenderer` 是 JEI 内部类，版本更新可能变化。需要通过 `MixinConditions` 做版本兼容检查。
2. **大型网络首次全量同步** — 10万+ 物品种类的首次快照包可能较大（~几百 KB）。可分片发送（复用 AE2 的 512KB 分包机制）。
3. **多终端冲突** — 玩家可能同时拥有多个终端连接不同网络。取优先级最高的那个（手持 > 饰品栏）。
4. **`getAvailableStacks()` 每秒全量调用** — AE2 内部对此有缓存（标记脏时才重算），开销与 AE2 终端打开时一致，但如果同时多人开终端 + 多人 JEI 同步可能叠加。可通过共享快照减少重复查询。
