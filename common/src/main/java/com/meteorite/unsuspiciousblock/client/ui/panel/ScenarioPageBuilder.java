package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiNode;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiAction;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioLabel;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioPresentation;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioUi;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.loottable.catalog.SimulationOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * 场景详情页的数据投影：条件树供文档和灯箱使用，可达条目供独立的结果列表使用。
 *
 * <p>只读客户端已有数据，不做业务判断，也不触发任何请求；重建由内容版本号门控。</p>
 */
final class ScenarioPageBuilder {
    private static final int INDENT_STEP = 8;
    /** 条件行的缩进：与区标题拉开一档。 */
    private static final int ITEM_INDENT = 8;

    private ScenarioPageBuilder() {}

    static List<UiNode> buildConditions(CatalogTableDto structure, String sceneKey, boolean showOther,
                                       UiAction toggleOther, UiAction openParams,
                                       Consumer<ResourceLocation> recommend) {
        SimulationOptions options = structure.options();
        if (options == null) return List.of();
        List<LootConditionInfo> atoms = ScenarioLabel.positiveAssumptions(options, sceneKey);
        List<UiNode> content = new ArrayList<>();
        content.add(new UiNode.Row(0, UiNode.NO_PARENT, conditionHeader(options, sceneKey),
                UiTextPalette.Parchment.TITLE, List.of(), ScenarioLabel.definition(options, sceneKey), null, null));
        for (LootConditionInfo atom : atoms) addCondition(content, atom, ITEM_INDENT, UiNode.NO_PARENT);
        List<LootConditionInfo> negatives = ScenarioLabel.negativeConditions(options, sceneKey);
        if (!negatives.isEmpty()) {
            if (!ScenarioLabel.isBaseline(sceneKey)) content.add(action(showOther ? "conditions.hide_other"
                    : "conditions.show_other", ScenarioUi.Icon.DOWN, toggleOther));
            if (showOther || ScenarioLabel.isBaseline(sceneKey)) {
                for (LootConditionInfo negative : negatives) addCondition(content, negative, ITEM_INDENT, UiNode.NO_PARENT);
            }
        }
        if (!structure.childProbabilities().isEmpty()) {
            content.add(new UiNode.Gap(5));
            content.add(new UiNode.Divider(ScenarioUi.BRANCH));
            content.add(new UiNode.Row(ScenarioSimulationClientState.text("conditions.entries"), UiTextPalette.Parchment.TITLE));
            for (var child : structure.childProbabilities()) {
                var childTable = ScenarioSimulationClientState.table(child.tableId());
                Component name = childTable == null ? Component.literal(child.tableId().toString()) : childTable.displayName();
                int parent = content.size();
                content.add(new UiNode.Row(0, UiNode.NO_PARENT, name, UiTextPalette.Parchment.NAME,
                        List.of(), List.of(name, Component.literal(child.tableId().toString())), null, null));
                if (child.conditions().isEmpty()) content.add(new UiNode.Row(ITEM_INDENT, parent,
                        ScenarioSimulationClientState.text("conditions.no_entry_gate"), UiTextPalette.Parchment.HINT,
                        List.of(), List.of(), null, null));
                for (var condition : child.conditions()) addCondition(content, condition, ITEM_INDENT, parent);
                content.add(action("recommend.title", ScenarioUi.Icon.RECOMMEND, () -> recommend.accept(child.tableId())));
                content.add(new UiNode.Gap(3));
            }
            content.add(action("params.title", ScenarioUi.Icon.PARAMS, openParams));
        }
        return List.copyOf(content);
    }

    private static UiNode.Row action(String key, ScenarioUi.Icon icon, UiAction action) {
        Component text = ScenarioSimulationClientState.text(key);
        return new UiNode.Row(0, UiNode.NO_PARENT,
                new UiNode.InlineIcon(ScenarioUi.icon(icon), List.of(text), null, action), text,
                UiTextPalette.Parchment.ACCENT, List.of(), List.of(text), null, action);
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
            color = UiTextPalette.Parchment.NEGATIVE;
        }
        nodes.add(new UiNode.Row(indent, parent, description, color,
                List.of(), List.of(condition.description().copy(), Component.literal(condition.conditionType().toString())), null, null));
        for (LootConditionInfo child : condition.children()) {
            addCondition(nodes, child, indent + INDENT_STEP, self);
        }
    }

    /**
     * 本场景的条目与概率，按概率降序。只收「已测到且非零命中」的条目——本区回答的是
     * 「这个场景能刷到什么」，不可达与未命中的条目留给网格页的四态展示。
     */
    static List<Outcome> outcomes(String sceneKey, ScenarioPresentation presentation) {
        CatalogTableDto source = presentation.source();
        if (source == null) return List.of();
        List<Outcome> outcomes = new ArrayList<>();
        for (CatalogTableDto.ItemEntry item : source.items()) {
            Probability probability = sceneProbability(item, sceneKey);
            if (probability == null && presentation.exactInput()) probability = item.probability();
            if (probability == null || !probability.isMeasured() || probability.isZeroHit()) continue;
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(item.id()));
            if (stack.isEmpty()) continue;
            outcomes.add(new Outcome(item, probability, stack));
        }
        outcomes.sort(Comparator.comparingDouble((Outcome outcome) -> outcome.probability().lowerBound()).reversed());
        return List.copyOf(outcomes);
    }

    @Nullable
    private static Probability sceneProbability(CatalogTableDto.ItemEntry item, String sceneKey) {
        for (var ref : item.scenarioProbabilities()) {
            if (ref.scenarioKey().equals(sceneKey)) return ref.probability();
        }
        return null;
    }

    /** 一条可达条目：物品条目、在本场景的概率，以及已解析好的物品栈（图标与展示名共用）。 */
    record Outcome(CatalogTableDto.ItemEntry item, Probability probability, ItemStack stack) {}
}
