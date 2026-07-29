# ExtendedAE Plus 开发进度

## 2026-07-29 环境搭建 + PRD

### 完成事项

1. **克隆仓库并构建成功**
   - 仓库：`/Users/bytedance/Desktop/github/ExtendedAE_Plus`
   - Gradle 8.14 + Architectury Loom 1.10 + Forge 1.20.1-47.4.3
   - Java 17 编译（使用 Temurin 21 兼容运行）
   - 构建产物：`build/libs/extendedae_plus-1.5.5.jar`
   - 首次构建需等待 loom remap（约 5-10 分钟），后续秒编译

2. **HMCL 游戏实例搭建**
   - 版本名：`1.20.1-Forge`
   - Mods 已放入全局目录：`~/Library/Application Support/minecraft/mods/`
   - 安装的 mod：
     - `extendedae_plus-1.5.5.jar`（本项目构建）
     - `ExtendedAE-1.20-1.4.2-forge.jar`（前置，仓库 libs/ 自带）
     - `appliedenergistics2-forge-15.4.10.jar`（AE2）
     - `glodium.jar`（ExtendedAE 前置）
     - `curios-forge.jar`（装备栏 API）
     - `jei-1.20.1-forge-15.20.0.129.jar`（JEI）

3. **PRD 编写完成**
   - 文件：`prd.md`
   - 功能：JEI 面板显示 AE2 网络物品数量 + 可合成标识
   - 方案：复用 AE2 的 serial + diff 协议，每 20 tick 后台同步，Mixin JEI 渲染器叠加显示
   - 状态：**待 review**

### 改动文件

- `prd.md`（新建）
- `process.md`（新建）

### 遗留 / 待确认

- PRD 方案需用户确认后开始实现
- 游戏还未实际启动验证 mod 加载（需启动 HMCL 测试）
- 版本隔离模式未确认：mods 同时放了全局和版本隔离目录，启动后看哪个生效
