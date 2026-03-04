package com.extendedae_plus.util.uploadPattern;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingPlan;
import appeng.me.helpers.PlayerSource;
import com.extendedae_plus.init.ModNetwork;
import com.extendedae_plus.network.crafting.AddJeiBookmarksS2CPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 上传样板后自动执行 AE 合成模拟：
 * - 若所有材料充足，则不合成（安静结束）
 * - 若材料不足，则将缺失的物品材料发送到客户端，由客户端添加到 JEI 书签
 */
public final class PostUploadCraftingSimulationUtil {

    private PostUploadCraftingSimulationUtil() {}

    /**
     * 在样板上传成功后调用，自动对样板的主输出物品执行 AE 合成模拟。
     *
     * @param player  服务器玩家
     * @param pattern 已编码样板
     * @param grid    AE 网络
     */
    public static void simulateAfterUpload(ServerPlayer player, ItemStack pattern, IGrid grid) {
        if (player == null || pattern == null || pattern.isEmpty() || grid == null) return;

        IPatternDetails details;
        try {
            details = PatternDetailsHelper.decodePattern(pattern, player.level());
        } catch (Throwable t) {
            return;
        }
        if (details == null) return;

        GenericStack primaryOutput = details.getPrimaryOutput();
        if (primaryOutput == null) return;

        AEKey outputKey = primaryOutput.what();
        if (outputKey == null) return;

        var craftingService = grid.getCraftingService();
        if (!craftingService.isCraftable(outputKey)) return;

        try {
            craftingService.beginCraftingCalculation(
                    player.serverLevel(),
                    new ICraftingSimulationRequester() {
                        @Override
                        public IActionSource getActionSource() {
                            return new PlayerSource(player);
                        }

                        @Override
                        public void setCalculationResult(CraftingPlan plan) {
                            player.server.execute(() -> {
                                try {
                                    handlePlanResult(player, plan);
                                } catch (Throwable ignored) {
                                }
                            });
                        }
                    },
                    outputKey,
                    1L,
                    CalculationStrategy.CRAFT_LESS
            );
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static void handlePlanResult(ServerPlayer player, CraftingPlan plan) {
        List<ItemStack> missingItems = new ArrayList<>();
        try {
            // 通过反射安全访问 missingItems（兼容 record accessor 和 public field）
            Object missingCounter = getMissingItems(plan);
            if (missingCounter instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() instanceof AEItemKey aeItemKey) {
                        missingItems.add(aeItemKey.getReadOnlyStack());
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (!missingItems.isEmpty()) {
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new AddJeiBookmarksS2CPacket(missingItems)
            );
        }
        // 若 missingItems 为空说明材料充足，不执行任何操作（不合成、不提示）
    }

    /**
     * 安全获取 CraftingPlan 中的 missingItems：
     * 先尝试 record accessor 方法（无参方法），再尝试 public field。
     */
    private static Object getMissingItems(CraftingPlan plan) {
        // 尝试 record 访问器（Java record 生成无前缀的同名方法）
        try {
            var method = plan.getClass().getMethod("missingItems");
            return method.invoke(plan);
        } catch (Throwable ignored) {
        }
        // 尝试 public field 访问
        try {
            var field = plan.getClass().getField("missingItems");
            return field.get(plan);
        } catch (Throwable ignored) {
        }
        return null;
    }
}
