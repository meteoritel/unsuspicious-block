package com.meteorite.unsuspiciousblock.core.loottable.catalog;

import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionHandler;
import com.meteorite.unsuspiciousblock.core.loottable.injection.DeclaredInjectionExpansion.ExpandedTable;
import com.meteorite.unsuspiciousblock.core.loottable.injection.DeclaredInjectionExpansion.ExpandedContribution;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionTree;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionTypeDescriptor;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LuckGate;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationConstraintCatalog;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationScenario;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 从权威路径生成分支；未知谓词保持假设候选，只有明确为假的主条件才隐藏目标。 */
public final class ScenarioBranchCatalog {
    private ScenarioBranchCatalog() {}

    public static List<ScenarioBranch> build(TableDefinition table, SimulationConstraintCatalog constraints,
            ExpandedTable expansion, Map<String, String> sourceNames) {
        Set<ScenarioBranch> branches = new LinkedHashSet<>();
        List<ExpandedContribution> contributions = expansion == null ? List.of() : expansion.contributions();
        TableDefinition nativeTable = expansion == null ? table : expansion.nativeTable();
        for (var item : nativeTable.items()) {
            for (var path : item.acquisitionPaths()) {
                if (path.luckImpossible()) continue;
                addPath(branches, item.signature().toStoredKey(), path, constraints, "", "");
            }
        }
        for (var contribution : contributions) {
            String source = sourceNames.getOrDefault(contribution.source(), contribution.source());
            for (var item : contribution.items()) for (var path : item.acquisitionPaths()) {
                if (!path.luckImpossible())
                    addPath(branches, item.signature().toStoredKey(), path, constraints, source, contribution.mode());
            }
            // 空子表仍然有入口门槛和来源。
            if (contribution.childTable() != null && contribution.items().isEmpty())
                addPath(branches, "", new LootAcquisitionPath(contribution.childTable(), List.of(),
                        contribution.entryConditions()), constraints, source, contribution.mode());
        }
        for (var child : table.childTableProbabilities()) {
            if (branches.stream().anyMatch(branch -> branch.kind().equals("table")
                    && branch.target().equals(child.tableId().toString()))) continue;
            List<LootConditionInfo> conditions = new ArrayList<>();
            List<LootConditionInfo> extras = new ArrayList<>();
            split(child.conditions(), conditions, extras);
            branches.add(new ScenarioBranch("table", child.tableId().toString(), conditions, extras, LuckGate.NONE,
                    constraints.activeScenes(child.conditions()), conditions.stream().anyMatch(ScenarioBranchCatalog::uncertain)));
        }
        for (var item : table.items()) {
            if (!item.origins().contains(LootOriginKind.UNKNOWN_RUNTIME)) continue;
            branches.add(new ScenarioBranch("item", item.signature().toStoredKey(), List.of(), List.of(), LuckGate.NONE,
                    constraints.scenarios().stream().map(SimulationScenario::key).toList(), true, "observed", ""));
        }
        return List.copyOf(branches);
    }

    private static void addPath(Set<ScenarioBranch> branches, String signature, LootAcquisitionPath path,
            SimulationConstraintCatalog constraints, String source, String mode) {
        String kind = path.sourceChildTable() != null ? "table" : path.sourceItemTag() != null ? "tag" : "item";
        String target = path.sourceChildTable() != null ? path.sourceChildTable().toString()
                : path.sourceItemTag() != null ? path.sourceItemTag().toString() : signature;
        List<LootConditionInfo> conditions = new ArrayList<>();
        List<LootConditionInfo> extras = new ArrayList<>();
        split(path.allConditions(), conditions, extras);
        branches.add(new ScenarioBranch(kind, target, conditions, extras,
                path.luckGate() == null ? LuckGate.NONE : path.luckGate(),
                constraints.activeScenes(path.allConditions()),
                conditions.stream().anyMatch(ScenarioBranchCatalog::uncertain)
                        || path.functionUncertainty() == LootConditionHandler.UncertaintyLevel.RUNTIME,
                source, mode));
    }

    private static void split(List<LootConditionInfo> source, List<LootConditionInfo> main, List<LootConditionInfo> extras) {
        for (var condition : source) {
            if (ConditionTree.kind(condition) == ConditionTree.Kind.ALL) split(ConditionTree.logicChildren(condition), main, extras);
            else if (LootConditionHandlers.supports(condition.conditionType(), ConditionTypeDescriptor.Capability.SECONDARY)) {
                if (!extras.contains(condition)) extras.add(condition);
            } else if (!main.contains(condition)) main.add(condition);
        }
    }

    private static boolean uncertain(LootConditionInfo condition) {
        return condition.metadata().containsKey(LootConditionHandlers.FIDELITY_METADATA_KEY)
                || LootConditionHandlers.descriptor(condition.conditionType()) == null
                || ConditionTree.logicChildren(condition).stream().anyMatch(ScenarioBranchCatalog::uncertain);
    }

}
