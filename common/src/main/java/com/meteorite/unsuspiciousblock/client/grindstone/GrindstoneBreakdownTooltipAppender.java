package com.meteorite.unsuspiciousblock.client.grindstone;

import com.meteorite.unsuspiciousblock.client.anvil.AnvilBreakdownTooltipBuilder;
import com.meteorite.unsuspiciousblock.inventory.InventoryPresenceRegistry;
import com.meteorite.unsuspiciousblock.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.GrindstoneScreen;
import net.minecraft.core.component.DataComponents;
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
 *   <li>玩家背包持有「猫之瞳」（{@link ModItems#EYE_OF_CAT}）</li>
 *   <li>触发 tooltip 的物品栈 == 砂轮槽位物品栈（引用相等）</li>
 * </ol>
 *
 * <p>行为分流：
 * <ul>
 *   <li>结果槽：追加完整操作分解（见 {@link GrindstoneBreakdownCalculator}）</li>
 *   <li>输入槽/附加槽：追加该物品当前的附魔惩罚值（REPAIR_COST），仅惩罚值 &gt; 0 时显示；
 *       复用铁砧的 {@link AnvilBreakdownTooltipBuilder#buildInputPenalty}</li>
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
    public static void appendIfApplicable(ItemStack stack, List<Component> tooltip) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof GrindstoneScreen grindstoneScreen)) {
            return;
        }
        GrindstoneMenu menu = grindstoneScreen.getMenu();
        Player player = mc.player;
        if (player == null || !hasEyeOfCat(player)) {
            return;
        }

        // 结果槽：追加完整操作分解
        // 引用比较：事件中的 stack 来自 hoveredSlot.getItem()，与结果槽物品同一引用
        if (stack == menu.getSlot(GrindstoneMenu.RESULT_SLOT).getItem()) {
            GrindstoneBreakdown bd = GrindstoneBreakdownCalculator.calculate(menu);
            if (bd != null) {
                tooltip.addAll(GrindstoneBreakdownTooltipBuilder.build(bd));
            }
            return;
        }

        // 输入槽/附加槽：追加当前物品的附魔惩罚值（仅惩罚值 > 0 时显示）
        // 复用铁砧的 input_penalty 渲染逻辑，语义一致
        if (stack == menu.getSlot(GrindstoneMenu.INPUT_SLOT).getItem()
                || stack == menu.getSlot(GrindstoneMenu.ADDITIONAL_SLOT).getItem()) {
            int repairCost = stack.getOrDefault(DataComponents.REPAIR_COST, 0);
            if (repairCost > 0) {
                tooltip.addAll(AnvilBreakdownTooltipBuilder.buildInputPenalty(repairCost));
            }
        }
    }

    // 客户端背包检查：持有猫之瞳才显示分解
    private static boolean hasEyeOfCat(Player player) {
        if (ModItems.EYE_OF_CAT == null) {
            return false;
        }
        return InventoryPresenceRegistry.isPresent(player, ModItems.EYE_OF_CAT);
    }
}
