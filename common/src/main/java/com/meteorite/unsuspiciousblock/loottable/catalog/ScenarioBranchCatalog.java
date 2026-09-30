package com.meteorite.unsuspiciousblock.loottable.catalog;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.simulation.LootConditionFingerprint;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.loottable.analysis.LuckGate;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationConstraintCatalog;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationScenarioPlanner;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationScenario;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 从权威路径生成分支；未知谓词保持假设候选，只有明确为假的主条件才隐藏目标。 */
public final class ScenarioBranchCatalog {
    private ScenarioBranchCatalog() {}

    public static List<ScenarioBranch> build(CatalogTableDto table, SimulationConstraintCatalog constraints) {
        Set<ScenarioBranch> branches = new LinkedHashSet<>();
        for (var item : table.items()) {
            if (item.injected()) continue;
            for (var path : item.acquisitionPaths()) {
                if (path.luckImpossible()) continue;
                String kind = path.sourceChildTable() != null ? "table" : path.sourceItemTag() != null ? "tag" : "item";
                String target = path.sourceChildTable() != null ? path.sourceChildTable().toString()
                        : path.sourceItemTag() != null ? path.sourceItemTag().toString() : item.signature().toStoredKey();
                List<LootConditionInfo> conditions = new ArrayList<>();
                List<LootConditionInfo> extras = new ArrayList<>();
                split(path.allConditions(), conditions, extras);
                if (path.sourceChildTable() != null)
                    split(constraints.childEntryGates().getOrDefault(path.sourceChildTable(), List.of()), conditions, extras);
                List<String> active = constraints.scenarios().stream().filter(scene -> conditions.stream()
                                .noneMatch(condition -> Boolean.FALSE.equals(evaluate(condition, scene.profile().conditionOutcomes()))))
                        .map(SimulationScenario::key).toList();
                branches.add(new ScenarioBranch(kind, target, conditions, extras,
                        path.luckGate() == null ? LuckGate.NONE : path.luckGate(), active,
                        conditions.stream().anyMatch(ScenarioBranchCatalog::uncertain)
                                || path.functionUncertainty() == com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandler.UncertaintyLevel.RUNTIME));
            }
        }
        for (var child : table.childProbabilities()) {
            if (branches.stream().anyMatch(branch -> branch.kind().equals("table")
                    && branch.target().equals(child.tableId().toString()))) continue;
            List<LootConditionInfo> conditions = new ArrayList<>();
            List<LootConditionInfo> extras = new ArrayList<>();
            split(child.conditions(), conditions, extras);
            branches.add(new ScenarioBranch("table", child.tableId().toString(), conditions, extras, LuckGate.NONE,
                    constraints.scenarios().stream().filter(scene -> conditions.stream().noneMatch(condition ->
                            Boolean.FALSE.equals(evaluate(condition, scene.profile().conditionOutcomes()))))
                            .map(SimulationScenario::key).toList(), conditions.stream().anyMatch(ScenarioBranchCatalog::uncertain)));
        }
        var declared = com.meteorite.unsuspiciousblock.platform.Services.PLATFORM.describeLootInjections(table.id());
        for (var rule : declared) {
            var conditions = new ArrayList<LootConditionInfo>();
            var extras = new ArrayList<LootConditionInfo>();
            split(injectionConditions(rule), conditions, extras);
            var signature = com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature.plain(rule.item());
            branches.add(new ScenarioBranch("item", signature.toStoredKey(), conditions, extras, LuckGate.NONE,
                    constraints.scenarios().stream().filter(scene -> conditions.stream().noneMatch(condition ->
                            Boolean.FALSE.equals(evaluate(condition, scene.profile().conditionOutcomes()))))
                            .map(SimulationScenario::key).toList(), conditions.stream().anyMatch(ScenarioBranchCatalog::uncertain),
                    com.meteorite.unsuspiciousblock.platform.Services.PLATFORM.getModDisplayName(rule.source()), rule.mode()));
        }
        for (var item : table.items()) {
            if (!item.injected() || declared.stream().anyMatch(rule -> rule.item().equals(item.id()))) continue;
            branches.add(new ScenarioBranch("item", item.signature().toStoredKey(), List.of(), List.of(), LuckGate.NONE,
                    constraints.scenarios().stream().map(SimulationScenario::key).toList(), true, "observed", ""));
        }
        String mudTable = com.meteorite.unsuspiciousblock.loottable.graph.RuntimeLootLinks.MUD_DREDGING_TABLE_KEY.location().toString();
        if (table.id().equals(net.minecraft.resources.ResourceLocation.withDefaultNamespace("gameplay/fishing"))) {
            var injected = branches.stream().filter(branch -> branch.kind().equals("table") && branch.target().equals(mudTable)).toList();
            branches.removeIf(branch -> branch.kind().equals("table") && branch.target().equals(mudTable));
            for (var branch : injected) branches.add(new ScenarioBranch(branch.kind(), branch.target(), branch.conditions(),
                    branch.requirements(), branch.luck(), branch.activeScenes(), branch.uncertain(),
                    com.meteorite.unsuspiciousblock.platform.Services.PLATFORM.getModDisplayName(com.meteorite.unsuspiciousblock.Constants.MOD_ID), "append"));
        }
        return List.copyOf(branches);
    }

    public static List<LootConditionInfo> injectionConditions(
            com.meteorite.unsuspiciousblock.loottable.injection.DeclaredLootInjection rule) {
        var result = new ArrayList<>(rule.conditions());
        if (rule.chance() < 1.0F) result.addAll(LootConditionHandlers.analyzeAll(List.of(
                net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition.randomChance(rule.chance()).build())));
        return List.copyOf(result);
    }

    private static void split(List<LootConditionInfo> source, List<LootConditionInfo> main, List<LootConditionInfo> extras) {
        for (var condition : source) {
            if (condition.conditionType().toString().equals("minecraft:all_of")) split(condition.children(), main, extras);
            else if (Set.of("minecraft:match_tool", "minecraft:table_bonus", "minecraft:random_chance",
                    "minecraft:random_chance_with_enchanted_bonus", "minecraft:enchantment_active").contains(condition.conditionType().toString())) {
                if (!extras.contains(condition)) extras.add(condition);
            } else if (!main.contains(condition)) main.add(condition);
        }
    }

    private static boolean uncertain(LootConditionInfo condition) {
        return condition.metadata().containsKey(LootConditionHandlers.FIDELITY_METADATA_KEY)
                || !condition.conditionType().getNamespace().equals("minecraft")
                || condition.children().stream().anyMatch(ScenarioBranchCatalog::uncertain);
    }

    private static Boolean evaluate(LootConditionInfo condition, Map<String, Boolean> outcomes) {
        String type = condition.conditionType().toString();
        if (type.equals("minecraft:inverted")) {
            if (condition.children().size() != 1) return null;
            Boolean child = evaluate(condition.children().getFirst(), outcomes);
            return child == null ? null : !child;
        }
        if (type.equals("minecraft:all_of") || type.equals("minecraft:any_of")) {
            boolean conjunction = type.equals("minecraft:all_of");
            boolean unknown = false;
            for (var child : condition.children()) {
                Boolean value = evaluate(child, outcomes);
                if (value == null) unknown = true;
                else if (value != conjunction) return !conjunction;
            }
            return unknown ? null : conjunction;
        }
        return SimulationScenarioPlanner.isScenarioControlled(condition.conditionType())
                ? outcomes.get(LootConditionFingerprint.of(condition)) : null;
    }
}
