package com.meteorite.unsuspiciousblock.core.network.journal;

import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog.ScenarioResult;
import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog.SubmissionResult;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.LootProbabilitySimulationWorker;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.ScenarioRequestRejectedPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncCatalogHashPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncScenarioResultPayload;
import java.util.Objects;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** 同步与异步模拟结果共用的传输 adapter。 */
public final class CatalogSimulationDelivery {
    private CatalogSimulationDelivery() {}

    public static void install(LootProbabilitySimulationWorker worker, long generation) {
        worker.setResultHandler((result, submission, server) ->
                deliver(server, ArchaeologyJournalServerCatalog.commitSimulated(result, submission, server)));
        worker.setQueueDrainedHandler(server -> broadcastHash(server, generation));
    }

    public static void deliver(ServerPlayer player, ScenarioResult result) {
        Services.NETWORK.sendToPlayer(player, new SyncScenarioResultPayload(
                result.generation(), result.hash(), result.inputKey(), result.table()));
    }

    public static void deliver(MinecraftServer server, SubmissionResult result) {
        for (var requester : result.requesters()) {
            ServerPlayer player = server.getPlayerList().getPlayer(requester);
            if (player == null) continue;
            switch (result.outcome()) {
                case COMMITTED -> deliver(player, Objects.requireNonNull(result.scenario()));
                case FAILED -> Services.NETWORK.sendToPlayer(player, new ScenarioRequestRejectedPayload(
                        result.generation(), result.tableId(), result.inputKey(),
                        ScenarioRequestRejectedPayload.Reason.SIMULATION_FAILED));
                case STALE, DUPLICATE, NO_WAITERS -> { }
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
