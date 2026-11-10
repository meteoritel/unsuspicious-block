package com.meteorite.unsuspiciousblock.core.loottable.catalog;

import java.util.Arrays;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootFunctionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.injection.DeclaredLootInjection;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.LootConditionFingerprint;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationConstraintCatalog;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.*;

/** 显式当代输入到最终目录 DTO 的投影；声明注入只在此归一。 */
public final class CatalogProjection {
    private static final String DECLARATION_KEY = "injection_declaration";

    private CatalogProjection() {}

    public static TableDefinition normalize(TableDefinition table, List<DeclaredLootInjection> declared) {
        Map<LootResultSignature, ItemDefinition> items = new LinkedHashMap<>();
        for (ItemDefinition item : table.items()) items.put(item.signature(), item);
        for (int index = 0; index < declared.size(); index++) {
            DeclaredLootInjection rule = declared.get(index);
            LootResultSignature signature = LootResultSignature.plain(rule.item());
            List<LootConditionInfo> conditions = new ArrayList<>();
            int conditionIndex = 0;
            for (LootConditionInfo condition : ScenarioBranchCatalog.injectionConditions(rule)) {
                conditions.add(stampDeclaration(condition, index + ":" + conditionIndex++));
            }
            LootAcquisitionPath injected = new LootAcquisitionPath(null, conditions, List.of());
            ItemDefinition item = items.get(signature);
            if (item == null) item = LootTableCatalog.buildDiscoveredDefinition(signature,
                    Probability.unknown(UnknownReason.NOT_SIMULATED), Set.of(LootOriginKind.MOD_INTEGRATION), List.of());
            List<LootAcquisitionPath> paths = new ArrayList<>(item.acquisitionPaths());
            Object key = pathKey(injected);
            if (paths.stream().noneMatch(path -> pathKey(path).equals(key))) paths.add(injected);
            Set<LootOriginKind> origins = new LinkedHashSet<>(item.origins());
            origins.add(LootOriginKind.MOD_INTEGRATION);
            items.put(signature, new ItemDefinition(item.id(), item.displayName(), item.tooltipHint(),
                    item.probability(), item.signature(), paths, origins,
                    item.scenarioProbabilities(), item.observedFunctions()));
        }
        return new TableDefinition(table.id(), table.displayName(), table.type(), List.copyOf(items.values()),
                table.simulationCount(), table.childTables(), table.childTableProbabilities());
    }

    // 不稳定谓词按声明中的出现位置区分，重复投影仍能识别同一声明；不读取谓词对象身份。
    private static LootConditionInfo stampDeclaration(LootConditionInfo condition, String occurrence) {
        if (stable(condition)) return condition;
        Map<String, String> metadata = new LinkedHashMap<>(condition.metadata());
        metadata.put(DECLARATION_KEY, occurrence);
        return new LootConditionInfo(condition.conditionType(), condition.description(), condition.probability(),
                condition.children(), metadata, condition.source());
    }

    public static CatalogTableDto project(TableDefinition table, TableDefinition raw, String hash,
            SimulationConstraintCatalog constraints, List<DeclaredLootInjection> declared,
            Map<String, String> sourceNames, long generation) {
        CatalogTableDto dto = CatalogTableDto.from(normalize(table, declared), hash);
        if (constraints == null) return dto;
        Map<ResourceLocation, CatalogTableDto.ChildTableEntry> existing = new LinkedHashMap<>();
        dto.childProbabilities().forEach(child -> existing.put(child.tableId(), child));
        List<CatalogTableDto.ChildTableEntry> children = new ArrayList<>();
        for (ResourceLocation child : table.childTables()) {
            CatalogTableDto.ChildTableEntry previous = existing.get(child);
            List<LootConditionInfo> conditions = mergeConditions(commonChildConditions(raw, child),
                    constraints.childEntryGates().getOrDefault(child, List.of()));
            children.add(new CatalogTableDto.ChildTableEntry(child,
                    previous == null ? Probability.unknown(UnknownReason.NOT_SIMULATED) : previous.probability(),
                    previous == null ? List.of() : previous.scenarioProbabilities(),
                    previous == null ? conditions : mergeConditions(previous.conditions(), conditions)));
        }
        dto = new CatalogTableDto(dto.id(), dto.hash(), dto.displayName(), dto.type(), dto.simulationCount(),
                dto.childTables(), dto.scenarios(), dto.items(), children, null);
        return dto.withBranches(ScenarioBranchCatalog.build(dto, constraints, declared, sourceNames))
                .withOptions(SimulationOptions.from(generation, constraints));
    }

    public static List<LootConditionInfo> commonChildConditions(TableDefinition parent, ResourceLocation child) {
        Map<Object, LootConditionInfo> common = null;
        for (ItemDefinition item : parent.items()) {
            for (LootAcquisitionPath path : item.acquisitionPaths()) {
                if (!child.equals(path.sourceChildTable())) continue;
                Map<Object, LootConditionInfo> indexed = indexConditions(path.allConditions());
                if (common == null) common = indexed;
                else common.keySet().retainAll(indexed.keySet());
            }
        }
        return common == null ? List.of() : List.copyOf(common.values());
    }

    public static List<LootConditionInfo> mergeConditions(List<LootConditionInfo> first,
                                                          List<LootConditionInfo> second) {
        Map<Object, LootConditionInfo> merged = indexConditions(first);
        second.forEach(condition -> merged.putIfAbsent(conditionKey(condition), condition));
        return List.copyOf(merged.values());
    }

    private static Map<Object, LootConditionInfo> indexConditions(List<LootConditionInfo> conditions) {
        Map<Object, LootConditionInfo> result = new LinkedHashMap<>();
        conditions.forEach(condition -> result.putIfAbsent(conditionKey(condition), condition));
        return result;
    }

    private static boolean stable(LootConditionInfo condition) {
        return "true".equals(condition.metadata().get(LootConditionFingerprint.STABLE_METADATA_KEY))
                && condition.children().stream().allMatch(CatalogProjection::stable);
    }

    private static Object conditionKey(LootConditionInfo condition) {
        String declaration = condition.metadata().get(DECLARATION_KEY);
        if (declaration != null) return List.of(DECLARATION_KEY, declaration);
        if (!stable(condition)) return new Object();
        return List.of(condition.conditionType(), LootConditionFingerprint.of(condition),
                condition.metadata(), condition.children().stream().map(CatalogProjection::conditionKey).toList());
    }

    private static Object functionKey(LootFunctionInfo function) {
        return List.of(function.functionType(), function.description(), function.effect(), function.fidelity(),
                function.conditions().stream().map(CatalogProjection::conditionKey).toList(),
                function.children().stream().map(CatalogProjection::functionKey).toList(), function.metadata());
    }

    private static Object pathKey(LootAcquisitionPath path) {
        return Arrays.asList(path.sourceChildTable(), path.sourceItemTag(),
                path.entryConditions().stream().map(CatalogProjection::conditionKey).toList(),
                path.inheritedConditions().stream().map(CatalogProjection::conditionKey).toList(),
                path.functionUncertainty(), path.luckAffected(), path.luckGate(), path.luckRequirements(),
                path.functions().stream().map(CatalogProjection::functionKey).toList());
    }
}
