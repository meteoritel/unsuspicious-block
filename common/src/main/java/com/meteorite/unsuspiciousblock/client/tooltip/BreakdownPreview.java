package com.meteorite.unsuspiciousblock.client.tooltip;

import com.meteorite.unsuspiciousblock.inventory.InventoryPresenceRegistry;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.text.TooltipBuilder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 铁砧 / 砂轮「猫之瞳」成本分解预览的公共编排层。
 *
 * <p>两处槽位 tooltip 追加器（{@code AnvilBreakdownTooltipAppender} / {@code GrindstoneBreakdownTooltipAppender}）
 * 的介入条件、槽位分流与惩罚值追加逐字同构，本类只收敛这部分编排：
 * 屏幕类型判断与各自的分解算法仍留在两个 Calculator 与两个 Breakdown record 里
 * ——两套算法本质不同，合并会造出错误抽象。</p>
 *
 * <p>调用方负责屏幕判定与算法调用，本类负责：结果槽 → 完整分解；输入/附加槽 → 附魔惩罚值（仅 &gt; 0 时）。</p>
 */
public final class BreakdownPreview {

    private BreakdownPreview() {}

    /**
     * 按槽位分流把分解行追加到 tooltip。
     *
     * @param stack        触发 tooltip 的物品栈；与槽位物品做引用比较（事件传入的是槽位同一引用）
     * @param menu         当前容器菜单
     * @param resultSlot   结果槽索引
     * @param inputSlot    输入槽索引
     * @param additionalSlot 附加槽索引
     * @param resultLines  结果槽的完整分解行；只在命中结果槽时求值
     */
    public static void appendIfApplicable(ItemStack stack, List<Component> tooltip, AbstractContainerMenu menu,
                                          int resultSlot, int inputSlot, int additionalSlot,
                                          Supplier<List<Component>> resultLines) {
        // 结果槽：追加完整分解；引用比较，理由见上
        if (stack == menu.getSlot(resultSlot).getItem()) {
            tooltip.addAll(resultLines.get());
            return;
        }
        // 输入槽 / 附加槽：追加该物品当前的附魔惩罚值，仅惩罚值 > 0 时显示
        if (stack != menu.getSlot(inputSlot).getItem() && stack != menu.getSlot(additionalSlot).getItem()) {
            return;
        }
        int repairCost = stack.getOrDefault(DataComponents.REPAIR_COST, 0);
        if (repairCost > 0) {
            tooltip.addAll(buildInputPenalty(repairCost));
        }
    }

    // 客户端背包检查：持有猫之瞳才显示分解
    public static boolean hasRevealItem(Player player) {
        if (ModItems.EYE_OF_CAT == null) {
            return false;
        }
        return InventoryPresenceRegistry.isPresent(player, ModItems.EYE_OF_CAT);
    }

    // 输入槽物品的附魔惩罚值 tooltip 行；铁砧与砂轮语义一致，共用同一文案与语义色
    public static List<Component> buildInputPenalty(int repairCost) {
        List<Component> lines = new ArrayList<>(2);
        // 空行分隔原版 tooltip
        lines.add(Component.empty());
        lines.add(Component.translatable(
                "unsuspiciousblock.container.anvil.reveal.input_penalty", repairCost)
                .withStyle(TooltipBuilder.LABEL));
        return lines;
    }
}
