package com.meteorite.unsuspiciousblock.client.grindstone;

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
 * <p>样式约定：标题金色，操作类型/分区灰色，移除项红色，保留诅咒暗红色，
 * 经验返还绿色，耐久变化绿色，附魔书转换紫色。</p>
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
                .withStyle(ChatFormatting.GOLD));

        // 操作类型
        String opKey = bd.operationType() == GrindstoneBreakdown.OperationType.MERGE_REPAIR
                ? "unsuspiciousblock.container.grindstone.reveal.operation.merge_repair"
                : "unsuspiciousblock.container.grindstone.reveal.operation.disenchant";
        lines.add(Component.translatable(opKey).withStyle(ChatFormatting.GRAY));

        // 将被移除的附魔
        if (!bd.removed().isEmpty()) {
            lines.add(Component.translatable("unsuspiciousblock.container.grindstone.reveal.removed_header")
                    .withStyle(ChatFormatting.GRAY));
            for (GrindstoneBreakdown.EnchantEntry entry : bd.removed()) {
                lines.add(buildEnchantLine(entry, "✗ ", ChatFormatting.RED));
            }
        }

        // 将保留的诅咒
        if (!bd.kept().isEmpty()) {
            lines.add(Component.translatable("unsuspiciousblock.container.grindstone.reveal.kept_header")
                    .withStyle(ChatFormatting.GRAY));
            for (GrindstoneBreakdown.EnchantEntry entry : bd.kept()) {
                lines.add(buildEnchantLine(entry, "⚠ ", ChatFormatting.DARK_RED));
            }
        }

        // 经验返还范围（仅 total > 0 时显示）
        if (bd.expMax() > 0) {
            MutableComponent expLine = Component.translatable(
                    "unsuspiciousblock.container.grindstone.reveal.experience_range",
                    bd.expMin(), bd.expMax());
            lines.add(expLine.withStyle(ChatFormatting.GREEN));
        }

        // 惩罚值变化（仅变化时显示）
        if (bd.newRepairCost() != bd.oldRepairCost()) {
            MutableComponent changeLine = Component.translatable(
                    "unsuspiciousblock.container.grindstone.reveal.repair_cost_change",
                    bd.oldRepairCost(),
                    Component.literal(String.valueOf(bd.newRepairCost()))
                            .withStyle(bd.newRepairCost() > bd.oldRepairCost()
                                    ? ChatFormatting.RED : ChatFormatting.GREEN)
            ).withStyle(ChatFormatting.GRAY);
            lines.add(changeLine);
        }

        // 耐久变化（仅合并修复时）
        if (bd.hasDurabilityChange()) {
            MutableComponent durLine = Component.translatable(
                    "unsuspiciousblock.container.grindstone.reveal.durability_change",
                    bd.oldDurability(), bd.newDurability());
            lines.add(durLine.withStyle(ChatFormatting.GREEN));
        }

        // 附魔书 → 普通书
        if (bd.bookTransformed()) {
            lines.add(Component.translatable("unsuspiciousblock.container.grindstone.reveal.book_transform")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
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
