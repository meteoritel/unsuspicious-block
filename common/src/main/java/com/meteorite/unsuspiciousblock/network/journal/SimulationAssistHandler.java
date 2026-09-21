package com.meteorite.unsuspiciousblock.network.journal;

import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.loottable.simulation.*;
import com.meteorite.unsuspiciousblock.network.payload.c2s.*;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncSimulationAssistPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** 推荐与读取共用版本校验和每玩家冷却；只在主动请求时执行。 */
public final class SimulationAssistHandler {
    private static final Map<ServerPlayer, Long> LAST_REQUEST = new WeakHashMap<>();
    private SimulationAssistHandler() {}

    public static void handle(ServerPlayer player, RequestSimulationAssistPayload request) {
        var selected = request.selection();
        boolean recommendation = !request.target().isEmpty();
        var generation = ArchaeologyJournalServerCatalog.current();
        var notes = new ArrayList<Component>();
        boolean found = false;
        RequestScenarioSimulationPayload response = selected;
        long now = System.nanoTime();
        Long last = LAST_REQUEST.put(player, now);
        if (last != null && now - last < 500_000_000L) notes.add(note("assist_busy"));
        else if (generation == null || selected.generation() != generation.generation()
                || !selected.tableHash().equals(generation.tableHash(selected.tableId())))
            notes.add(note("assist_stale"));
        else {
            var catalog = generation.constraintCatalog(selected.tableId());
            var raw = generation.staticTable(selected.tableId());
            if (catalog != null && raw != null) {
                try {
                    var params = new ScenarioParams(selected.luck(), selected.toolId(),
                            selected.toolEnchantments(), selected.sampleCount());
                    var previous = catalog.resolve(selected.scenarioKey(), params);
                    if (previous.isPresent()) {
                        var table = ArchaeologyJournalServerCatalog.constraintTable(
                                generation.session(), raw, player.serverLevel());
                        SimulationInput result = null;
                        if (recommendation) {
                            var witness = RecommendationSolver.solve(table, request.target(),
                                    catalog, params, player.serverLevel());
                            if (witness.isPresent()) result = witness.get().input();
                            else notes.add(note("no_witness"));
                        } else {
                            var probe = PlayerStateProbe.probe(player, table, catalog, previous.get());
                            result = probe.input();
                            notes.addAll(probe.notes());
                        }
                        if (result != null) {
                            found = true;
                            var p = result.params();
                            response = new RequestScenarioSimulationPayload(generation.generation(), table.id(),
                                    selected.tableHash(), result.scenarioKey(), p.luck(), p.toolId(),
                                    p.toolEnchantments(), p.sampleCount());
                        }
                    } else notes.add(note("assist_invalid"));
                } catch (RuntimeException exception) {
                    notes.add(note("assist_invalid"));
                }
            } else notes.add(note("assist_invalid"));
        }
        Services.NETWORK.sendToPlayer(player, new SyncSimulationAssistPayload(
                request.requestId(), recommendation, found, response, List.copyOf(notes)));
    }

    private static Component note(String key) {
        return Component.translatable("screen.unsuspiciousblock.archaeology_journal.simulation." + key);
    }
}

