package com.meteorite.unsuspiciousblock.client.grindstone;

import com.meteorite.unsuspiciousblock.client.tooltip.TooltipBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.List;

/**
 * 砂轮操作分解 tooltip 行构建器。
 * 将 {@link GrindstoneBreakdown} 转换为可渲染的 {@link Component} 列表，
 * 供 {@link GrindstoneBreakdownTooltipAppender} 追加到原版物品 tooltip 之后。
 *
 * <p>样式约定遵循 docs/dev/tooltip.md 语义色表（2026-09-15 起统一走 {@code TooltipBuilder}
 * 常量）：标题 TITLE，操作类型/分区 LABEL，移除项 NEGATIVE，保留诅咒 SEVERE，
 * 经验返还/耐久变化/惩罚减少 POSITIVE，惩罚增加 NEGATIVE，附魔书转换标注 ACCENT。</p>
 */
public final class GrindstoneBreakdownTooltipBuilder {

    private GrindstoneBreakdownTooltipBuilder() {}

    /** 构建完整的分解 tooltip 行列表（不含原版物品 tooltip）。 */
    public static List<Component> build(GrindstoneBreakdown bd) {
        List<Component> lines = new ArrayList<>();
        // 空行分隔原版 tooltip
        lines.add(Component.empty());
        // 标题
        lines.add(Component.translatable("unsuspiciousblock.container.grindstone.reveal.header")
                .withStyle(TooltipBuilder.TITLE));

        // 操作类型
        String opKey = bd.operationType() == GrindstoneBreakdown.OperationType.MERGE_REPAIR
                ? "unsuspiciousblock.container.grindstone.reveal.operation.merge_repair"
                : "unsuspiciousblock.container.grindstone.reveal.operation.disenchant";
        lines.add(Component.translatable(opKey).withStyle(TooltipBuilder.LABEL));

        // 将被移除的附魔
        if (!bd.removed().isEmpty()) {
            lines.add(Component.translatable("unsuspiciousblock.container.grindstone.reveal.removed_header")
                    .withStyle(TooltipBuilder.LABEL));
            for (GrindstoneBreakdown.EnchantEntry entry : bd.removed()) {
                lines.add(buildEnchantLine(entry, "✗ ", TooltipBuilder.NEGATIVE));
            }
        }

        // 将保留的诅咒
        if (!bd.kept().isEmpty()) {
            lines.add(Component.translatable("unsuspiciousblock.container.grindstone.reveal.kept_header")
                    .withStyle(TooltipBuilder.LABEL));
            for (GrindstoneBreakdown.EnchantEntry entry : bd.kept()) {
                lines.add(buildEnchantLine(entry, "⚠ ", TooltipBuilder.SEVERE));
            }
        }

        // 经验返还范围（仅 total > 0 时显示）
        if (bd.expMax() > 0) {
            MutableComponent expLine = Component.translatable(
                    "unsuspiciousblock.container.grindstone.reveal.experience_range",
                    bd.expMin(), bd.expMax());
            lines.add(expLine.withStyle(TooltipBuilder.POSITIVE));
        }

        // 惩罚值变化（仅变化时显示）
        if (bd.newRepairCost() != bd.oldRepairCost()) {
            MutableComponent changeLine = Component.translatable(
                    "unsuspiciousblock.container.grindstone.reveal.repair_cost_change",
                    bd.oldRepairCost(),
                    Component.literal(String.valueOf(bd.newRepairCost()))
                            .withStyle(bd.newRepairCost() > bd.oldRepairCost()
                                    ? TooltipBuilder.NEGATIVE : TooltipBuilder.POSITIVE)
            ).withStyle(TooltipBuilder.LABEL);
            lines.add(changeLine);
        }

        // 耐久变化（仅合并修复时）
        if (bd.hasDurabilityChange()) {
            MutableComponent durLine = Component.translatable(
                    "unsuspiciousblock.container.grindstone.reveal.durability_change",
                    bd.oldDurability(), bd.newDurability());
            lines.add(durLine.withStyle(TooltipBuilder.POSITIVE));
        }

        // 附魔书 → 普通书
        if (bd.bookTransformed()) {
            lines.add(Component.translatable("unsuspiciousblock.container.grindstone.reveal.book_transform")
                    .withStyle(TooltipBuilder.ACCENT));
        }

        return lines;
    }

    /** 构建单条附魔行：前缀符号 + 附魔全名。 */
    private static Component buildEnchantLine(GrindstoneBreakdown.EnchantEntry entry,
                                              String prefix, ChatFormatting prefixColor) {
        MutableComponent line = Component.literal(prefix).withStyle(prefixColor);
        line.append(Enchantment.getFullname(entry.enchant(), entry.level()));
        return line;
    }
}
