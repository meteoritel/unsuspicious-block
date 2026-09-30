package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiNode;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiAction;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioLabel;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.loottable.catalog.SimulationOptions;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 场景详情页的数据投影：按场景假设与子表入口分组，条件树供页内文档和灯箱使用。
 *
 * <p>只读客户端已有数据，不做业务判断，也不触发任何请求；重建由内容版本号门控。</p>
 */
final class ScenarioPageBuilder {
    private static final int INDENT_STEP = 8;
    /** 条件行的缩进：与区标题拉开一档。 */
    private static final int ITEM_INDENT = 12;

    /** 标题标记供页内视图绘制分组底纹，折行后仍保留同一层级。 */
    enum Heading { SECTION, ENTRY }

    private ScenarioPageBuilder() {}

    static List<UiNode> buildConditions(CatalogTableDto structure, String sceneKey, boolean showOther,
                                       UiAction toggleOther,
                                       Consumer<ResourceLocation> recommend) {
        SimulationOptions options = structure.options();
        if (options == null) return List.of();
        List<LootConditionInfo> atoms = ScenarioLabel.positiveAssumptions(options, sceneKey);
        List<UiNode> content = new ArrayList<>();
        content.add(heading(conditionHeader(options, sceneKey), Heading.SECTION,
                ScenarioLabel.definition(options, sceneKey)));
        content.add(new UiNode.Gap(3));
        for (LootConditionInfo atom : atoms) addCondition(content, atom, ITEM_INDENT, 0);
        List<LootConditionInfo> negatives = ScenarioLabel.negativeConditions(options, sceneKey);
        if (!negatives.isEmpty()) {
            int parent = 0;
            if (!ScenarioLabel.isBaseline(sceneKey)) {
                content.add(new UiNode.Gap(4));
                parent = content.size();
                content.add(action(showOther ? "conditions.hide_other"
                        : "conditions.show_other", ScenarioUi.Icon.DOWN, toggleOther));
            }
            if (showOther || ScenarioLabel.isBaseline(sceneKey)) {
                for (LootConditionInfo negative : negatives) addCondition(content, negative, ITEM_INDENT, parent);
            }
        }
        if (!structure.childProbabilities().isEmpty()) {
            content.add(new UiNode.Gap(8));
            content.add(heading(ScenarioSimulationClientState.text("conditions.entries"), Heading.SECTION, List.of()));
            content.add(new UiNode.Gap(4));
            for (var child : structure.childProbabilities()) {
                var childTable = ScenarioSimulationClientState.table(child.tableId());
                Component name = childTable == null ? Component.literal(child.tableId().toString()) : childTable.displayName();
                int parent = content.size();
                content.add(heading(name, Heading.ENTRY, List.of(name, Component.literal(child.tableId().toString()))));
                if (child.conditions().isEmpty()) content.add(new UiNode.Row(ITEM_INDENT, parent,
                        ScenarioSimulationClientState.text("conditions.no_entry_gate"), UiTextPalette.Parchment.LABEL,
                        List.of(), List.of(), null, null));
                for (var condition : child.conditions()) addCondition(content, condition, ITEM_INDENT, parent);
                content.add(action("recommend.title", ScenarioUi.Icon.RECOMMEND, () -> recommend.accept(child.tableId())));
                content.add(new UiNode.Gap(7));
            }
        }
        return List.copyOf(content);
    }

    private static UiNode.Row heading(Component text, Heading kind, List<Component> tooltip) {
        return new UiNode.Row(4, UiNode.NO_PARENT, text.copy().withStyle(ChatFormatting.BOLD),
                UiTextPalette.Parchment.TITLE, List.of(), tooltip, kind, null);
    }

    private static UiNode.Row action(String key, ScenarioUi.Icon icon, UiAction action) {
        Component text = ScenarioSimulationClientState.text(key);
        return new UiNode.Row(4, UiNode.NO_PARENT,
                new UiNode.InlineIcon(ScenarioUi.icon(icon), List.of(text), null, action),
                text.copy().withStyle(ChatFormatting.UNDERLINE),
                UiTextPalette.Parchment.BODY, List.of(), List.of(text), null, action);
    }

    // 条件区标题：表本身没有可调条件 → 沿用「无场景条件」；基准 → 如实说明全部为假；其余 → 本场景成立者。
    private static Component conditionHeader(SimulationOptions options, String sceneKey) {
        if (!ScenarioLabel.isBaseline(sceneKey)) return ScenarioSimulationClientState.text("scene_conditions");
        int negatives = ScenarioLabel.negativeAssumptions(options, sceneKey).size();
        return negatives == 0 ? ScenarioSimulationClientState.text("no_assumptions")
                : ScenarioSimulationClientState.text("baseline_all_false", negatives);
    }

    private static void addCondition(List<UiNode> nodes, LootConditionInfo condition, int indent, int parent) {
        int self = nodes.size();
        String fidelity = condition.metadata().get(LootConditionHandlers.FIDELITY_METADATA_KEY);
        Component description = ScenarioLabel.displayDescription(condition);
        int color = UiTextPalette.Parchment.BODY;
        if (fidelity != null) {
            description = description.copy().append(" · ").append(ScenarioSimulationClientState.text("conditions." + fidelity))
                    .withStyle(net.minecraft.ChatFormatting.ITALIC);
            color = UiTextPalette.Parchment.LABEL;
        }
        nodes.add(new UiNode.Row(indent, parent, description, color,
                List.of(), List.of(condition.description().copy(), Component.literal(condition.conditionType().toString())), null, null));
        for (LootConditionInfo child : condition.children()) {
            addCondition(nodes, child, indent + INDENT_STEP, self);
        }
    }

}
