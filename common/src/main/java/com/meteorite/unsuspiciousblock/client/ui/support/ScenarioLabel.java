package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.client.state.ScenarioSession;
import com.meteorite.unsuspiciousblock.client.ui.overlay.ScenarioParamsOverlay;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionPresentation;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionTree;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.SimulationOptions;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ConditionOption;
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
 * 场景与原子条件勾选的展示文案工具：提供简洁勾选文本、当前勾选摘要与完整暗底 Tooltip。
 */
public final class ScenarioLabel {
    private static final String CONDITION_PREFIX = "screen.unsuspiciousblock.archaeology_journal.condition.";

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
     * 暗底 Tooltip：展示当前勾选条件与工具/幸运/附魔摘要（严格使用 {@link TooltipBuilder} 亮色语义）。
     */
    public static List<Component> selectionTooltip(SimulationOptions options, List<String> enabledConditions,
                                                   ScenarioParams params) {
        List<Component> lines = new ArrayList<>();
        lines.add(ScenarioSession.text("scene.tooltip.assumptions").copy().withStyle(TooltipBuilder.TITLE));
        if (options.conditions().isEmpty()) {
            lines.add(ScenarioSession.text("no_assumptions").copy().withStyle(TooltipBuilder.LABEL));
        } else if (enabledConditions.isEmpty()) {
            lines.add(ScenarioSession.text("conditions.none_checked", options.conditions().size())
                    .copy().withStyle(TooltipBuilder.LABEL));
        } else {
            Set<String> enabled = new LinkedHashSet<>(enabledConditions);
            for (ConditionOption option : options.conditions()) {
                if (!enabled.contains(option.id())) {
                    continue;
                }
                lines.add(Component.literal("✓ ").withStyle(TooltipBuilder.POSITIVE)
                        .append(shortConditionLabel(option.condition()).copy().withStyle(TooltipBuilder.BODY)));
            }
        }
        Component toolName = toolDisplayName(options, params);
        lines.add(ScenarioSession.text("params.current", toolName, params.luck(), params.sampleCount())
                .copy().withStyle(TooltipBuilder.LABEL));
        params.toolEnchantments().forEach((id, level) -> lines.add(
                ScenarioSession.text("params.enchant_level",
                                ScenarioParamsOverlay.enchantmentName(id), level)
                        .copy().withStyle(TooltipBuilder.ACCENT)));
        return List.copyOf(lines);
    }

    /**
     * 单个原子条件的暗底完整 Tooltip。
     */
    public static List<Component> conditionTooltip(ConditionPresentation condition, boolean checked) {
        List<Component> lines = new ArrayList<>();
        lines.add(shortConditionLabel(condition).copy().withStyle(TooltipBuilder.TITLE));
        lines.add(displayDescription(condition).copy().withStyle(TooltipBuilder.BODY));
        for (ConditionPresentation child : condition.displayChildren()) {
            lines.add(Component.literal("• ").withStyle(TooltipBuilder.ACCENT)
                    .append(displayDescription(child).copy().withStyle(TooltipBuilder.LABEL)));
        }
        lines.add(Component.literal(condition.conditionType().toString()).withStyle(TooltipBuilder.HINT));
        lines.add(ScenarioSession.text(checked
                        ? "conditions.click_uncheck" : "conditions.click_check")
                .copy().withStyle(TooltipBuilder.POSITIVE));
        return List.copyOf(lines);
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

    private static Component toolDisplayName(SimulationOptions options, ScenarioParams params) {
        for (var tool : options.tools()) {
            if (tool.id().equals(params.toolId())) {
                return tool.displayName();
            }
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(params.toolId())).getHoverName();
    }
}
