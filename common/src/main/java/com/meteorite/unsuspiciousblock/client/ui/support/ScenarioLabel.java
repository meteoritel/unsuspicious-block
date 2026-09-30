package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.SimulationOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * 场景的展示口径：可读名、条件行文本与完整定义。
 *
 * <p>场景的假设是一张「指纹 → 布尔」的完整赋值表：为真的放原条件，为假的放合成的
 * {@code minecraft:inverted} 包装。因此**相对基准的差异就是被置真的那一批**，展示只取它们，
 * 否则每个场景页都会多出整屏恒为「不成立」的重复行（基准页本身除外，见
 * {@link #definition}）。</p>
 *
 * <p>摘要保留服务端的组合结构；标题不再拼接叶子，实体目标与不完整描述不折叠。</p>
 */
public final class ScenarioLabel {
    private static final String CONDITION_PREFIX = "screen.unsuspiciousblock.archaeology_journal.condition.";
    private static final ResourceLocation INVERTED = ResourceLocation.withDefaultNamespace("inverted");
    private static final String BASELINE = "baseline";

    private ScenarioLabel() {
    }

    public static boolean isBaseline(String sceneKey) { return BASELINE.equals(sceneKey); }

    /** 有限宽页头的短名称；条件摘要与完整定义单独提供。 */
    public static Component shortLabel(String sceneKey) {
        return isBaseline(sceneKey)
                ? ScenarioSimulationClientState.text("baseline")
                : ScenarioSimulationClientState.text("scene", ordinal(sceneKey));
    }

    /** 列表第二行的条件摘要，不重复场景序号。 */
    public static Component detailLabel(SimulationOptions options, String sceneKey) {
        if (isBaseline(sceneKey)) {
            int count = negativeAssumptions(options, sceneKey).size();
            return count == 0 ? ScenarioSimulationClientState.text("no_assumptions")
                    : ScenarioSimulationClientState.text("baseline_all_false", count);
        }
        List<LootConditionInfo> atoms = positiveAssumptions(options, sceneKey);
        if (atoms.isEmpty()) return ScenarioSimulationClientState.text("no_assumptions");
        return Component.literal(String.join("; ", atoms.stream().map(ScenarioLabel::describe).toList()));
    }

    /** 场景的完整定义（tooltip）：基准列出全部被置假的条件，其余场景列出为真者与「其余不成立」。 */
    public static List<Component> definition(SimulationOptions options, String sceneKey) {
        List<Component> lines = new ArrayList<>();
        if (isBaseline(sceneKey)) {
            List<Component> negatives = negativeAssumptions(options, sceneKey);
            lines.add(ScenarioSimulationClientState.text("baseline_all_false", negatives.size()));
            lines.addAll(negatives);
            return List.copyOf(lines);
        }
        List<LootConditionInfo> atoms = positiveAssumptions(options, sceneKey);
        if (atoms.isEmpty()) {
            lines.add(ScenarioSimulationClientState.text("no_assumptions"));
        } else {
            for (LootConditionInfo atom : atoms) lines.add(Component.literal(describe(atom)));
        }
        lines.add(ScenarioSimulationClientState.text("conditions_others_false"));
        return List.copyOf(lines);
    }

    /** 场景相对基准成立的条件；只过滤合成取反包装，保留完整逻辑树。 */
    public static List<LootConditionInfo> positiveAssumptions(SimulationOptions options, String sceneKey) {
        for (var scene : options.scenes()) {
            if (!scene.scenarioKey().equals(sceneKey)) continue;
            List<LootConditionInfo> atoms = new ArrayList<>();
            for (LootConditionInfo assumption : scene.assumptions()) {
                if (isInverted(assumption)) continue;
                atoms.add(assumption);
            }
            return List.copyOf(atoms);
        }
        return List.of();
    }

    /** 场景被置假的条件（合成包装的描述，形如「非: X」）；基准场景即该表的全部旋钮。 */
    public static List<Component> negativeAssumptions(SimulationOptions options, String sceneKey) {
        for (var scene : options.scenes()) {
            if (!scene.scenarioKey().equals(sceneKey)) continue;
            List<Component> lines = new ArrayList<>();
            for (LootConditionInfo assumption : scene.assumptions()) {
                if (isInverted(assumption)) lines.add(assumption.description().copy());
            }
            return List.copyOf(lines);
        }
        return List.of();
    }

    /** 逐条列出完整结构；取反和组合节点也保留子树。 */
    private static String describe(LootConditionInfo node) {
        if (node.children().isEmpty()) return displayDescription(node).getString();
        StringJoiner joiner = new StringJoiner("、");
        for (LootConditionInfo child : node.children()) joiner.add(describe(child));
        return displayDescription(node).getString() + "(" + joiner + ")";
    }

    public static Component displayDescription(LootConditionInfo node) {
        Component description = node.description();
        if (description.getContents() instanceof TranslatableContents contents
                && contents.getKey().equals(CONDITION_PREFIX + "location_check_biomes")
                && contents.getArgs().length == 1) {
            Object value = contents.getArgs()[0];
            String id = value instanceof Component component ? component.getString() : String.valueOf(value);
            if (id.equals("#c:is_swamp")) return Component.translatable(contents.getKey(),
                    Component.translatable(CONDITION_PREFIX + "tag.c.is_swamp")).setStyle(description.getStyle());
        }
        return description.copy();
    }

    public static List<LootConditionInfo> negativeConditions(SimulationOptions options, String sceneKey) {
        return options.scenes().stream().filter(scene -> scene.scenarioKey().equals(sceneKey))
                .flatMap(scene -> scene.assumptions().stream()).filter(ScenarioLabel::isInverted).toList();
    }

    private static boolean isInverted(LootConditionInfo info) {
        return info.conditionType().equals(INVERTED);
    }

    private static int ordinal(String sceneKey) {
        try {
            return Integer.parseInt(sceneKey.substring(sceneKey.lastIndexOf('-') + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
