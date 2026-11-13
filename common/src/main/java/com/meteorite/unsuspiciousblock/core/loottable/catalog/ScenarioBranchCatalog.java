package com.meteorite.unsuspiciousblock.core.loottable.catalog;

import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionTree;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionTypeDescriptor;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionHandler;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LuckGate;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.core.loottable.injection.DeclaredInjectionExpansion.ExpandedTable;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 展示分支与完整候选在同一派生过程中绑定，引用只在所属内容版本内有效。
 */
public final class ScenarioBranchCatalog {
    private ScenarioBranchCatalog() {
    }

    /**
     * 完整路径见证；物品签名与子表入口分别保留身份。
     */
    public record Candidate(String itemSignature, String kind, String target, String pathId, LootAcquisitionPath path,
                            String branchReference) {
    }

    /**
     * 展示去重只合并候选集合，不能扩大到目标的其他路径。
     */
    public record Plan(List<ScenarioBranch> branches, Map<String, List<Candidate>> candidates) {
        public Plan {
            branches = List.copyOf(branches);
            candidates = Collections.unmodifiableMap(new LinkedHashMap<>(candidates));
        }
    }

    /**
     * 派生与路径签发同源的只读展示分支。
     */
    public static List<ScenarioBranch> build(TableDefinition table, ExpandedTable expansion, Map<String, String> names) {
        return plan(table, expansion, names).branches();
    }

    /**
     * 在同代内容中绑定展示分支、原始来源与完整候选路径集合。
     */
    public static Plan plan(TableDefinition table, ExpandedTable expansion, Map<String, String> names) {
        Map<ScenarioBranch, List<Candidate>> grouped = new LinkedHashMap<>();
        TableDefinition nativeTable = expansion == null ? table : expansion.nativeTable();
        int pathId = 1;
        for (var item : nativeTable.items())
            for (var path : item.acquisitionPaths()) {
                if (!path.luckImpossible())
                    addPath(grouped, item.signature().toStoredKey(), path, "", "", "", pathId++);
            }
        if (expansion != null) for (var contribution : expansion.contributions()) {
            for (var item : contribution.items())
                for (var path : item.acquisitionPaths()) {
                    if (!path.luckImpossible()) addPath(grouped, item.signature().toStoredKey(), path,
                            names.getOrDefault(contribution.source(), contribution.source()), contribution.mode(), contribution.source(), pathId++);
                }
            if (contribution.childTable() != null && contribution.items().isEmpty()) addPath(grouped, "",
                    entryPath(contribution.childTable(), contribution.entryConditions()),
                    names.getOrDefault(contribution.source(), contribution.source()), contribution.mode(), contribution.source(), pathId++);
        }
        for (var child : (expansion == null ? table : expansion.planning()).childTableProbabilities()) {
            if (grouped.keySet().stream().anyMatch(branch -> branch.kind().equals("table")
                    && branch.target().equals(child.tableId().toString()))) continue;
            addPath(grouped, "", entryPath(child.tableId(), child.conditions()), "", "", "", pathId++);
        }
        for (var item : table.items()) {
            if (item.origins().contains(LootOriginKind.UNKNOWN_RUNTIME)) grouped.putIfAbsent(new ScenarioBranch(
                    "item", item.signature().toStoredKey(), List.of(), List.of(), LuckGate.NONE, true, "observed", ""), new ArrayList<>());
        }
        List<ScenarioBranch> branches = new ArrayList<>();
        Map<String, List<Candidate>> candidates = new LinkedHashMap<>();
        int ordinal = 1;
        for (var entry : grouped.entrySet()) {
            String reference = Integer.toString(ordinal++);
            boolean supported = !entry.getValue().isEmpty() && !entry.getKey().kind().equals("tag");
            branches.add(entry.getKey().withReference(reference, supported));
            if (!entry.getValue().isEmpty()) candidates.put(reference, entry.getValue().stream().map(candidate ->
                    new Candidate(candidate.itemSignature(), candidate.kind(), candidate.target(), candidate.pathId(),
                            candidate.path(), reference)).toList());
        }
        return new Plan(branches, candidates);
    }

    private static void addPath(Map<ScenarioBranch, List<Candidate>> grouped, String signature,
                                LootAcquisitionPath path, String source, String mode, String sourceKey, int pathId) {
        String kind = path.sourceChildTable() != null ? "table" : path.sourceItemTag() != null ? "tag" : "item";
        String target = path.sourceChildTable() != null ? path.sourceChildTable().toString()
                : path.sourceItemTag() != null ? path.sourceItemTag().toString() : signature;
        List<LootConditionInfo> conditions = new ArrayList<>();
        List<LootConditionInfo> extras = new ArrayList<>();
        split(path.allConditions(), conditions, extras);
        var branch = new ScenarioBranch(kind, target, conditions, extras,
                path.luckGate() == null ? LuckGate.NONE : path.luckGate(),
                conditions.stream().anyMatch(ScenarioBranchCatalog::uncertain)
                        || path.functionUncertainty() == LootConditionHandler.UncertaintyLevel.RUNTIME,
                source, mode, "", false, sourceKey);
        grouped.computeIfAbsent(branch, ignored -> new ArrayList<>()).add(new Candidate(signature,
                path.sourceChildTable() == null ? "item" : "table",
                path.sourceChildTable() == null ? signature : path.sourceChildTable().toString(), Integer.toString(pathId), path, ""));
    }

    private static LootAcquisitionPath entryPath(ResourceLocation child, List<LootConditionInfo> conditions) {
        return new LootAcquisitionPath(child, null, List.of(), conditions,
                LootConditionHandler.UncertaintyLevel.NONE, false, LuckGate.NONE, List.of());
    }

    private static void split(List<LootConditionInfo> source, List<LootConditionInfo> main,
                              List<LootConditionInfo> extras) {
        for (var condition : source) {
            if (ConditionTree.kind(condition) == ConditionTree.Kind.ALL) {
                split(ConditionTree.logicChildren(condition), main, extras);
            } else if (LootConditionHandlers.supports(condition.conditionType(),
                    ConditionTypeDescriptor.Capability.SECONDARY)) {
                if (!extras.contains(condition)) {
                    extras.add(condition);
                }
            } else if (!main.contains(condition)) {
                main.add(condition);
            }
        }
    }

    private static boolean uncertain(LootConditionInfo condition) {
        return condition.metadata().containsKey(LootConditionHandlers.FIDELITY_METADATA_KEY)
                || LootConditionHandlers.descriptor(condition.conditionType()) == null
                || ConditionTree.logicChildren(condition).stream().anyMatch(ScenarioBranchCatalog::uncertain);
    }
}
