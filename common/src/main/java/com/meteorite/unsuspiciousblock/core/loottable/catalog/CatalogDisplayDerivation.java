package com.meteorite.unsuspiciousblock.core.loottable.catalog;

import com.meteorite.unsuspiciousblock.core.loottable.analysis.ConditionIdentity;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ChildTableProbability;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ScenarioProbability;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.FunctionObservationSummary;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.PathHintAnalyzer;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationConstraintCatalog;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationInput;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationScenario;
import com.meteorite.unsuspiciousblock.core.world.LootProbabilityData;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只消费固定读快照的展示派生，不访问当代全局状态和 LRU。 */
public final class CatalogDisplayDerivation {
    private CatalogDisplayDerivation() {}

    /** 输入级测量与表级发现的只读快照。 */
    public record Readings(Map<String, LootProbabilityData.InputMeasurement> byScenario,
            Map<String, LootProbabilityData.DiscoveryRecord> discovery) {
        public Readings {
            byScenario = Map.copyOf(byScenario);
            discovery = Map.copyOf(discovery);
        }
    }

    public static TableDefinition derive(TableDefinition raw, SimulationConstraintCatalog constraint,
            SimulationInput input, Readings readings) {
        String displayedScenarioKey = input.scenarioKey();
        Map<String, LootProbabilityData.InputMeasurement> byScenario = readings.byScenario();
        LootProbabilityData.InputMeasurement displayed = byScenario.get(displayedScenarioKey);
        Map<String, LootProbabilityData.DiscoveryRecord> discovery = readings.discovery();

        List<ItemDefinition> items = new ArrayList<>(raw.items().size() + discovery.size());
        Set<String> rawKeys = new HashSet<>();
        for (ItemDefinition item : raw.items()) {
            String storedKey = item.signature().toStoredKey();
            rawKeys.add(storedKey);
            List<PathHint> hints = PathHintAnalyzer.hintsFor(item.acquisitionPaths());
            List<ScenarioProbability> scenarioProbabilities = itemScenarioProbabilities(
                    constraint, storedKey, item.acquisitionPaths(), hints, byScenario);
            Probability display = PathHintAnalyzer.deriveDisplay(
                    displayedValue(displayed, storedKey, constraint, displayedScenarioKey, hints),
                    item.acquisitionPaths());
            // 规则与观测分区：静态路径照常派生；观测只来自**当前展示输入**，不与别的输入混用（决策 13）
            items.add(new ItemDefinition(item.id(), item.displayName(), item.tooltipHint(),
                    display, item.signature(), item.acquisitionPaths(), item.origins(),
                    scenarioProbabilities, observedFor(displayed, storedKey)));
        }

        // 动态条目（GLM / LootTableEvents.MODIFY 模拟期注入）：没有静态路径，因此"需要什么条件"
        // 无从陈述，展示值只取实际测量到的那一份；它们的"来源"记录挂在表级、不随 LRU 淘汰，
        // 所以即使某个输入的测量值被淘汰，条目本身也不会从网格里消失（决策 37）。
        for (Map.Entry<String, LootProbabilityData.DiscoveryRecord> entry : discovery.entrySet()) {
            if (rawKeys.contains(entry.getKey())) {
                continue;
            }
            LootResultSignature signature = LootResultSignature.fromStoredKey(entry.getKey());
            if (signature == null) {
                continue;
            }
            List<LootAcquisitionPath> paths = discoveredPaths(entry.getValue());
            List<ScenarioProbability> scenarioProbabilities = discoveredScenarioProbabilities(
                    constraint, entry.getKey(), byScenario);
            Probability display = displayedValue(displayed, entry.getKey(), constraint,
                    displayedScenarioKey, List.of());
            // 动态发现**不等于**平台注入（F07 / D09）：模拟期新出现的签名只说明"有东西改了掉落"，
            // 既可能是已声明的模组联动，也可能是本模组尚未理解的原版变换（如熔炼后再改组件）。
            // 这里没有可靠证据，因此来源记为 UNKNOWN_RUNTIME，绝不冒充 MOD_INTEGRATION。
            Set<LootOriginKind> discoveredOrigins = Set.of(LootOriginKind.UNKNOWN_RUNTIME);
            ItemDefinition discoveredItem = LootTableCatalog.buildDiscoveredDefinition(
                    signature, display, discoveredOrigins, scenarioProbabilities);
            FunctionObservationSummary discoveredObservation = observedFor(displayed, entry.getKey());
            if (paths.isEmpty()) {
                items.add(discoveredItem.withObservedFunctions(discoveredObservation));
            } else {
                items.add(new ItemDefinition(discoveredItem.id(), discoveredItem.displayName(),
                        discoveredItem.tooltipHint(), discoveredItem.probability(),
                        discoveredItem.signature(), paths, discoveredItem.origins(),
                        discoveredItem.scenarioProbabilities(), discoveredObservation));
            }
        }

        List<ChildTableProbability> childProbabilities = new ArrayList<>();
        for (ResourceLocation childTable : raw.childTables()) {
            // 两条来源合成入口的条件树：通往它的路径共同成立的条件（交集）+ 注入边门槛。
            // 交集的理由：tooltip 的头是"父表条件"，用并集会把"只有部分路径需要"的条件说成"需要"。
            // 门槛必须在这里补：它不写在任何 JSON 里，静态投影看不到它，缺了它被入口挡住的子表
            // 只能显示一个没有原因的「未命中」。
            List<LootConditionInfo> entryGates = constraint.childEntryGates()
                    .getOrDefault(childTable, List.of());
            List<LootConditionInfo> conditions = raw.childTableProbabilities().stream()
                    .filter(child -> child.tableId().equals(childTable)).findFirst().orElseThrow().conditions();
            // 提示按并集派生：它要回答的是"这个入口引用了哪些可调的旋钮/条件"，比条件树宽松
            List<PathHint> hints = PathHintAnalyzer.hintsForConditions(
                    CatalogProjection.mergeConditions(childTableConditions(raw, childTable), entryGates));
            List<ScenarioProbability> scenarioProbabilities = new ArrayList<>();
            boolean applicableSomewhere = false;
            for (SimulationScenario scenario : constraint.scenarios()) {
                applicableSomewhere |= scenario.applicableChildTables().contains(childTable);
                if (!byScenario.containsKey(scenario.key())) continue;
                if (!scenario.applicableChildTables().contains(childTable)) {
                    scenarioProbabilities.add(new ScenarioProbability(scenario.key(),
                            PathHintAnalyzer.inapplicableScenarioDisplay(hints), scenario.assumptions()));
                    continue;
                }
                LootProbabilityData.InputMeasurement measurement = byScenario.get(scenario.key());
                SimulatedValue measured = measurement == null
                        ? null : measurement.children().get(childTable);
                scenarioProbabilities.add(new ScenarioProbability(scenario.key(),
                        PathHintAnalyzer.deriveEntryDisplay(measured == null
                                ? Probability.unknown(UnknownReason.NOT_SIMULATED)
                                : measured.toProbability(UnknownReason.UNCOVERED), hints),
                        scenario.assumptions()));
            }
            if (!applicableSomewhere) {
                scenarioProbabilities = List.of();
            }
            // 展示值走与物品同构的入口派生：零命中且门槛可陈述时报「需要条件」。
            // 分场景引用与当前行采用同一服务端展示口径；原始计数仍留在测量缓存中。
            childProbabilities.add(new ChildTableProbability(childTable,
                    PathHintAnalyzer.deriveEntryDisplay(
                            scenarioValue(scenarioProbabilities, displayedScenarioKey), hints),
                    List.copyOf(scenarioProbabilities), conditions));
        }

        return new TableDefinition(raw.id(), raw.displayName(), raw.type(), items,
                input.params().sampleCount(), raw.childTables(), childProbabilities);
    }

    // 当前展示输入下某签名的函数观测摘要；null 表示当前输入没有观测——
    // 那是"没观测到"，不是"没有函数执行过"，界面不得把它写成结论（规划 D12）
    @Nullable
    private static FunctionObservationSummary observedFor(
            @Nullable LootProbabilityData.InputMeasurement displayed, String storedKey) {
        return displayed == null ? null : displayed.observations().get(storedKey);
    }

    // 当前展示输入下某个签名的展示值：测到了就是测量值，适用但没算过是"尚未计算"，
    // 不适用则由静态提示决定「需要条件」还是「未覆盖」。
    // hints 由调用方给出：静态条目传它自己路径的提示，动态条目传空——后者没有静态路径可引用。
    private static Probability displayedValue(
            @Nullable LootProbabilityData.InputMeasurement displayed, String storedKey,
            SimulationConstraintCatalog constraint, String displayedScenarioKey, List<PathHint> hints) {
        SimulatedValue measured = displayed == null ? null : displayed.items().get(storedKey);
        if (measured != null) {
            return measured.toProbability(UnknownReason.UNCOVERED);
        }
        SimulationScenario scenario = constraint.scenario(displayedScenarioKey);
        boolean applicableHere = scenario != null
                && scenario.applicableSignatures().contains(storedKey);
        if (applicableHere) {
            return Probability.unknown(UnknownReason.NOT_SIMULATED);
        }
        return PathHintAnalyzer.inapplicableScenarioDisplay(hints);
    }

    // 静态条目的分场景展示列表；没有任何场景覆盖该签名时返回空列表——
    // 那是"场景被上界截断"，逐个写 0 会把"未覆盖"显示成"不可达"
    private static List<ScenarioProbability> itemScenarioProbabilities(
            SimulationConstraintCatalog constraint, String storedKey,
            List<LootAcquisitionPath> paths, List<PathHint> hints,
            Map<String, LootProbabilityData.InputMeasurement> byScenario) {
        List<ScenarioProbability> result = new ArrayList<>();
        boolean applicableSomewhere = false;
        for (SimulationScenario scenario : constraint.scenarios()) {
            applicableSomewhere |= scenario.applicableSignatures().contains(storedKey);
            LootProbabilityData.InputMeasurement measurement = byScenario.get(scenario.key());
            if (measurement == null) continue;
            result.add(new ScenarioProbability(scenario.key(),
                    PathHintAnalyzer.deriveDisplay(displayedValue(measurement, storedKey, constraint,
                            scenario.key(), hints), paths),
                    scenario.assumptions()));
        }
        return applicableSomewhere ? List.copyOf(result) : List.of();
    }

    // 动态条目只保留缓存里实际测到过的场景；其余场景不进列表，避免为"没观测到"编造状态
    private static List<ScenarioProbability> discoveredScenarioProbabilities(
            SimulationConstraintCatalog constraint, String storedKey,
            Map<String, LootProbabilityData.InputMeasurement> byScenario) {
        List<ScenarioProbability> result = new ArrayList<>();
        for (SimulationScenario scenario : constraint.scenarios()) {
            LootProbabilityData.InputMeasurement measurement = byScenario.get(scenario.key());
            SimulatedValue measured = measurement == null ? null : measurement.items().get(storedKey);
            if (measured == null) {
                continue;
            }
            result.add(new ScenarioProbability(scenario.key(),
                    measured.toProbability(UnknownReason.UNCOVERED), scenario.assumptions()));
        }
        return List.copyOf(result);
    }

    // 动态条目的获取路径：来源记录在表级发现记录里，直接还原成路径即可
    private static List<LootAcquisitionPath> discoveredPaths(LootProbabilityData.DiscoveryRecord record) {
        List<LootAcquisitionPath> paths = new ArrayList<>(
                record.sourceChildTables().size() + (record.hasDirectSource() ? 1 : 0));
        if (record.hasDirectSource()) {
            paths.add(new LootAcquisitionPath(null, List.of(), List.of()));
        }
        for (ResourceLocation childSource : record.sourceChildTables()) {
            paths.add(new LootAcquisitionPath(childSource, List.of(), List.of()));
        }
        return List.copyOf(paths);
    }

    // 某个场景在分场景列表里的值；场景不在列表里（被截断或尚未规划）时如实报"未覆盖"
    private static Probability scenarioValue(List<ScenarioProbability> probabilities, String scenarioKey) {
        for (ScenarioProbability probability : probabilities) {
            if (probability.scenarioKey().equals(scenarioKey)) {
                return probability.probability();
            }
        }
        return Probability.uncovered();
    }

    // 该子表入口在父表里出现过的条件（**并集**）；用于"这个场景下为什么拿不到"的静态陈述——
    // 提示要回答的是"引用了哪些可调的旋钮/条件"，只要有一条路径引用过就该列出来
    private static List<LootConditionInfo> childTableConditions(
            TableDefinition parent, ResourceLocation childTable) {
        LinkedHashMap<ConditionIdentity, LootConditionInfo> conditions = new LinkedHashMap<>();
        for (ItemDefinition item : parent.items()) {
            for (LootAcquisitionPath path : item.acquisitionPaths()) {
                if (!childTable.equals(path.sourceChildTable())) {
                    continue;
                }
                for (LootConditionInfo condition : path.allConditions()) {
                    conditions.putIfAbsent(ConditionIdentity.of(condition), condition);
                }
            }
        }
        return List.copyOf(conditions.values());
    }

}
