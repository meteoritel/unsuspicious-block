package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.SimulationOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 场景的展示口径：可读名、条件行文本与完整定义。
 *
 * <p>场景的假设是一张「指纹 → 布尔」的完整赋值表：为真的放原条件，为假的放合成的
 * {@code minecraft:inverted} 包装。因此**相对基准的差异就是被置真的那一批**，展示只取它们，
 * 否则每个场景页都会多出整屏恒为「不成立」的重复行（基准页本身除外，见
 * {@link #definition}）。</p>
 *
 * <p>「纯分组父行」的判定基于本地化键后缀，是本类唯一的隐式契约（「服务端恰好这么拼 Component」）：
 * 认不出时一律不折叠，宁可多一行也不把取值折掉。</p>
 */
public final class ScenarioLabel {
    private static final String CONDITION_PREFIX = "screen.unsuspiciousblock.archaeology_journal.condition.";
    private static final ResourceLocation INVERTED = ResourceLocation.withDefaultNamespace("inverted");
    private static final String BASELINE = "baseline";
    /**
     * 父行只描述「哪一类条件」、取值全在子行里的条件键后缀。
     * 这类父行在只有一个子行时折叠掉，取值才与条件同处一行。
     * <b>不含</b> {@code block_state_property}（父行「方块：X」自带取值）与 {@code time_check}
     * 的区间行，也不含 {@code inverted}（父行「非: X」自带语义）。
     */
    private static final Set<String> GROUPING_PARENTS = Set.of(
            "location_check", "weather_check", "damage_source_properties", "all_of", "any_of");

    private ScenarioLabel() {
    }

    public static boolean isBaseline(String sceneKey) { return BASELINE.equals(sceneKey); }

    /** 场景显示名：基准、或「场景 N · 叶子条件」；没有叶子条件时只留序号。 */
    public static Component label(SimulationOptions options, String sceneKey) {
        if (isBaseline(sceneKey)) return ScenarioSimulationClientState.text("baseline");
        Component name = ScenarioSimulationClientState.text("scene", ordinal(sceneKey));
        List<String> leaves = leafTexts(positiveAssumptions(options, sceneKey));
        if (leaves.isEmpty()) return name;
        return name.copy().append(Component.literal(" · " + String.join(" + ", leaves)));
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

    /** 场景相对基准**成立**的条件；为假的合成包装在此剔除，纯分组父行折叠为它的子行。 */
    public static List<LootConditionInfo> positiveAssumptions(SimulationOptions options, String sceneKey) {
        for (var scene : options.scenes()) {
            if (!scene.scenarioKey().equals(sceneKey)) continue;
            List<LootConditionInfo> atoms = new ArrayList<>();
            for (LootConditionInfo assumption : scene.assumptions()) {
                if (isInverted(assumption)) continue;
                atoms.add(fold(assumption));
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

    /**
     * 折叠纯分组父行：父行只描述条件类别、且**恰好一个**子行时，用子行替换父行。
     * 多子行保留分组（那是真正的合取），未知键后缀一律不折叠。
     */
    public static LootConditionInfo fold(LootConditionInfo node) {
        if (node.children().size() != 1) return node;
        String suffix = keySuffix(node.description());
        if (suffix == null || !GROUPING_PARENTS.contains(suffix)) return node;
        return fold(node.children().getFirst());
    }

    /** 逐条列出条件下的全文：分组父行后接括号内的子行，取反行自带内层描述、不再展开子行。 */
    private static String describe(LootConditionInfo node) {
        if (node.children().isEmpty() || isInverted(node)) return node.description().getString();
        StringJoiner joiner = new StringJoiner("、");
        for (LootConditionInfo child : node.children()) joiner.add(describe(child));
        return node.description().getString() + "(" + joiner + ")";
    }

    // 叶子条件的本地化全文；取反叶子保留「非:」前缀，避免把「不成立」读成「成立」。
    private static List<String> leafTexts(List<LootConditionInfo> atoms) {
        List<String> out = new ArrayList<>();
        for (LootConditionInfo atom : atoms) collectLeafText(atom, out);
        return out;
    }

    private static void collectLeafText(LootConditionInfo node, List<String> out) {
        if (isInverted(node) && node.children().size() == 1) {
            List<String> inner = new ArrayList<>(1);
            collectLeafText(node.children().getFirst(), inner);
            for (String text : inner) {
                out.add(Component.translatable(CONDITION_PREFIX + "inverted", text).getString());
            }
            return;
        }
        if (!node.children().isEmpty()) {
            for (LootConditionInfo child : node.children()) collectLeafText(child, out);
            return;
        }
        out.add(node.description().getString());
    }

    @Nullable
    private static String keySuffix(Component description) {
        if (!(description.getContents() instanceof TranslatableContents contents)) return null;
        String key = contents.getKey();
        if (!key.startsWith(CONDITION_PREFIX)) return null;
        return key.substring(CONDITION_PREFIX.length());
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
