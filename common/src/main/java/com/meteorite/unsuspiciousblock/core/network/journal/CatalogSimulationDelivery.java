package com.meteorite.unsuspiciousblock.core.network.journal;

import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog.ScenarioResult;
import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog.SubmissionResult;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.ScenarioRequestRejectedPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncCatalogHashPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncScenarioResultPayload;

import java.util.Objects;

import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 同步与异步模拟结果共用的传输 adapter。
 */
public final class CatalogSimulationDelivery {
    private CatalogSimulationDelivery() {
    }

    public static void deliverSimulation(ServerPlayer player, ScenarioResult result, long requestId) {
        Services.NETWORK.sendToPlayer(player, new SyncScenarioResultPayload(requestId, true,
                result.generation(), result.hash(), result.inputKey(), result.table()));
    }

    public static void deliverCached(ServerPlayer player, ScenarioResult result, long requestId) {
        Services.NETWORK.sendToPlayer(player, new SyncScenarioResultPayload(requestId, false,
                result.generation(), result.hash(), result.inputKey(), result.table()));
    }

    public static void deliver(MinecraftServer server, SubmissionResult result) {
        for (var entry : result.requesters().entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            var ticket = entry.getValue();
            switch (result.outcome()) {
                case COMMITTED ->
                        deliverSimulation(player, Objects.requireNonNull(result.scenario()), ticket.requestId());
                case FAILED, STALE -> Services.NETWORK.sendToPlayer(player, new ScenarioRequestRejectedPayload(
                        ticket.requestId(), ticket.generation(), result.tableId(), ticket.tableHash(), result.inputKey(),
                        ArchaeologyJournalServerCatalog.currentGenerationId(), result.outcome()
                        == ArchaeologyJournalServerCatalog.SubmissionOutcome.STALE
                        ? ScenarioRequestRejectedPayload.Reason.STALE_HASH : ScenarioRequestRejectedPayload.Reason.SIMULATION_FAILED));
                case DUPLICATE, NO_WAITERS -> {
                }
            }
        }
    }

    public static void broadcastHash(MinecraftServer server, long generation) {
        String hash = ArchaeologyJournalServerCatalog.catalogHashFor(generation);
        if (hash.isEmpty()) return;
        SyncCatalogHashPayload payload = new SyncCatalogHashPayload(hash);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) Services.NETWORK.sendToPlayer(player, payload);
    }
}
