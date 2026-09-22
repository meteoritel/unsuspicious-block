package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiIcon;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiNode;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioLabel;
import com.meteorite.unsuspiciousblock.client.ui.support.ScenarioPresentation;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.loottable.catalog.SimulationOptions;
import com.meteorite.unsuspiciousblock.loottable.simulation.ProbabilityFormat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 场景详情页的内容构建器：把「场景假设 + 该输入的测量结果」折叠成一段块序列。上半是条件区
 * （本场景在基准之上成立的条件），下半是可达条目区（本场景确实能产出的条目与概率）。
 *
 * <p>只读客户端已有数据，不做业务判断，也不触发任何请求；重建由内容版本号门控。</p>
 */
final class ScenarioPageBuilder {
    private static final int INDENT_STEP = 12;
    /** 条件行与条目行的缩进：与区标题拉开一档，读作「标题下的清单」。 */
    private static final int ITEM_INDENT = 12;
    /** 条目区最多列出的条数：再多就交给网格页，避免框内变成一个需要大幅缩放的清单。 */
    private static final int MAX_OUTCOMES = 12;

    private ScenarioPageBuilder() {}

    static List<UiNode> buildContent(CatalogTableDto structure, String sceneKey, ScenarioPresentation presentation) {
        SimulationOptions options = structure.options();
        if (options == null) return List.of();
        List<LootConditionInfo> atoms = ScenarioLabel.positiveAssumptions(options, sceneKey);
        List<UiNode> content = new ArrayList<>();
        content.add(new UiNode.Row(0, UiNode.NO_PARENT, conditionHeader(options, sceneKey),
                UiTextPalette.Parchment.TITLE, List.of(), ScenarioLabel.definition(options, sceneKey), null, null));
        for (LootConditionInfo atom : atoms) addCondition(content, atom, ITEM_INDENT, UiNode.NO_PARENT);

        content.add(new UiNode.Divider(UiTextPalette.Parchment.HINT));
        List<Outcome> outcomes = outcomes(sceneKey, presentation);
        content.add(new UiNode.Row(0, UiNode.NO_PARENT, outcomesHeader(presentation, outcomes),
                UiTextPalette.Parchment.TITLE, List.of(), List.of(), null, null));
        int listed = Math.min(outcomes.size(), MAX_OUTCOMES);
        for (Outcome outcome : outcomes.subList(0, listed)) content.add(outcomeRow(outcome));
        if (outcomes.size() > listed) {
            content.add(new UiNode.Row(ITEM_INDENT, UiNode.NO_PARENT, null,
                    ScenarioSimulationClientState.text("outcome_more", outcomes.size() - listed),
                    UiTextPalette.Parchment.LABEL, List.of(), List.of(), null, null));
        }
        return List.copyOf(content);
    }

    // 条件区标题：表本身没有可调条件 → 沿用「无场景条件」；基准 → 如实说明全部为假；其余 → 本场景成立者。
    private static Component conditionHeader(SimulationOptions options, String sceneKey) {
        if (!ScenarioLabel.isBaseline(sceneKey)) return ScenarioSimulationClientState.text("scene_conditions");
        int negatives = ScenarioLabel.negativeAssumptions(options, sceneKey).size();
        return negatives == 0 ? ScenarioSimulationClientState.text("no_assumptions")
                : ScenarioSimulationClientState.text("baseline_all_false", negatives);
    }

    private static Component outcomesHeader(ScenarioPresentation presentation, List<Outcome> outcomes) {
        if (!presentation.status().equals("cached")) {
            return ScenarioSimulationClientState.text("outcome_uncomputed",
                    ScenarioSimulationClientState.text(presentation.status()));
        }
        return outcomes.isEmpty() ? ScenarioSimulationClientState.text("outcome_empty")
                : ScenarioSimulationClientState.text("outcome_header", outcomes.size());
    }

    private static void addCondition(List<UiNode> nodes, LootConditionInfo condition, int indent, int parent) {
        int self = nodes.size();
        nodes.add(new UiNode.Row(indent, parent, condition.description().copy(), UiTextPalette.Parchment.BODY,
                List.of(), List.of(condition.description().copy()), null, null));
        for (LootConditionInfo child : condition.children()) {
            addCondition(nodes, child, indent + INDENT_STEP, self);
        }
    }

    private static UiNode outcomeRow(Outcome outcome) {
        Component probability = ProbabilityFormat.formatComponent(outcome.probability());
        List<Component> tooltip = List.of(outcome.item().displayName().copy(), probability);
        return new UiNode.Row(ITEM_INDENT, UiNode.NO_PARENT,
                new UiNode.InlineIcon(new UiIcon.Item(outcome.stack()), tooltip, outcome.item().signature(), null),
                outcome.item().displayName().copy().append(Component.literal("  ")).append(probability),
                UiTextPalette.Parchment.BODY, List.of(), tooltip, null, null);
    }

    /**
     * 本场景的条目与概率，按概率降序。只收「已测到且非零命中」的条目——本区回答的是
     * 「这个场景能刷到什么」，不可达与未命中的条目留给网格页的四态展示。
     */
    private static List<Outcome> outcomes(String sceneKey, ScenarioPresentation presentation) {
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
    private record Outcome(CatalogTableDto.ItemEntry item, Probability probability, ItemStack stack) {}
}
