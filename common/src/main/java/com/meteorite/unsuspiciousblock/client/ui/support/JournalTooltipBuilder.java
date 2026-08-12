package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.ui.panel.ItemGridPanel;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandler;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
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

        // 未发现物品只展示状态与获取条件，避免提前泄露物品身份
        if (data.discovered()) {
            lines.add(data.stack().getHoverName().copy().withStyle(ChatFormatting.WHITE));
            JournalItemDetailAppender.append(lines, data.stack());
        } else {
            lines.add(Component.translatable("screen.unsuspiciousblock.archaeology_journal.undiscovered")
                    .copy().withStyle(ChatFormatting.GRAY));
        }

        // 获取数量
        if (data.discovered() && data.count() >= 0) {
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
                case RUNTIME -> ChatFormatting.YELLOW;
                default -> probUncertain ? ChatFormatting.GRAY : ChatFormatting.GREEN;
            };
            if (probUncertain && hintIsApprox) {
                lines.add(Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.probability_uncertain_approx")
                        .copy().withStyle(probColor));
            } else if (probUncertain) {
                lines.add(Component.translatable(
                                data.uncertaintyLevel() == LootConditionHandler.UncertaintyLevel.NONE
                                        ? "screen.unsuspiciousblock.archaeology_journal.probability_unknown"
                                        : "screen.unsuspiciousblock.archaeology_journal.probability_uncertain")
                        .copy().withStyle(probColor));
            } else if (data.uncertaintyLevel() == LootConditionHandler.UncertaintyLevel.PROBABILISTIC) {
                lines.add(Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.probability_estimated", data.probability())
                        .copy().withStyle(probColor));
            } else if (data.uncertaintyLevel() == LootConditionHandler.UncertaintyLevel.RUNTIME) {
                lines.add(Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.probability_conditional_value", data.probability())
                        .copy().withStyle(probColor));
            } else {
                lines.add(formatProbabilityComponent(data.probability())
                        .copy().withStyle(probColor));
            }
        }

        // 提示文本（近似概率等）
        if (data.discovered() && data.hint() != null) {
            boolean probUncertain = data.probability() != null && data.probability().equals("?");
            boolean hintIsApprox = data.hint().getString().equals(
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.item_hint.approximate").getString());
            if (!(probUncertain && hintIsApprox)) {
                lines.add(data.hint().copy().withStyle(ChatFormatting.DARK_GREEN, ChatFormatting.ITALIC));
            }
        }

        // 外部注入标记
        if (data.discovered() && data.injected()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.injected_loot")
                    .copy().withStyle(ChatFormatting.AQUA, ChatFormatting.ITALIC));
        }

        appendAcquisitionPaths(lines, data.acquisitionPaths());

        return lines;
    }

    // 格式化概率为 tooltip Component
    private static Component formatProbabilityComponent(String probability) {
        if (probability == null || probability.equals("?")) {
            return Component.translatable("screen.unsuspiciousblock.archaeology_journal.probability_unknown");
        }
        return Component.translatable("screen.unsuspiciousblock.archaeology_journal.probability", probability);
    }

    private static void appendAcquisitionPaths(List<Component> lines, List<LootAcquisitionPath> paths) {
        if (paths.isEmpty()) {
            return;
        }
        if (paths.size() == 1) {
            appendSinglePath(lines, paths.getFirst());
            return;
        }

        lines.add(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.acquisition_paths_header")
                .copy().withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE));
        for (int i = 0; i < paths.size(); i++) {
            LootAcquisitionPath path = paths.get(i);
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.acquisition_path", i + 1)
                    .copy().withStyle(ChatFormatting.AQUA));
            if (path.hasConditions()) {
                appendConditionTree(lines, path.allConditions(), "  ");
            } else if (path.sourceChildTable() == null) {
                lines.add(Component.literal("  ").append(Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.acquisition_path_unconditional"))
                        .withStyle(ChatFormatting.GREEN));
            }
            appendSourceTable(lines, path.sourceChildTable(), "  ");
            appendSourceItemTag(lines, path.sourceItemTag(), "  ");
        }
    }

    private static void appendSinglePath(List<Component> lines, LootAcquisitionPath path) {
        if (!path.entryConditions().isEmpty()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.conditions_header")
                    .copy().withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE));
            appendConditionTree(lines, path.entryConditions(), "");
        }
        if (!path.inheritedConditions().isEmpty()) {
            lines.add(Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.inherited_conditions_header")
                    .copy().withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE));
            appendConditionTree(lines, path.inheritedConditions(), "");
        }
        appendSourceTable(lines, path.sourceChildTable(), "");
        appendSourceItemTag(lines, path.sourceItemTag(), "");
    }

    private static void appendSourceTable(List<Component> lines, ResourceLocation sourceChildTable, String prefix) {
        if (sourceChildTable == null
                || !ArchaeologyJournalClientState.getCatalog().containsKey(sourceChildTable)) {
            return;
        }
        Component childTableName = LootTableNames.resolveDisplayName(sourceChildTable);
        lines.add(Component.literal(prefix).append(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.from_child_table", childTableName))
                .withStyle(ChatFormatting.AQUA, ChatFormatting.ITALIC));
    }

    private static void appendSourceItemTag(List<Component> lines,
                                            @org.jetbrains.annotations.Nullable ResourceLocation sourceItemTag,
                                            String prefix) {
        if (sourceItemTag == null) {
            return;
        }
        lines.add(Component.literal(prefix).append(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.from_item_tag",
                Component.literal(sourceItemTag.toString())))
                .withStyle(ChatFormatting.AQUA, ChatFormatting.ITALIC));
    }

    // 递归渲染条件树到 tooltip 行列表
    private static void appendConditionTree(List<Component> lines, List<LootConditionInfo> conditions, String prefix) {
        for (int i = 0; i < conditions.size(); i++) {
            LootConditionInfo info = conditions.get(i);
            boolean isLast = i == conditions.size() - 1;
            String branch = isLast ? "└─ " : "├─ ";
            String childPrefix = isLast ? "   " : "│  ";

            ChatFormatting color = getConditionColor(info.conditionType());
            lines.add(Component.literal(prefix + branch).withStyle(ChatFormatting.DARK_GRAY)
                    .append(info.description().copy().withStyle(color)));

            if (!info.children().isEmpty()) {
                appendConditionTree(lines, info.children(), prefix + childPrefix);
            }
        }
    }

    // 根据条件类型返回对应颜色
    private static ChatFormatting getConditionColor(ResourceLocation conditionType) {
        var handler = LootConditionHandlers.get(conditionType);
        if (handler == null) return ChatFormatting.GRAY;
        return switch (handler.uncertaintyLevel()) {
            case NONE -> ChatFormatting.GREEN;
            case PROBABILISTIC -> ChatFormatting.GOLD;
            case RUNTIME -> ChatFormatting.YELLOW;
        };
    }
}
