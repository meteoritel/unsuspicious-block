package com.meteorite.unsuspiciousblock.client.grindstone;

import com.meteorite.unsuspiciousblock.client.tooltip.BreakdownPreview;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.GrindstoneScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 砂轮槽位 tooltip 追加器。
 * <p>
 * 由各平台客户端事件监听器（NeoForge {@code ItemTooltipEvent} / Fabric {@code ItemTooltipCallback}）
 * 在物品 tooltip 构建时调用，判断是否需要追加砂轮操作分解并写入 tooltip 列表。
 *
 * <p>介入条件（全部满足）：
 * <ol>
 *   <li>当前屏幕为 {@link GrindstoneScreen}</li>
 *   <li>玩家背包持有「猫之瞳」（{@link com.meteorite.unsuspiciousblock.item.ModItems#EYE_OF_CAT}）</li>
 *   <li>触发 tooltip 的物品栈 == 砂轮槽位物品栈（引用相等）</li>
 * </ol>
 *
 * <p>行为分流（槽位编排见 {@link BreakdownPreview}，与铁砧共用同一入口，不再反向依赖 anvil 包）：
 * <ul>
 *   <li>结果槽：追加完整操作分解（见 {@link GrindstoneBreakdownCalculator}）</li>
 *   <li>输入槽/附加槽：追加该物品当前的附魔惩罚值（REPAIR_COST），仅惩罚值 &gt; 0 时显示</li>
 * </ul>
 *
 * <p>分解计算完全在客户端进行，无网络同步开销。
 */
public final class GrindstoneBreakdownTooltipAppender {

    private GrindstoneBreakdownTooltipAppender() {}

    /**
     * 在物品 tooltip 上追加砂轮操作分解。
     * 由平台事件监听器调用，不满足介入条件时直接返回。
     */
    // 平台事件回调入口：只做屏幕判定，槽位分流与追加委托 BreakdownPreview
    public static void appendIfApplicable(ItemStack stack, List<Component> tooltip) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof GrindstoneScreen grindstoneScreen)) {
            return;
        }
        GrindstoneMenu menu = grindstoneScreen.getMenu();
        Player player = mc.player;
        if (player == null || !BreakdownPreview.hasRevealItem(player)) {
            return;
        }
        BreakdownPreview.appendIfApplicable(stack, tooltip, menu,
                GrindstoneMenu.RESULT_SLOT, GrindstoneMenu.INPUT_SLOT, GrindstoneMenu.ADDITIONAL_SLOT,
                () -> {
                    GrindstoneBreakdown bd = GrindstoneBreakdownCalculator.calculate(menu);
                    return bd == null ? List.of() : GrindstoneBreakdownTooltipBuilder.build(bd);
                });
    }
}
