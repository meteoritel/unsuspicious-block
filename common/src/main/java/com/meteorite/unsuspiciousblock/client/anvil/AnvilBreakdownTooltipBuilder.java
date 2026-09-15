package com.meteorite.unsuspiciousblock.client.anvil;

import com.meteorite.unsuspiciousblock.client.tooltip.TooltipBuilder;

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
 * <p>样式约定遵循 docs/dev/tooltip.md 语义色表（2026-09-15 起统一走 {@code TooltipBuilder}
 * 常量）：标签行 LABEL，标题/总计 TITLE，不兼容/过于昂贵/惩罚增加 NEGATIVE，
 * 拒绝原因 SEVERE，新增/升级/仅重命名标注 POSITIVE，惩罚减少新值 BODY。</p>
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
                .withStyle(TooltipBuilder.LABEL));
        return lines;
    }

    /** 构建完整的分解 tooltip 行列表（不含原版物品 tooltip）。 */
    public static List<Component> build(AnvilBreakdown bd) {
        List<Component> lines = new ArrayList<>();
        // 空行分隔原版 tooltip
        lines.add(Component.empty());
        // 标题
        lines.add(Component.translatable("unsuspiciousblock.container.anvil.reveal.header")
                .withStyle(TooltipBuilder.TITLE));
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
                    .withStyle(TooltipBuilder.NEGATIVE));
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
                .withStyle(TooltipBuilder.TITLE));

        // 过于昂贵标注：数据层已判定操作超出上限时醒目提示
        if (bd.tooExpensive()) {
            lines.add(Component.translatable("unsuspiciousblock.container.anvil.reveal.too_expensive")
                    .withStyle(TooltipBuilder.NEGATIVE));
        }

        // REPAIR_COST 变化
        if (bd.newRepairCost() != AnvilBreakdown.REPAIR_COST_UNKNOWN) {
            MutableComponent changeLine = Component.translatable(
                    "unsuspiciousblock.container.anvil.reveal.repair_cost_change",
                    bd.oldRepairCost(),
                    Component.literal(String.valueOf(bd.newRepairCost()))
                            .withStyle(bd.newRepairCost() > bd.oldRepairCost()
                                    ? TooltipBuilder.NEGATIVE : TooltipBuilder.BODY)
            ).withStyle(TooltipBuilder.LABEL);
            lines.add(changeLine);
        }

        // 仅重命名标注
        if (bd.renameOnly()) {
            lines.add(Component.translatable("unsuspiciousblock.container.anvil.reveal.rename_only")
                    .withStyle(TooltipBuilder.POSITIVE));
        }

        // 附魔变动
        if (!bd.changes().isEmpty()) {
            lines.add(Component.empty());
            lines.add(Component.translatable("unsuspiciousblock.container.anvil.reveal.enchant_changes")
                    .withStyle(TooltipBuilder.LABEL));
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
                .withStyle(TooltipBuilder.LABEL);
    }

    /** 构建单条附魔变动行。 */
    private static Component buildEnchantChangeLine(AnvilBreakdown.EnchantChange ec) {
        MutableComponent line = Component.literal("  ");
        if (!ec.applied()) {
            // 拒绝：✗ 名称 (原因)
            line.append(Component.literal("✗ ").withStyle(TooltipBuilder.NEGATIVE));
            line.append(Enchantment.getFullname(ec.enchant(), ec.materialLevel()));
            String reasonKey = AnvilBreakdown.EnchantChange.REASON_INCOMPATIBLE.equals(ec.rejectReason())
                    ? "unsuspiciousblock.container.anvil.reveal.change_rejected_incompatible"
                    : "unsuspiciousblock.container.anvil.reveal.change_rejected_unsupported";
            line.append(Component.literal(" "));
            line.append(Component.translatable(reasonKey).withStyle(TooltipBuilder.SEVERE));
            return line;
        }
        if (ec.targetLevel() > 0) {
            // 升级：↑ 基名 oldRoman→newRoman
            line.append(Component.literal("↑ ").withStyle(TooltipBuilder.POSITIVE));
            line.append(ec.enchant().value().description().copy());
            line.append(Component.literal(" "));
            line.append(roman(ec.targetLevel()));
            line.append(Component.literal("→").withStyle(TooltipBuilder.POSITIVE));
            line.append(roman(ec.resultLevel()));
        } else {
            // 新增：✓ 全名
            line.append(Component.literal("✓ ").withStyle(TooltipBuilder.POSITIVE));
            line.append(Enchantment.getFullname(ec.enchant(), ec.resultLevel()));
        }
        return line;
    }

    /** 罗马数字 Component，复用原版 enchantment.level.<n> 翻译键。 */
    private static Component roman(int level) {
        return Component.translatable("enchantment.level." + level);
    }
}
