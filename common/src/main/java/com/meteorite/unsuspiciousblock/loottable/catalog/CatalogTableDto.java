package com.meteorite.unsuspiciousblock.loottable.catalog;

import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ChildTableProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ScenarioProbability;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 目录的**网络形态**——只包含客户端渲染所需字段，不直接暴露服务端内部记录。
 * <p>
 * 与内部记录的关键差异是场景条件的组织方式：同一个代表场景的假设条件树对一张表内的所有物品与
 * 子表完全相同，因此按 {@code scenarioKey} 在表级**只序列化一次**（{@link ScenarioAssumptions}），
 * 物品与子表侧只带 key 与概率（{@link ScenarioRef}）。这消除了"每个物品重复同一个条件树"的
 * 冗余传输，也保证目录哈希的输入与实际上线内容一致。
 * <p>
 * 不变量：同一张表内同一个 {@code scenarioKey} 必然对应同一份假设条件树——它们都来自该表的一次
 * {@code SimulationScenarioPlanner.plan} 结果。若出现冲突按首次出现者保留。
 */
public record CatalogTableDto(
        ResourceLocation id,
        Component displayName,
        String type,
        int simulationCount,
        List<ResourceLocation> childTables,
        List<ScenarioAssumptions> scenarios,
        List<ItemEntry> items,
        List<ChildTableEntry> childProbabilities) {

    public CatalogTableDto {
        childTables = List.copyOf(childTables);
        scenarios = List.copyOf(scenarios);
        items = List.copyOf(items);
        childProbabilities = List.copyOf(childProbabilities);
    }

    /** 表级场景假设：{@code scenarioKey} → 该场景的假设条件树（每表一次）。 */
    public record ScenarioAssumptions(String scenarioKey, List<LootConditionInfo> assumptions) {
        public ScenarioAssumptions {
            assumptions = List.copyOf(assumptions);
        }
    }

    /** 分场景概率的引用形态：只带 key 与数值，条件树引用表级 {@link ScenarioAssumptions}。 */
    public record ScenarioRef(String scenarioKey, Probability probability) {
    }

    /** 物品条目；与内部记录同形，但场景概率为引用形态。 */
    public record ItemEntry(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                            Probability probability, LootResultSignature signature,
                            List<LootAcquisitionPath> acquisitionPaths, boolean injected,
                            List<ScenarioRef> scenarioProbabilities) {
        public ItemEntry {
            acquisitionPaths = List.copyOf(acquisitionPaths);
            scenarioProbabilities = List.copyOf(scenarioProbabilities);
        }
    }

    /** 子表概率条目；同样使用引用形态。 */
    public record ChildTableEntry(ResourceLocation tableId, Probability probability,
                                  List<ScenarioRef> scenarioProbabilities) {
        public ChildTableEntry {
            scenarioProbabilities = List.copyOf(scenarioProbabilities);
        }
    }

    /** 把内部记录映射为网络形态，并按 {@code scenarioKey} 抽取表级假设。 */
    public static CatalogTableDto from(TableDefinition table) {
        Map<String, List<LootConditionInfo>> assumptionsByScenario = new LinkedHashMap<>();
        List<ItemEntry> items = new ArrayList<>(table.items().size());
        for (ItemDefinition item : table.items()) {
            items.add(new ItemEntry(item.id(), item.displayName(), item.tooltipHint(), item.probability(),
                    item.signature(), item.acquisitionPaths(), item.injected(),
                    collectScenarioRefs(item.scenarioProbabilities(), assumptionsByScenario)));
        }

        List<ChildTableEntry> children = new ArrayList<>(table.childTableProbabilities().size());
        for (ChildTableProbability child : table.childTableProbabilities()) {
            children.add(new ChildTableEntry(child.tableId(), child.probability(),
                    collectScenarioRefs(child.scenarioProbabilities(), assumptionsByScenario)));
        }

        // 按 key 排序，使同一份目录每次生成的网络形态与哈希输入都稳定
        List<ScenarioAssumptions> scenarios = new ArrayList<>(assumptionsByScenario.size());
        new TreeMap<>(assumptionsByScenario)
                .forEach((key, assumptions) -> scenarios.add(new ScenarioAssumptions(key, assumptions)));

        return new CatalogTableDto(table.id(), table.displayName(), table.type(), table.simulationCount(),
                table.childTables(), scenarios, items, children);
    }

    /** 还原为内部记录；表级假设按 key 回填到每个分场景概率上。 */
    public TableDefinition toTableDefinition() {
        Map<String, List<LootConditionInfo>> assumptionsByScenario = new LinkedHashMap<>();
        for (ScenarioAssumptions scenario : this.scenarios) {
            assumptionsByScenario.putIfAbsent(scenario.scenarioKey(), scenario.assumptions());
        }

        List<ItemDefinition> items = new ArrayList<>(this.items.size());
        for (ItemEntry item : this.items) {
            items.add(new ItemDefinition(item.id(), item.displayName(), item.tooltipHint(), item.probability(),
                    item.signature(), item.acquisitionPaths(), item.injected(),
                    resolveScenarioProbabilities(item.scenarioProbabilities(), assumptionsByScenario)));
        }

        List<ChildTableProbability> children = new ArrayList<>(this.childProbabilities.size());
        for (ChildTableEntry child : this.childProbabilities) {
            children.add(new ChildTableProbability(child.tableId(), child.probability(),
                    resolveScenarioProbabilities(child.scenarioProbabilities(), assumptionsByScenario)));
        }

        return new TableDefinition(this.id, this.displayName, this.type, items,
                this.simulationCount, this.childTables, children);
    }

    private static List<ScenarioRef> collectScenarioRefs(List<ScenarioProbability> probabilities,
                                                        Map<String, List<LootConditionInfo>> assumptionsByScenario) {
        List<ScenarioRef> refs = new ArrayList<>(probabilities.size());
        for (ScenarioProbability probability : probabilities) {
            assumptionsByScenario.putIfAbsent(probability.scenarioKey(), probability.conditions());
            refs.add(new ScenarioRef(probability.scenarioKey(), probability.probability()));
        }
        return List.copyOf(refs);
    }

    private static List<ScenarioProbability> resolveScenarioProbabilities(
            List<ScenarioRef> refs, Map<String, List<LootConditionInfo>> assumptionsByScenario) {
        List<ScenarioProbability> probabilities = new ArrayList<>(refs.size());
        for (ScenarioRef ref : refs) {
            probabilities.add(new ScenarioProbability(ref.scenarioKey(), ref.probability(),
                    assumptionsByScenario.getOrDefault(ref.scenarioKey(), List.of())));
        }
        return List.copyOf(probabilities);
    }
}
