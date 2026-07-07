package com.meteorite.unsuspiciousblock.client.anvil;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.List;

/**
 * 铁砧成本分解 tooltip 行构建器。
 * 将 {@link AnvilBreakdown} 转换为可渲染的 {@link Component} 列表，
 * 供 {@link AnvilBreakdownTooltipAppender} 追加到原版物品 tooltip 之后。
 *
 * <p>样式约定：标签行灰色，总计金色，不兼容红色，新增/升级附魔绿色，拒绝附魔红色。</p>
 */
public final class AnvilBreakdownTooltipBuilder {

    private AnvilBreakdownTooltipBuilder() {}

    /**
     * 构建输入槽物品的附魔惩罚值 tooltip 行。
     * 用于铁砧左右两个输入槽物品，展示其当前 REPAIR_COST（prior work 累积值）。
     */
    public static List<Component> buildInputPenalty(int repairCost) {
        List<Component> lines = new ArrayList<>();
        // 空行分隔原版 tooltip
        lines.add(Component.empty());
        lines.add(Component.translatable(
                "unsuspiciousblock.container.anvil.reveal.input_penalty", repairCost)
                .withStyle(ChatFormatting.GRAY));
        return lines;
    }

    /** 构建完整的分解 tooltip 行列表（不含原版物品 tooltip）。 */
    public static List<Component> build(AnvilBreakdown bd) {
        List<Component> lines = new ArrayList<>();
        // 空行分隔原版 tooltip
        lines.add(Component.empty());
        // 标题
        lines.add(Component.translatable("unsuspiciousblock.container.anvil.reveal.header")
                .withStyle(ChatFormatting.GOLD));
        // 成本明细
        if (bd.priorWork() > 0) {
            lines.add(line("prior_work", bd.priorWork()));
        }
        if (bd.enchantCost() > 0) {
            lines.add(line("enchant_cost", bd.enchantCost()));
        }
        if (bd.repairCost() > 0) {
            lines.add(line("repair_cost", bd.repairCost()));
        }
        if (bd.renameCost() > 0) {
            lines.add(line("rename", bd.renameCost()));
        }
        if (bd.incompatibleCost() > 0) {
            lines.add(Component.translatable(
                    "unsuspiciousblock.container.anvil.reveal.incompatible", bd.incompatibleCost())
                    .withStyle(ChatFormatting.RED));
        }
        // 残差：其他 mod 改写或堆叠修正导致的未归类成本
        int known = bd.priorWork() + bd.enchantCost() + bd.repairCost()
                + bd.renameCost() + bd.incompatibleCost();
        int other = bd.total() - known;
        if (other > 0) {
            lines.add(line("other", other));
        }

        // 总计
        lines.add(Component.translatable("unsuspiciousblock.container.anvil.reveal.total", bd.total())
                .withStyle(ChatFormatting.GOLD));

        // REPAIR_COST 变化
        if (bd.newRepairCost() != AnvilBreakdown.REPAIR_COST_UNKNOWN) {
            MutableComponent changeLine = Component.translatable(
                    "unsuspiciousblock.container.anvil.reveal.repair_cost_change",
                    bd.oldRepairCost(),
                    Component.literal(String.valueOf(bd.newRepairCost()))
                            .withStyle(bd.newRepairCost() > bd.oldRepairCost()
                                    ? ChatFormatting.RED : ChatFormatting.WHITE)
            ).withStyle(ChatFormatting.GRAY);
            lines.add(changeLine);
        }

        // 仅重命名标注
        if (bd.renameOnly()) {
            lines.add(Component.translatable("unsuspiciousblock.container.anvil.reveal.rename_only")
                    .withStyle(ChatFormatting.DARK_GREEN));
        }

        // 附魔变动
        if (!bd.changes().isEmpty()) {
            lines.add(Component.empty());
            lines.add(Component.translatable("unsuspiciousblock.container.anvil.reveal.enchant_changes")
                    .withStyle(ChatFormatting.GRAY));
            for (AnvilBreakdown.EnchantChange ec : bd.changes()) {
                lines.add(buildEnchantChangeLine(ec));
            }
        }

        return lines;
    }

    /** 构建单条成本行：标签 + 数值，整体灰色。 */
    private static Component line(String key, int value) {
        return Component.translatable(
                "unsuspiciousblock.container.anvil.reveal." + key, value)
                .withStyle(ChatFormatting.GRAY);
    }

    /** 构建单条附魔变动行。 */
    private static Component buildEnchantChangeLine(AnvilBreakdown.EnchantChange ec) {
        MutableComponent line = Component.literal("  ");
        if (!ec.applied()) {
            // 拒绝：✗ 名称 (原因)
            line.append(Component.literal("✗ ").withStyle(ChatFormatting.RED));
            line.append(Enchantment.getFullname(ec.enchant(), ec.materialLevel()));
            String reasonKey = AnvilBreakdown.EnchantChange.REASON_INCOMPATIBLE.equals(ec.rejectReason())
                    ? "unsuspiciousblock.container.anvil.reveal.change_rejected_incompatible"
                    : "unsuspiciousblock.container.anvil.reveal.change_rejected_unsupported";
            line.append(Component.literal(" "));
            line.append(Component.translatable(reasonKey).withStyle(ChatFormatting.DARK_RED));
            return line;
        }
        if (ec.targetLevel() > 0) {
            // 升级：↑ 基名 oldRoman→newRoman
            line.append(Component.literal("↑ ").withStyle(ChatFormatting.GREEN));
            line.append(ec.enchant().value().description().copy());
            line.append(Component.literal(" "));
            line.append(roman(ec.targetLevel()));
            line.append(Component.literal("→").withStyle(ChatFormatting.GREEN));
            line.append(roman(ec.resultLevel()));
        } else {
            // 新增：✓ 全名
            line.append(Component.literal("✓ ").withStyle(ChatFormatting.GREEN));
            line.append(Enchantment.getFullname(ec.enchant(), ec.resultLevel()));
        }
        return line;
    }

    /** 罗马数字 Component，复用原版 enchantment.level.<n> 翻译键。 */
    private static Component roman(int level) {
        return Component.translatable("enchantment.level." + level);
    }
}
