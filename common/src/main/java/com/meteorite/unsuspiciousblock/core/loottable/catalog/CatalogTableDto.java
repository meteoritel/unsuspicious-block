package com.meteorite.unsuspiciousblock.core.loottable.catalog;

import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ChildTableProbability;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ScenarioProbability;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.injection.DeclaredInjectionExpansion.ExpandedTable;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.FunctionObservationSummary;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationConstraintCatalog;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 目录的网络传输形态——承载表级静态结构、物品/子表展示概率、可勾选条件约束与条件分支清单。
 */
public record CatalogTableDto(
        ResourceLocation id,
        String hash,
        Component displayName,
        String type,
        int simulationCount,
        List<ResourceLocation> childTables,
        List<ItemEntry> items,
        List<ChildTableEntry> childProbabilities,
        @Nullable SimulationOptions options,
        List<ScenarioBranch> branches) {

    public CatalogTableDto {
        childTables = List.copyOf(childTables);
        items = List.copyOf(items);
        childProbabilities = List.copyOf(childProbabilities);
        branches = List.copyOf(branches);
    }

    /** 同参数下已缓存条件组合的概率引用（用于 Tooltip 汇总最小/最大产出区间）。 */
    public record ScenarioRef(String scenarioKey, Probability probability) {
    }

    /** 物品条目网络传输记录。 */
    public record ItemEntry(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                            Probability probability, LootResultSignature signature,
                            List<LootAcquisitionPath> acquisitionPaths, boolean injected,
                            List<ScenarioRef> scenarioProbabilities, Set<LootOriginKind> origins,
                            @Nullable FunctionObservationSummary observedFunctions) {
        public ItemEntry {
            acquisitionPaths = List.copyOf(acquisitionPaths);
            scenarioProbabilities = List.copyOf(scenarioProbabilities);
            origins = LootOriginKind.normalize(origins, injected);
            injected = origins.contains(LootOriginKind.MOD_INTEGRATION);
        }

        /** 兼容构造：无观测摘要。 */
        public ItemEntry(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                         Probability probability, LootResultSignature signature,
                         List<LootAcquisitionPath> acquisitionPaths, boolean injected,
                         List<ScenarioRef> scenarioProbabilities, Set<LootOriginKind> origins) {
            this(id, displayName, tooltipHint, probability, signature, acquisitionPaths, injected,
                    scenarioProbabilities, origins, null);
        }
    }

    /** 子表入口网络传输记录。 */
    public record ChildTableEntry(ResourceLocation tableId, Probability probability,
                                  List<ScenarioRef> scenarioProbabilities,
                                  List<LootConditionInfo> conditions) {
        public ChildTableEntry {
            scenarioProbabilities = List.copyOf(scenarioProbabilities);
            conditions = List.copyOf(conditions);
        }
    }

    /** 将内部表定义装配为网络 DTO。 */
    public static CatalogTableDto assemble(TableDefinition table, String hash,
                                           @Nullable SimulationConstraintCatalog constraints,
                                           @Nullable ExpandedTable expansion,
                                           Map<String, String> sourceNames, long generation) {
        List<ItemEntry> items = new ArrayList<>(table.items().size());
        for (ItemDefinition item : table.items()) {
            items.add(new ItemEntry(item.id(), item.displayName(), item.tooltipHint(), item.probability(),
                    item.signature(), item.acquisitionPaths(), item.injected(),
                    toRefs(item.scenarioProbabilities()), item.origins(),
                    item.observedFunctions()));
        }

        List<ChildTableEntry> children = new ArrayList<>(table.childTableProbabilities().size());
        for (ChildTableProbability child : table.childTableProbabilities()) {
            children.add(new ChildTableEntry(child.tableId(), child.probability(),
                    toRefs(child.scenarioProbabilities()), child.conditions()));
        }

        return new CatalogTableDto(table.id(), hash, table.displayName(), table.type(),
                table.simulationCount(), table.childTables(), items, children,
                constraints == null ? null : SimulationOptions.from(generation, constraints),
                constraints == null ? List.of() : ScenarioBranchCatalog.build(table, expansion, sourceNames));
    }

    /** 还原为内部表定义。 */
    public TableDefinition toTableDefinition() {
        List<ItemDefinition> items = new ArrayList<>(this.items.size());
        for (ItemEntry item : this.items) {
            items.add(new ItemDefinition(item.id(), item.displayName(), item.tooltipHint(), item.probability(),
                    item.signature(), item.acquisitionPaths(), item.injected(),
                    fromRefs(item.scenarioProbabilities()),
                    item.origins(), item.observedFunctions()));
        }

        List<ChildTableProbability> children = new ArrayList<>(this.childProbabilities.size());
        for (ChildTableEntry child : this.childProbabilities) {
            children.add(new ChildTableProbability(child.tableId(), child.probability(),
                    fromRefs(child.scenarioProbabilities()),
                    child.conditions()));
        }

        return new TableDefinition(this.id, this.displayName, this.type(), items,
                this.simulationCount, this.childTables, children);
    }

    private static List<ScenarioRef> toRefs(List<ScenarioProbability> probabilities) {
        List<ScenarioRef> refs = new ArrayList<>(probabilities.size());
        for (ScenarioProbability probability : probabilities) {
            refs.add(new ScenarioRef(probability.scenarioKey(), probability.probability()));
        }
        return List.copyOf(refs);
    }

    private static List<ScenarioProbability> fromRefs(List<ScenarioRef> refs) {
        List<ScenarioProbability> probabilities = new ArrayList<>(refs.size());
        for (ScenarioRef ref : refs) {
            probabilities.add(new ScenarioProbability(ref.scenarioKey(), ref.probability(), List.of()));
        }
        return List.copyOf(probabilities);
    }
}
