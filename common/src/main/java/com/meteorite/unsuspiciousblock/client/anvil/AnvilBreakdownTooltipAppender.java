package com.meteorite.unsuspiciousblock.client.anvil;

import com.meteorite.unsuspiciousblock.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 铁砧结果槽成本分解 tooltip 追加器。
 * <p>
 * 由各平台客户端事件监听器（NeoForge {@code ItemTooltipEvent} / Fabric {@code ItemTooltipCallback}）
 * 在物品 tooltip 构建时调用，判断是否需要追加成本分解并写入 tooltip 列表。
 *
 * <p>介入条件（全部满足）：
 * <ol>
 *   <li>当前屏幕为 {@link AnvilScreen}</li>
 *   <li>玩家背包持有「猫之瞳」（{@link ModItems#EYE_OF_CAT}）</li>
 *   <li>触发 tooltip 的物品栈 == 铁砧结果槽物品栈（引用相等）</li>
 * </ol>
 *
 * <p>分解计算完全在客户端进行（见 {@link AnvilBreakdownCalculator}），无网络同步开销。
 */
public final class AnvilBreakdownTooltipAppender {

    private AnvilBreakdownTooltipAppender() {}

    /**
     * 在物品 tooltip 上追加铁砧成本分解。
     * 由平台事件监听器调用，不满足介入条件时直接返回。
     */
    // 平台事件回调入口：判断 + 计算 + 追加，无业务逻辑外泄
    public static void appendIfApplicable(ItemStack stack, List<Component> tooltip) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof AnvilScreen anvilScreen)) {
            return;
        }
        AnvilMenu menu = anvilScreen.getMenu();
        // 引用比较：事件中的 stack 来自 hoveredSlot.getItem()，与结果槽物品同一引用
        if (stack != menu.getSlot(AnvilMenu.RESULT_SLOT).getItem()) {
            return;
        }
        Player player = mc.player;
        if (player == null || !hasEyeOfCat(player)) {
            return;
        }
        AnvilBreakdown breakdown = AnvilBreakdownCalculator.calculate(menu, player);
        tooltip.addAll(AnvilBreakdownTooltipBuilder.build(breakdown));
    }

    // 客户端背包检查：持有猫之瞳才显示分解
    private static boolean hasEyeOfCat(Player player) {
        if (ModItems.EYE_OF_CAT == null) {
            return false;
        }
        return player.getInventory().contains(new ItemStack(ModItems.EYE_OF_CAT));
    }
}
