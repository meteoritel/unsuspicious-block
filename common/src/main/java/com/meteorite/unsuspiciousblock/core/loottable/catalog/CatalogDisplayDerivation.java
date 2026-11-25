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
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioConditionEvaluator;
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
    private CatalogDisplayDerivation() {
    }

    /** 输入级测量与表级发现的只读快照。 */
    public record Readings(Map<String, LootProbabilityData.InputMeasurement> byScenario,
                           Map<String, LootProbabilityData.DiscoveryRecord> discovery) {
        public Readings {
            byScenario = Map.copyOf(byScenario);
            discovery = Map.copyOf(discovery);
        }
    }

    /** 根据当前输入对应的执行场景与已缓存测量值派生展示表定义。 */
    public static TableDefinition derive(TableDefinition raw, SimulationConstraintCatalog constraint,
                                         TableDefinition planningTable, SimulationInput input, Readings readings) {
        SimulationScenario currentScenario = constraint.buildExecutionScenario(
                input.enabledConditions(), planningTable);
        String displayedScenarioKey = currentScenario.key();
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
                    storedKey, item.acquisitionPaths(), hints, byScenario);
            Probability display = PathHintAnalyzer.deriveDisplay(
                    displayedValue(displayed, storedKey, currentScenario, hints),
                    item.acquisitionPaths());
            items.add(new ItemDefinition(item.id(), item.displayName(), item.tooltipHint(),
                    display, item.signature(), item.acquisitionPaths(), item.origins(),
                    scenarioProbabilities, observedFor(displayed, storedKey)));
        }

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
                    entry.getKey(), byScenario);
            Probability display = displayedValue(displayed, entry.getKey(), currentScenario, List.of());
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

        Set<String> enabledSet = Set.copyOf(input.enabledConditions());
        List<ChildTableProbability> childProbabilities = new ArrayList<>();
        for (ResourceLocation childTable : raw.childTables()) {
            List<LootConditionInfo> entryGates = constraint.childEntryGates()
                    .getOrDefault(childTable, List.of());
            List<LootConditionInfo> conditions = raw.childTableProbabilities().stream()
                    .filter(child -> child.tableId().equals(childTable))
                    .findFirst().orElseThrow().conditions();
            conditions = CatalogProjection.mergeConditions(conditions, entryGates);
            List<PathHint> hints = PathHintAnalyzer.hintsForChildEntry(
                    CatalogProjection.mergeConditions(childTableConditions(raw, childTable), entryGates),
                    constraint.childEntryLuckGates().get(childTable),
                    constraint.luckAffectedChildren().contains(childTable));
            List<ScenarioProbability> scenarioProbabilities = new ArrayList<>();
            for (Map.Entry<String, LootProbabilityData.InputMeasurement> entry : byScenario.entrySet()) {
                SimulatedValue measured = entry.getValue().children().get(childTable);
                Probability rawProb = measured == null
                        ? Probability.unknown(UnknownReason.NOT_SIMULATED)
                        : measured.toProbability(UnknownReason.UNCOVERED);
                scenarioProbabilities.add(new ScenarioProbability(entry.getKey(),
                        PathHintAnalyzer.deriveEntryDisplay(rawProb, hints), List.of()));
            }
            boolean applicableHere = currentScenario.applicableChildTables().contains(childTable)
                    && ScenarioConditionEvaluator.areScenarioConditionsSatisfied(conditions, enabledSet);
            Probability entryDisplay;
            if (!applicableHere) {
                entryDisplay = PathHintAnalyzer.inapplicableScenarioDisplay(hints);
            } else {
                SimulatedValue measured = displayed == null ? null : displayed.children().get(childTable);
                Probability rawProb = measured == null
                        ? Probability.unknown(UnknownReason.NOT_SIMULATED)
                        : measured.toProbability(UnknownReason.UNCOVERED);
                entryDisplay = PathHintAnalyzer.deriveEntryDisplay(rawProb, hints);
            }
            childProbabilities.add(new ChildTableProbability(childTable,
                    entryDisplay, List.copyOf(scenarioProbabilities), conditions));
        }

        return new TableDefinition(raw.id(), raw.displayName(), raw.type(), items,
                input.params().sampleCount(), raw.childTables(), childProbabilities);
    }

    @Nullable
    private static FunctionObservationSummary observedFor(
            @Nullable LootProbabilityData.InputMeasurement displayed, String storedKey) {
        return displayed == null ? null : displayed.observations().get(storedKey);
    }

    private static Probability displayedValue(@Nullable LootProbabilityData.InputMeasurement displayed,
                                              String storedKey,
                                              SimulationScenario scenario,
                                              List<PathHint> hints) {
        SimulatedValue measured = displayed == null ? null : displayed.items().get(storedKey);
        if (measured != null) {
            return measured.toProbability(UnknownReason.UNCOVERED);
        }
        if (scenario.applicableSignatures().contains(storedKey)) {
            return Probability.unknown(UnknownReason.NOT_SIMULATED);
        }
        return PathHintAnalyzer.inapplicableScenarioDisplay(hints);
    }

    private static List<ScenarioProbability> itemScenarioProbabilities(
            String storedKey, List<LootAcquisitionPath> paths, List<PathHint> hints,
            Map<String, LootProbabilityData.InputMeasurement> byScenario) {
        List<ScenarioProbability> result = new ArrayList<>();
        for (Map.Entry<String, LootProbabilityData.InputMeasurement> entry : byScenario.entrySet()) {
            SimulatedValue measured = entry.getValue().items().get(storedKey);
            if (measured == null) {
                continue;
            }
            result.add(new ScenarioProbability(entry.getKey(),
                    PathHintAnalyzer.deriveDisplay(measured.toProbability(UnknownReason.UNCOVERED), paths),
                    List.of()));
        }
        return List.copyOf(result);
    }

    private static List<ScenarioProbability> discoveredScenarioProbabilities(
            String storedKey, Map<String, LootProbabilityData.InputMeasurement> byScenario) {
        List<ScenarioProbability> result = new ArrayList<>();
        for (Map.Entry<String, LootProbabilityData.InputMeasurement> entry : byScenario.entrySet()) {
            SimulatedValue measured = entry.getValue().items().get(storedKey);
            if (measured == null) {
                continue;
            }
            result.add(new ScenarioProbability(entry.getKey(),
                    measured.toProbability(UnknownReason.UNCOVERED), List.of()));
        }
        return List.copyOf(result);
    }

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

    private static List<LootConditionInfo> childTableConditions(TableDefinition parent, ResourceLocation childTable) {
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
