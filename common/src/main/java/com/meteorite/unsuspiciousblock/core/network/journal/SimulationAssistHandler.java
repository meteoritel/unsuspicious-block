package com.meteorite.unsuspiciousblock.core.network.journal;

import com.meteorite.unsuspiciousblock.core.network.payload.ScenarioSelection;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.Recommendation;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.ScenarioBranchCatalog.Candidate;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.PlayerStateProbe;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.RecommendationSolver;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationInput;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationAssistTarget;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestSimulationAssistPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncSimulationAssistPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 推荐与读取共用版本校验和每玩家冷却；只在主动请求时执行。
 */
public final class SimulationAssistHandler {
    private static final Map<ServerPlayer, Long> LAST_REQUEST = new WeakHashMap<>();

    private SimulationAssistHandler() {
    }

    public static void handle(ServerPlayer player, RequestSimulationAssistPayload request) {
        var selected = request.selection();
        boolean recommendation = request.target().recommendation();
        var generation = ArchaeologyJournalServerCatalog.simulationContext(selected.tableId());
        var notes = new ArrayList<Component>();
        boolean found = false;
        Recommendation.Witness witness = null;
        RecommendationSolver.Outcome outcome = null;
        ScenarioSelection response = selected;
        long now = System.nanoTime();
        Long last = LAST_REQUEST.put(player, now);
        if (last != null && now - last < 500_000_000L) notes.add(note("assist_busy"));
        else if (generation == null || selected.generation() != generation.generation()
                || !selected.tableHash().equals(generation.tableHash()))
            notes.add(note("assist_stale"));
        else {
            var catalog = generation.constraints();
            var raw = generation.planningTable();
            var branches = generation.branches();
            if (catalog != null && raw != null && branches != null) {
                try {
                    var params = new ScenarioParams(selected.luck(), selected.toolId(),
                            selected.toolEnchantments(), selected.sampleCount());
                    var previous = catalog.resolve(selected.enabledConditions(), params);
                    boolean validTarget = switch (request.target().kind()) {
                        case PLAYER_STATE -> request.target().value().isEmpty();
                        case ITEM ->
                                raw.items().stream().anyMatch(item -> item.signature().toStoredKey().equals(request.target().value()));
                        case BRANCH -> branches.branches().stream().anyMatch(branch -> branch.recommendable()
                                && branch.reference().equals(request.target().value()));
                        case CHILD_TABLE -> raw.childTables().stream()
                                .anyMatch(child -> child.toString().equals(request.target().value()));
                    };
                    if (previous.isPresent() && validTarget) {
                        var table = generation.planningTable();
                        SimulationInput result = null;
                        if (recommendation) {
                            List<Candidate> candidates = branches.candidates().entrySet().stream()
                                    .filter(entry -> request.target().kind() != SimulationAssistTarget.Kind.BRANCH
                                            || entry.getKey().equals(request.target().value()))
                                    .flatMap(entry -> entry.getValue().stream())
                                    .filter(candidate -> switch (request.target().kind()) {
                                        case ITEM -> candidate.itemSignature().equals(request.target().value());
                                        case CHILD_TABLE ->
                                                candidate.kind().equals("table") && candidate.target().equals(request.target().value());
                                        case BRANCH -> true;
                                        case PLAYER_STATE -> false;
                                    }).toList();
                            var solved = RecommendationSolver.solve(candidates, catalog, previous.get());
                            outcome = solved.outcome();
                            if (solved.recommendation() != null) {
                                result = solved.recommendation().input();
                                witness = solved.recommendation().witness();
                            } else {
                                notes.add(note(switch (solved.outcome()) {
                                    case UNSUPPORTED -> "recommend.unsupported";
                                    case BUDGET_EXHAUSTED -> "no_witness";
                                    default -> "recommend.no_candidate";
                                }));
                                for (String reason : solved.reasons())
                                    notes.add(Component.translatable(
                                            "screen.unsuspiciousblock.archaeology_journal.simulation.recommend.reason", reason));
                            }
                        } else {
                            var probe = PlayerStateProbe.probe(player, catalog, previous.get());
                            result = probe.input();
                            notes.addAll(probe.notes());
                        }
                        if (result != null) {
                            found = true;
                            var p = result.params();
                            response = new ScenarioSelection(generation.generation(), table.id(),
                                    selected.tableHash(), result.enabledConditions(), p.luck(), p.toolId(),
                                    p.toolEnchantments(), p.sampleCount());
                        }
                    } else {
                        Constants.LOG.warn("{} 辅助请求校验失败: table={}, resolved={}, validTarget={}",
                                Constants.LOG_TAG, selected.tableId(), previous.isPresent(), validTarget);
                        notes.add(note("assist_invalid"));
                    }
                } catch (RuntimeException exception) {
                    Constants.LOG.warn("{} 辅助请求处理异常: table={}", Constants.LOG_TAG, selected.tableId(), exception);
                    notes.add(note("assist_invalid"));
                }
            } else {
                Constants.LOG.warn("{} 辅助请求缺少约束或规划表: table={}", Constants.LOG_TAG, selected.tableId());
                notes.add(note("assist_invalid"));
            }
        }
        Services.NETWORK.sendToPlayer(player, new SyncSimulationAssistPayload(
                request.requestId(), request.target(), found, response, outcome, witness, List.copyOf(notes)));
    }

    private static Component note(String key) {
        return Component.translatable("screen.unsuspiciousblock.archaeology_journal.simulation." + key);
    }
}
