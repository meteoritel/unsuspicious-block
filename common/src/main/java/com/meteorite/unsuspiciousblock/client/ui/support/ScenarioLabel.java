package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSession;
import com.meteorite.unsuspiciousblock.client.ui.overlay.ScenarioParamsOverlay;
import com.meteorite.unsuspiciousblock.client.ui.tooltip.JournalTooltipSpec;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionPresentation;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionTree;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.SimulationOptions;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ConditionOption;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ProbabilityFormat;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.core.text.TooltipBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 场景与原子条件勾选的展示文案工具：提供简洁勾选文本、当前勾选摘要与规范暗底 Tooltip。
 */
public final class ScenarioLabel {
    private static final String CONDITION_PREFIX = "screen.unsuspiciousblock.archaeology_journal.condition.";
    private static final String KEY_PREFIX = "screen.unsuspiciousblock.archaeology_journal.";

    private ScenarioLabel() {
    }

    /**
     * 勾选框与列表行的紧凑文本：优先展示具象子描述（如具体群系/天气/开阔水域），避免千篇一律的通用前缀。
     */
    public static Component shortConditionLabel(ConditionPresentation condition) {
        if (condition.displayChildren().isEmpty()) {
            return conditionText(condition);
        }
        if (condition instanceof LootConditionInfo info && info.kind() != ConditionTree.Kind.LEAF) {
            return expression(condition);
        }
        List<? extends ConditionPresentation> children = condition.displayChildren();
        if (children.size() == 1) {
            return conditionText(children.getFirst());
        }
        MutableComponent joined = Component.empty();
        for (int index = 0; index < children.size(); index++) {
            if (index > 0) {
                joined.append(symbol(" · "));
            }
            joined.append(conditionText(children.get(index)));
        }
        return joined;
    }

    /**
     * 网格顶栏或页头的简洁状态摘要。
     */
    public static Component selectionSummary(SimulationOptions options, List<String> enabledConditions) {
        int total = options.conditions().size();
        if (total == 0) {
            return ScenarioSession.text("no_assumptions");
        }
        if (enabledConditions.isEmpty()) {
            return ScenarioSession.text("conditions.summary_count", 0, total);
        }
        if (enabledConditions.size() == 1) {
            String target = enabledConditions.getFirst();
            for (ConditionOption option : options.conditions()) {
                if (option.id().equals(target)) {
                    return ScenarioSession.text("conditions.summary_single",
                            shortConditionLabel(option.condition()).getString());
                }
            }
        }
        return ScenarioSession.text("conditions.summary_count", enabledConditions.size(), total);
    }

    /**
     * 网格顶栏场景选择按钮的 Tooltip（分段列出已选环境条件与当前工具/幸运/附魔配置）。
     */
    public static List<Component> selectionTooltipLines(SimulationOptions options, List<String> enabledConditions,
                                                      ScenarioParams params) {
        JournalTooltipSpec.Builder spec = JournalTooltipSpec.builder();
        int total = options.conditions().size();
        if (total == 0) {
            spec.line(ScenarioSession.text("no_assumptions"), TooltipBuilder.LABEL);
        } else if (enabledConditions.isEmpty()) {
            spec.line(ScenarioSession.text("conditions.none_checked", total), TooltipBuilder.LABEL);
        } else {
            spec.section(ScenarioSession.text("scene.tooltip.assumptions"));
            Set<String> enabled = new LinkedHashSet<>(enabledConditions);
            for (ConditionOption option : options.conditions()) {
                if (!enabled.contains(option.id())) {
                    continue;
                }
                spec.line(Component.literal("• ").withStyle(TooltipBuilder.POSITIVE)
                        .append(plainShortLabel(option.condition()).copy().withStyle(TooltipBuilder.BODY)));
            }
        }

        spec.blank();
        spec.section(ScenarioSession.text("params.title"));
        Component toolName = toolDisplayName(options, params);
        spec.line(Component.translatable(KEY_PREFIX + "simulation.tool",
                toolName.copy().withStyle(TooltipBuilder.BODY)).withStyle(TooltipBuilder.LABEL));
        spec.line(Component.translatable(KEY_PREFIX + "simulation.conditions.current_luck",
                Component.literal(ProbabilityFormat.formatLuck(params.luck())).withStyle(TooltipBuilder.ACCENT))
                .withStyle(TooltipBuilder.LABEL));
        if (!params.toolEnchantments().isEmpty()) {
            params.toolEnchantments().forEach((id, level) -> spec.line(
                    Component.literal("• ").withStyle(TooltipBuilder.HINT)
                            .append(ScenarioSession.text("params.enchant_level",
                                    ScenarioParamsOverlay.enchantmentName(id), level).copy().withStyle(TooltipBuilder.ACCENT))));
        }

        spec.blank();
        spec.line(ScenarioSession.text("conditions.tooltip_open"), TooltipBuilder.HINT);
        return spec.build().lines();
    }

    /**
     * 单个原子条件复选框的暗底 Tooltip（仅补充行内未呈现的完整描述、复合子条件与 F3+H ID，不复现图标与药丸）。
     */
    public static List<Component> conditionTooltipLines(ConditionPresentation condition, boolean checked) {
        JournalTooltipSpec.Builder spec = JournalTooltipSpec.builder();
        Component shortLabel = plainShortLabel(condition);
        spec.line(shortLabel, checked ? TooltipBuilder.POSITIVE : TooltipBuilder.BODY);

        String titleText = shortLabel.getString();
        Component fullDesc = displayDescription(condition);
        if (condition.displayChildren().isEmpty() && !fullDesc.getString().equals(titleText)) {
            spec.line(fullDesc, TooltipBuilder.LABEL);
        } else if (condition.displayChildren().size() > 1) {
            for (ConditionPresentation child : ProbabilityFormat.flattenGenericWrappers(condition.displayChildren())) {
                spec.line(Component.literal("• ").withStyle(TooltipBuilder.HINT)
                        .append(displayDescription(child).copy().withStyle(TooltipBuilder.LABEL)));
            }
        } else if (condition.displayChildren().size() == 1) {
            ConditionPresentation onlyChild = condition.displayChildren().getFirst();
            Component childDesc = displayDescription(onlyChild);
            if (!childDesc.getString().equals(titleText) && !ProbabilityFormat.isGenericWrapper(condition)) {
                spec.line(childDesc, TooltipBuilder.LABEL);
            }
        }

        if (JournalTooltipSpec.showAdvancedIds()) {
            spec.line(Component.literal(condition.conditionType().toString()), TooltipBuilder.HINT);
        }

        spec.line(ScenarioSession.text(checked ? "conditions.click_uncheck" : "conditions.click_check"),
                TooltipBuilder.HINT);
        return spec.build().lines();
    }

    public static Component symbol(String value) {
        return Component.literal(value).withStyle(style -> style.withColor(UiTextScheme.PARCHMENT.accent()));
    }

    public static Component expression(ConditionPresentation condition) {
        ConditionTree.Kind kind = condition instanceof LootConditionInfo node ? node.kind() : ConditionTree.Kind.LEAF;
        if (kind == ConditionTree.Kind.NOT && condition.displayChildren().size() == 1) {
            return symbol("¬(").copy().append(expression(condition.displayChildren().getFirst())).append(symbol(")"));
        }
        if (kind == ConditionTree.Kind.ANY || kind == ConditionTree.Kind.ALL) {
            var result = Component.empty().append(symbol("("));
            for (int index = 0; index < condition.displayChildren().size(); index++) {
                if (index > 0) {
                    result.append(symbol(kind == ConditionTree.Kind.ANY ? " ∨ " : " ∧ "));
                }
                result.append(expression(condition.displayChildren().get(index)));
            }
            return result.append(symbol(")"));
        }
        var label = conditionText(condition).copy();
        if (!condition.displayChildren().isEmpty()) {
            label.append(symbol("("));
            for (int index = 0; index < condition.displayChildren().size(); index++) {
                if (index > 0) {
                    label.append(symbol(" · "));
                }
                label.append(expression(condition.displayChildren().get(index)));
            }
            label.append(symbol(")"));
        }
        return label;
    }

    public static Component conditionText(ConditionPresentation condition) {
        var label = displayDescription(condition).copy().withStyle(style -> style.withColor(UiTextScheme.PARCHMENT.body()));
        if (label.getContents() instanceof TranslatableContents contents) {
            Object[] values = contents.getArgs().clone();
            for (int index = 0; index < values.length; index++) {
                Component value = values[index] instanceof Component component ? component.copy()
                        : Component.literal(String.valueOf(values[index]));
                values[index] = value.copy().withStyle(style -> style.withColor(UiTextScheme.PARCHMENT.positive()));
            }
            String shortKey = contents.getKey().startsWith(CONDITION_PREFIX)
                    ? "screen.unsuspiciousblock.archaeology_journal.simulation.short."
                    + contents.getKey().substring(CONDITION_PREFIX.length())
                    : "";
            label = Component.translatable(Language.getInstance().has(shortKey)
                    ? shortKey : contents.getKey(), values).setStyle(label.getStyle());
        }
        return label;
    }

    public static Component displayDescription(ConditionPresentation node) {
        Component description = node.description();
        if (description.getContents() instanceof TranslatableContents contents
                && contents.getKey().equals(CONDITION_PREFIX + "location_check_biomes")
                && contents.getArgs().length == 1) {
            Object value = contents.getArgs()[0];
            String id = value instanceof Component component ? component.getString() : String.valueOf(value);
            if (id.equals("#c:is_swamp")) {
                return Component.translatable(contents.getKey(),
                        Component.translatable(CONDITION_PREFIX + "tag.c.is_swamp")).setStyle(description.getStyle());
            }
        }
        return description.copy();
    }

    /**
     * 从暗底上下文安全提取纯文本条件短名称（保留语义但剥离羊皮纸文字着色）。
     */
    public static Component plainShortLabel(ConditionPresentation condition) {
        return Component.literal(shortConditionLabel(condition).getString());
    }

    private static Component toolDisplayName(SimulationOptions options, ScenarioParams params) {
        for (var tool : options.tools()) {
            if (tool.id().equals(params.toolId())) {
                return tool.displayName();
            }
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(params.toolId())).getHoverName();
    }
}
