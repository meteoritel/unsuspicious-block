package com.meteorite.unsuspiciousblock.core.loottable.injection;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootFunctionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogProjection;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootOriginKind;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ChildTableProbability;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.Probability;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.UnknownReason;
import com.meteorite.unsuspiciousblock.core.loottable.graph.RuntimeLootLinks.InjectionEdge;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationConstraintCatalog;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 构建时展开声明注入；静态展示与约束规划共用同一份路径和条件出处。 */
public final class DeclaredInjectionExpansion {
    private DeclaredInjectionExpansion() {}

    /** 同一声明在父表中的贡献；childTable 保存第一跳，items 保留完整路径。 */
    public record ExpandedContribution(ResourceLocation parent, String source, String mode,
            @Nullable ResourceLocation childTable, List<ItemDefinition> items,
            List<LootConditionInfo> entryConditions) {
        public ExpandedContribution {
            items = List.copyOf(items);
            entryConditions = List.copyOf(entryConditions);
        }
    }

    /** 仅由展开入口构造的当代静态与规划读模型。 */
    public static final class ExpandedTable {
        private final TableDefinition nativeTable;
        private final TableDefinition display;
        private final TableDefinition planning;
        private final List<ExpandedContribution> contributions;
        private final Map<ResourceLocation, List<LootConditionInfo>> childEntryGates;

        private ExpandedTable(TableDefinition nativeTable, TableDefinition display, TableDefinition planning,
                List<ExpandedContribution> contributions,
                Map<ResourceLocation, List<LootConditionInfo>> childEntryGates) {
            this.nativeTable = nativeTable;
            this.display = display;
            this.planning = planning;
            this.contributions = List.copyOf(contributions);
            this.childEntryGates = Map.copyOf(childEntryGates);
        }

        public TableDefinition nativeTable() { return nativeTable; }
        public TableDefinition display() { return display; }
        public TableDefinition planning() { return planning; }
        public List<ExpandedContribution> contributions() { return contributions; }
        public Map<ResourceLocation, List<LootConditionInfo>> childEntryGates() { return childEntryGates; }
    }

    /** 原始声明只在这里转换一次；opaque 条件在后续复制中保持原有出处身份。 */
    public static Map<ResourceLocation, ExpandedTable> build(Map<ResourceLocation, TableDefinition> tables,
            Map<ResourceLocation, List<DeclaredLootInjection>> declarations, List<InjectionEdge> edges,
            HolderLookup.Provider registries) {
        DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        Map<ResourceLocation, List<ExpandedContribution>> direct = new LinkedHashMap<>();
        declarations.forEach((id, rules) -> {
            List<ExpandedContribution> contributions = new ArrayList<>();
            for (DeclaredLootInjection rule : rules) {
                List<LootConditionInfo> conditions = new ArrayList<>(rule.conditions());
                if (rule.chance() < 1.0F) conditions.addAll(LootConditionHandlers.analyzeAll(List.of(
                        LootItemRandomChanceCondition.randomChance(rule.chance()).build()), ops));
                ItemDefinition item = LootTableCatalog.buildDiscoveredDefinition(LootResultSignature.plain(rule.item()),
                        Probability.unknown(UnknownReason.NOT_SIMULATED), Set.of(LootOriginKind.MOD_INTEGRATION), List.of());
                item = withPaths(item, List.of(new LootAcquisitionPath(null, conditions, List.of())));
                contributions.add(new ExpandedContribution(id, rule.source(), rule.mode(), null,
                        List.of(item), conditions));
            }
            direct.put(id, List.copyOf(contributions));
        });
        Map<InjectionEdge, List<LootConditionInfo>> gates = new LinkedHashMap<>();
        for (InjectionEdge edge : edges) {
            if (tables.containsKey(edge.source()) && tables.containsKey(edge.target()))
                gates.put(edge, SimulationConstraintCatalog.describeGate(edge.gate(), registries));
        }
        Map<ResourceLocation, ExpandedTable> result = new LinkedHashMap<>();
        tables.forEach((id, raw) -> {
            List<ExpandedContribution> contributions = new ArrayList<>(direct.getOrDefault(id, List.of()));
            expandChildren(id, id, null, List.of(), tables, direct, gates, new LinkedHashSet<>(), contributions);
            Map<ResourceLocation, List<LootConditionInfo>> childGates = new LinkedHashMap<>();
            gates.forEach((edge, conditions) -> {
                if (edge.source().equals(id)) childGates.put(edge.target(), conditions);
            });
            TableDefinition display = merge(raw, direct.getOrDefault(id, List.of()));
            TableDefinition planning = merge(raw, contributions);
            List<ChildTableProbability> children = raw.childTables().stream().map(child ->
                    new ChildTableProbability(child, Probability.unknown(UnknownReason.NOT_SIMULATED), List.of(),
                            CatalogProjection.mergeConditions(CatalogProjection.commonChildConditions(planning, child),
                                    childGates.getOrDefault(child, List.of())))).toList();
            display = new TableDefinition(id, raw.displayName(), raw.type(), display.items(),
                    raw.simulationCount(), raw.childTables(), children);
            TableDefinition planned = new TableDefinition(id, raw.displayName(), raw.type(), planning.items(),
                    raw.simulationCount(), raw.childTables(), children);
            result.put(id, new ExpandedTable(raw, display, planned, contributions, childGates));
        });
        return Map.copyOf(result);
    }

    private static void expandChildren(ResourceLocation parent, ResourceLocation source,
            @Nullable ResourceLocation firstChild, List<LootConditionInfo> inherited,
            Map<ResourceLocation, TableDefinition> tables, Map<ResourceLocation, List<ExpandedContribution>> direct,
            Map<InjectionEdge, List<LootConditionInfo>> gates, Set<ResourceLocation> stack,
            List<ExpandedContribution> result) {
        if (!stack.add(source)) return;
        for (var entry : gates.entrySet()) {
            InjectionEdge edge = entry.getKey();
            if (!edge.source().equals(source) || stack.contains(edge.target())) continue;
            ResourceLocation child = firstChild == null ? edge.target() : firstChild;
            List<LootConditionInfo> conditions = CatalogProjection.mergeConditions(inherited, entry.getValue());
            TableDefinition target = merge(tables.get(edge.target()), direct.getOrDefault(edge.target(), List.of()));
            List<ItemDefinition> items = target.items().stream().map(item -> withPaths(item,
                    item.acquisitionPaths().stream().map(path -> new LootAcquisitionPath(child,
                            path.sourceItemTag(), path.entryConditions(),
                            CatalogProjection.mergeConditions(conditions, path.inheritedConditions()),
                            path.functionUncertainty(), path.luckAffected(), path.luckGate(),
                            path.luckRequirements(), path.functions())).toList())).toList();
            result.add(new ExpandedContribution(parent, edge.owner(), edge.mode(), child, items, conditions));
            expandChildren(parent, edge.target(), child, conditions, tables, direct, gates, stack, result);
        }
        stack.remove(source);
    }

    private static TableDefinition merge(TableDefinition table, List<ExpandedContribution> contributions) {
        Map<LootResultSignature, ItemDefinition> items = new LinkedHashMap<>();
        table.items().forEach(item -> items.put(item.signature(), item));
        for (ExpandedContribution contribution : contributions) for (ItemDefinition added : contribution.items()) {
            ItemDefinition previous = items.get(added.signature());
            if (previous == null) { items.put(added.signature(), added); continue; }
            Map<Object, LootAcquisitionPath> paths = new LinkedHashMap<>();
            previous.acquisitionPaths().forEach(path -> paths.put(pathKey(path), path));
            added.acquisitionPaths().forEach(path -> paths.putIfAbsent(pathKey(path), path));
            Set<LootOriginKind> origins = new LinkedHashSet<>(previous.origins());
            origins.add(LootOriginKind.MOD_INTEGRATION);
            items.put(previous.signature(), new ItemDefinition(previous.id(), previous.displayName(), previous.tooltipHint(),
                    previous.probability(), previous.signature(), List.copyOf(paths.values()), origins,
                    previous.scenarioProbabilities(), previous.observedFunctions()));
        }
        return new TableDefinition(table.id(), table.displayName(), table.type(), List.copyOf(items.values()),
                table.simulationCount(), table.childTables(), table.childTableProbabilities());
    }

    private static Object pathKey(LootAcquisitionPath path) {
        return Arrays.asList(path.sourceChildTable(), path.sourceItemTag(),
                path.entryConditions().stream().map(LootConditionInfo::identity).toList(),
                path.inheritedConditions().stream().map(LootConditionInfo::identity).toList(),
                path.functionUncertainty(), path.luckAffected(), path.luckGate(), path.luckRequirements(),
                path.functions().stream().map(DeclaredInjectionExpansion::functionKey).toList());
    }

    private static Object functionKey(LootFunctionInfo function) {
        return List.of(function.functionType(), function.description(), function.effect(), function.fidelity(),
                function.conditions().stream().map(LootConditionInfo::identity).toList(),
                function.children().stream().map(DeclaredInjectionExpansion::functionKey).toList(), function.metadata());
    }

    private static ItemDefinition withPaths(ItemDefinition item, List<LootAcquisitionPath> paths) {
        return new ItemDefinition(item.id(), item.displayName(), item.tooltipHint(), item.probability(),
                item.signature(), paths, Set.of(LootOriginKind.MOD_INTEGRATION),
                item.scenarioProbabilities(), item.observedFunctions());
    }
}
