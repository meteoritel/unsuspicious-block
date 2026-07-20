package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.ui.panel.ItemGridPanel;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 考古笔记物品 tooltip 构建器，负责将 {@link ItemGridPanel.TooltipData} 转换为 tooltip 行列表。
 * <p>
 * 从 {@link com.meteorite.unsuspiciousblock.client.ui.screen.ArchaeologyJournalScreen} 的 render 方法中提取，
 * 职责包括：
 * <ul>
 *   <li>物品名、获取数量、概率信息</li>
 *   <li>概率不确定性提示</li>
 *   <li>外部注入标记</li>
 *   <li>条件树形结构递归渲染</li>
 *   <li>父表条件与子表来源标注</li>
 * </ul>
 */
public final class JournalTooltipBuilder {

    private JournalTooltipBuilder() {
    }

    /**
     * 从 TooltipData 构建 tooltip 行列表。
     *
     * @param data tooltip 数据
     * @return tooltip 行列表，用于 {@code GuiGraphics.renderTooltip}
     */
    public static List<Component> build(ItemGridPanel.TooltipData data) {
        List<Component> lines = new ArrayList<>();

        // 物品名
        lines.add(data.stack().getHoverName().copy().withStyle(ChatFormatting.WHITE));

        // 获取数量
        if (data.count() >= 0) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.acquired", data.count())
                    .copy().withStyle(ChatFormatting.GREEN));
        }

        // 概率信息
        if (data.probability() != null) {
            boolean probUncertain = data.probability().equals("?");
            boolean hintIsApprox = data.hint() != null && data.hint().getString().equals(
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.item_hint.approximate").getString());
            ChatFormatting probColor = switch (data.uncertaintyLevel()) {
                case PROBABILISTIC -> ChatFormatting.GOLD;
                case RUNTIME -> ChatFormatting.RED;
                default -> ChatFormatting.GOLD;
            };
            if (probUncertain && hintIsApprox) {
                lines.add(Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.probability_uncertain_approx")
                        .copy().withStyle(probColor));
            } else if (probUncertain) {
                lines.add(Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.probability_uncertain")
                        .copy().withStyle(probColor));
            } else {
                lines.add(formatProbabilityComponent(data.probability())
                        .copy().withStyle(probColor));
            }
        }

        // 提示文本（近似概率等）
        if (data.hint() != null) {
            boolean probUncertain = data.probability() != null && data.probability().equals("?");
            boolean hintIsApprox = data.hint().getString().equals(
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.item_hint.approximate").getString());
            if (!(probUncertain && hintIsApprox)) {
                lines.add(data.hint().copy().withStyle(ChatFormatting.DARK_GREEN, ChatFormatting.ITALIC));
            }
        }

        // 外部注入标记
        if (data.injected()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.injected_loot")
                    .copy().withStyle(ChatFormatting.AQUA, ChatFormatting.ITALIC));
        }

        // 条件信息（树形结构，递归渲染）
        if (!data.conditions().isEmpty()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.conditions_header")
                    .copy().withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE));
            appendConditionTree(lines, data.conditions(), "");
        }

        // 子表条件（来自 loot_table 引用条目的 conditions）
        if (!data.parentTableConditions().isEmpty()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.parent_table_conditions_header")
                    .copy().withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE));
            appendConditionTree(lines, data.parentTableConditions(), "");
        }

        // 子表来源标注
        if (data.sourceChildTable() != null
                && LootTableNames.isArchaeologyLootTable(data.sourceChildTable())) {
            Component childTableName = LootTableNames.resolveDisplayName(data.sourceChildTable());
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.from_child_table", childTableName)
                    .copy().withStyle(ChatFormatting.AQUA, ChatFormatting.ITALIC));
        }

        return lines;
    }

    // 格式化概率为 tooltip Component
    private static Component formatProbabilityComponent(String probability) {
        if (probability == null || probability.equals("?")) {
            return Component.translatable("screen.unsuspiciousblock.archaeology_journal.probability_unknown");
        }
        return Component.translatable("screen.unsuspiciousblock.archaeology_journal.probability", probability);
    }

    // 递归渲染条件树到 tooltip 行列表
    private static void appendConditionTree(List<Component> lines, List<LootConditionInfo> conditions, String prefix) {
        for (int i = 0; i < conditions.size(); i++) {
            LootConditionInfo info = conditions.get(i);
            boolean isLast = i == conditions.size() - 1;
            String branch = isLast ? "└─ " : "├─ ";
            String childPrefix = isLast ? "   " : "│  ";

            ChatFormatting color = getConditionColor(info.conditionType());
            String text = prefix + branch + info.description().getString();
            lines.add(Component.literal(text).withStyle(color));

            if (!info.children().isEmpty()) {
                appendConditionTree(lines, info.children(), prefix + childPrefix);
            }
        }
    }

    // 根据条件类型返回对应颜色
    private static ChatFormatting getConditionColor(ResourceLocation conditionType) {
        var handler = LootConditionHandlers.get(conditionType);
        if (handler == null) return ChatFormatting.WHITE;
        return switch (handler.uncertaintyLevel()) {
            case NONE -> ChatFormatting.GREEN;
            case PROBABILISTIC -> ChatFormatting.GOLD;
            case RUNTIME -> ChatFormatting.RED;
        };
    }
}