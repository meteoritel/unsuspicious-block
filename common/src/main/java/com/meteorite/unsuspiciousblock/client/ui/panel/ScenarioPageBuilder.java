package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.state.ScenarioSimulationClientState;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiIcon;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiNode;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.loottable.simulation.ProbabilityFormat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 场景详情页的内容构建器：把「目录结构 + 该输入的测量结果 + 当前选中」折叠成一段块序列。
 * 只读客户端已有数据，不做业务判断，也不触发任何请求；重建由内容版本号门控。
 *
 * <p>树只列**正条件**：场景相对基准的全部差异就是被置真的那批指纹，其余指纹为假即是基准本身，
 * 因此列负条件只会让每个场景页都多出大半屏重复内容。
 */
final class ScenarioPageBuilder {
    private static final int INDENT_STEP = 12;
    /** 服务端在条件 metadata 里下发的指纹键，用于把「物品的获取路径」与「场景的条件」对上。 */
    private static final String FINGERPRINT_KEY = "simulation_fingerprint";
    private static final ResourceLocation INVERTED = ResourceLocation.withDefaultNamespace("inverted");
    private static final String CONDITION_PREFIX = "screen.unsuspiciousblock.archaeology_journal.condition.";

    private ScenarioPageBuilder() {}

    static List<UiNode> buildTree(CatalogTableDto structure, String sceneKey,
                                  com.meteorite.unsuspiciousblock.client.ui.support.ScenarioPresentation presentation) {
        var options = structure.options();
        if (options == null) return List.of();
        List<LootConditionInfo> atoms = positiveAssumptions(options, sceneKey);
        Map<String, List<CatalogTableDto.ItemEntry>> itemsByAtom = itemsByAtom(structure.items());
        Map<com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature, CatalogTableDto.ItemEntry> measured = new HashMap<>();
        if (presentation.source() != null) {
            for (var item : presentation.source().items()) measured.put(item.signature(), item);
        }
        List<UiNode> tree = new ArrayList<>();
        if (atoms.isEmpty()) {
            tree.add(new UiNode.Row(0, UiNode.NO_PARENT, ScenarioSimulationClientState.text("no_assumptions"),
                    UiTextPalette.Parchment.LABEL, itemIcons(structure.items(), measured, sceneKey, presentation.exactInput()),
                    List.of(ScenarioSimulationClientState.text("no_assumptions")), null, null));
        }
        for (LootConditionInfo atom : atoms) {
            addCondition(tree, atom, itemsByAtom, measured, sceneKey, presentation.exactInput(), 0, UiNode.NO_PARENT);
        }
        return List.copyOf(tree);
    }

    static Component title(com.meteorite.unsuspiciousblock.loottable.catalog.SimulationOptions options, String sceneKey) {
        String number = sceneKey.equals("baseline") ? "" : "#" + sceneKey.substring(sceneKey.lastIndexOf('-') + 1) + " ";
        return Component.literal(number).append(formula(positiveAssumptions(options, sceneKey)));
    }

    static List<Component> titleTooltip(com.meteorite.unsuspiciousblock.loottable.catalog.SimulationOptions options, String sceneKey) {
        return fullFormula(positiveAssumptions(options, sceneKey));
    }

    private static List<Component> fullFormula(List<LootConditionInfo> atoms) {
        if (atoms.isEmpty()) return List.of(ScenarioSimulationClientState.text("no_assumptions"));
        StringJoiner joiner = new StringJoiner(" ∧ ");
        for (LootConditionInfo atom : atoms) joiner.add(atom.description().getString());
        return List.of(Component.literal(joiner.toString()));
    }

    // 场景的假设列表里，被置真的指纹放原条件；被置假的是合成的 inverted 包装，这里只要前者。
    private static List<LootConditionInfo> positiveAssumptions(
            com.meteorite.unsuspiciousblock.loottable.catalog.SimulationOptions options, String sceneKey) {
        for (var scene : options.scenes()) {
            if (!scene.scenarioKey().equals(sceneKey)) continue;
            List<LootConditionInfo> atoms = new ArrayList<>();
            for (LootConditionInfo assumption : scene.assumptions()) {
                if (isInverted(assumption)) continue;
                atoms.add(assumption);
            }
            return atoms;
        }
        return List.of();
    }

    // 每层条件都可挂图标；未计算的输入只显示未计算，不回退到目录的基准概率。
    private static void addCondition(List<UiNode> nodes, LootConditionInfo condition,
                                      Map<String, List<CatalogTableDto.ItemEntry>> itemsByAtom,
                                      Map<com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature, CatalogTableDto.ItemEntry> measured,
                                      String sceneKey, boolean exact, int indent, int parent) {
        int self = nodes.size();
        List<UiNode.InlineIcon> icons = itemIcons(itemsByAtom.getOrDefault(fingerprint(condition), List.of()),
                measured, sceneKey, exact);
        nodes.add(new UiNode.Row(indent, parent, condition.description().copy(), UiTextPalette.Parchment.BODY,
                icons, List.of(condition.description().copy()), condition, null));
        for (LootConditionInfo child : condition.children()) {
            addCondition(nodes, child, itemsByAtom, measured, sceneKey, exact, indent + INDENT_STEP, self);
        }
    }

    private static List<UiNode.InlineIcon> itemIcons(List<CatalogTableDto.ItemEntry> items,
            Map<com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature, CatalogTableDto.ItemEntry> measured,
            String sceneKey, boolean exact) {
        List<UiNode.InlineIcon> icons = new ArrayList<>();
        for (var item : items) {
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(item.id()));
            if (stack.isEmpty()) continue;
            CatalogTableDto.ItemEntry result = measured.get(item.signature());
            Probability probability = result == null ? null : sceneProbability(result, sceneKey);
            if (probability == null && exact && result != null) probability = result.probability();
            Component probabilityText = probability == null ? ScenarioSimulationClientState.text("uncomputed")
                    : ProbabilityFormat.formatComponent(probability);
            icons.add(new UiNode.InlineIcon(new UiIcon.Item(stack),
                    List.of(item.displayName().copy(), probabilityText), item.signature(), null));
        }
        return icons;
    }

    @Nullable
    private static Probability sceneProbability(CatalogTableDto.ItemEntry item, String sceneKey) {
        for (var ref : item.scenarioProbabilities()) {
            if (ref.scenarioKey().equals(sceneKey)) return ref.probability();
        }
        return null;
    }

    // 物品 → 它引用的全部条件指纹；条件树递归收集，取反包装自身没有指纹、由内层提供。
    private static Map<String, List<CatalogTableDto.ItemEntry>> itemsByAtom(List<CatalogTableDto.ItemEntry> items) {
        Map<String, List<CatalogTableDto.ItemEntry>> index = new HashMap<>();
        for (CatalogTableDto.ItemEntry item : items) {
            Set<String> keys = new LinkedHashSet<>();
            for (var path : item.acquisitionPaths()) {
                collectFingerprints(path.entryConditions(), keys);
                collectFingerprints(path.inheritedConditions(), keys);
            }
            for (String key : keys) index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
        }
        return index;
    }

    private static void collectFingerprints(List<LootConditionInfo> conditions, Set<String> out) {
        for (LootConditionInfo condition : conditions) {
            String fingerprint = fingerprint(condition);
            if (fingerprint != null) out.add(fingerprint);
            collectFingerprints(condition.children(), out);
        }
    }

    @Nullable
    private static String fingerprint(LootConditionInfo info) {
        return info.metadata().get(FINGERPRINT_KEY);
    }

    private static boolean isInverted(LootConditionInfo info) {
        return info.conditionType().equals(INVERTED);
    }

    /**
     * 旋钮公式：把一棵条件（含子行）压成单字母组合。叶子条件的可识别键映射成助记字母，
     * **只要有一个叶子认不出来，整条公式就退回按位置分配的字母**——宁可失去助记，也不能让
     * 公式出现“有的字母有含义、有的没有”的歧义。全称恒在 tooltip。
     */
    private static Component formula(List<LootConditionInfo> atoms) {
        if (atoms.isEmpty()) return ScenarioSimulationClientState.text("baseline");
        List<String> letters = new ArrayList<>();
        boolean mnemonic = true;
        for (LootConditionInfo atom : atoms) {
            if (!collectLeafLetters(atom, letters)) {
                mnemonic = false;
                break;
            }
        }
        List<String> parts = mnemonic ? letters : positionalLetters(atoms.size());
        LinkedHashSet<String> distinct = new LinkedHashSet<>(parts);
        return Component.literal(String.join(" ∧ ", distinct));
    }

    private static List<String> positionalLetters(int count) {
        List<String> letters = new ArrayList<>(count);
        for (int i = 0; i < count; i++) letters.add(String.valueOf((char) ('A' + i % 26)));
        return letters;
    }

    private static boolean collectLeafLetters(LootConditionInfo info, List<String> out) {
        if (isInverted(info) && info.children().size() == 1) {
            List<String> inner = new ArrayList<>(1);
            if (collectLeafLetters(info.children().getFirst(), inner)) {
                inner.forEach(letter -> out.add("¬" + letter));
                return true;
            }
            return false;
        }
        if (info.children().isEmpty()) {
            String letter = leafLetter(info);
            if (letter == null) return false;
            out.add(letter);
            return true;
        }
        for (LootConditionInfo child : info.children()) {
            if (collectLeafLetters(child, out)) continue;
            return false;
        }
        return true;
    }

    // 取值一律由服务端下发的本地化描述承载；这里只取可识别的键后缀作助记，认不出就返回 null。
    @Nullable
    private static String leafLetter(LootConditionInfo info) {
        if (!(info.description().getContents() instanceof TranslatableContents contents)) return null;
        String key = contents.getKey();
        if (!key.startsWith(CONDITION_PREFIX)) return null;
        String suffix = key.substring(CONDITION_PREFIX.length());
        if (suffix.startsWith("location_check_biomes")) return "B";
        if (suffix.startsWith("location_check_dimension")) return "D";
        if (suffix.startsWith("location_check_structures")) return "S";
        if (suffix.startsWith("weather_check")) return "W";
        if (suffix.startsWith("time_check")) return "C";
        if (suffix.startsWith("entity_properties_fishing")) return "O";
        if (suffix.startsWith("random_chance")) return "R";
        if (suffix.startsWith("match_tool")) return "T";
        if (suffix.startsWith("block_state_propert")) return "K";
        if (suffix.startsWith("entity_scores")) return "E";
        if (suffix.startsWith("damage_source")) return "G";
        if (suffix.startsWith("entity_properties")) return "M";
        return null;
    }
}
