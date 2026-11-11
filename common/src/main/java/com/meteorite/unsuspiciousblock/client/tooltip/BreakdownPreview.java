package com.meteorite.unsuspiciousblock.client.tooltip;

import com.meteorite.unsuspiciousblock.client.anvil.AnvilBreakdownCalculator;
import com.meteorite.unsuspiciousblock.client.anvil.AnvilBreakdownTooltipBuilder;
import com.meteorite.unsuspiciousblock.client.grindstone.GrindstoneBreakdown;
import com.meteorite.unsuspiciousblock.client.grindstone.GrindstoneBreakdownCalculator;
import com.meteorite.unsuspiciousblock.client.grindstone.GrindstoneBreakdownTooltipBuilder;
import com.meteorite.unsuspiciousblock.core.inventory.CarriedItems;
import com.meteorite.unsuspiciousblock.core.item.ModItems;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.GrindstoneScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 铁砧 / 砂轮「猫之瞳」成本分解预览的公共编排层。
 *
 * <p>容器登记项只保存屏幕、菜单、槽位和结果行生成器；两套分解算法仍保持独立。</p>
 *
 * <p>本类负责介入条件、登记遍历和槽位分流：结果槽 → 完整分解；输入/附加槽 → 附魔惩罚值（仅 &gt; 0 时）。</p>
 */
public final class BreakdownPreview {

    private static final List<Registration> REGISTRATIONS = List.of(
            registration(AnvilScreen.class, AnvilScreen::getMenu,
                    AnvilMenu.RESULT_SLOT, AnvilMenu.INPUT_SLOT, AnvilMenu.ADDITIONAL_SLOT,
                    (menu, player) -> AnvilBreakdownTooltipBuilder.build(
                            AnvilBreakdownCalculator.calculate(menu, player))),
            registration(GrindstoneScreen.class, GrindstoneScreen::getMenu,
                    GrindstoneMenu.RESULT_SLOT, GrindstoneMenu.INPUT_SLOT, GrindstoneMenu.ADDITIONAL_SLOT,
                    (menu, player) -> {
                        GrindstoneBreakdown breakdown = GrindstoneBreakdownCalculator.calculate(menu);
                        return breakdown == null ? List.of() : GrindstoneBreakdownTooltipBuilder.build(breakdown);
                    })
    );

    private BreakdownPreview() {}

    /** 为当前屏幕追加所有已登记容器的分解信息。 */
    public static void appendRegistered(ItemStack stack, List<Component> tooltip) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null || minecraft.player == null
                || !hasRevealItem(minecraft.player)) {
            return;
        }
        for (Registration registration : REGISTRATIONS) {
            registration.append(stack, tooltip, minecraft, minecraft.player);
        }
    }

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

    private static <S extends AbstractContainerScreen<M>, M extends AbstractContainerMenu> Registration registration(
            Class<S> screenType, Function<S, M> menuExtractor,
            int resultSlot, int inputSlot, int additionalSlot,
            BiFunction<M, Player, List<Component>> resultLines) {
        return (stack, tooltip, minecraft, player) -> {
            if (!screenType.isInstance(minecraft.screen)) {
                return;
            }
            S screen = screenType.cast(minecraft.screen);
            M menu = menuExtractor.apply(screen);
            appendIfApplicable(stack, tooltip, menu, resultSlot, inputSlot, additionalSlot,
                    () -> resultLines.apply(menu, player));
        };
    }

    @FunctionalInterface
    private interface Registration {
        void append(ItemStack stack, List<Component> tooltip, Minecraft minecraft, Player player);
    }

    // 客户端背包检查：持有猫之瞳才显示分解
    public static boolean hasRevealItem(Player player) {
        if (ModItems.EYE_OF_CAT == null) {
            return false;
        }
        return CarriedItems.isPresent(player, ModItems.EYE_OF_CAT);
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
