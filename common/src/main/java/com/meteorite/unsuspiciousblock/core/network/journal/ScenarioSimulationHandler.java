package com.meteorite.unsuspiciousblock.core.network.journal;

import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationInputKey;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestScenarioCachePayload;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.RequestScenarioSimulationPayload;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.LootProbabilitySimulationWorker.SimulationTicket;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.ScenarioRequestRejectedPayload;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.SyncScenarioCachePayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * 按需模拟请求与缓存查询的服务端处理。
 */
public final class ScenarioSimulationHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ServerPlayer, Long> CACHE_REQUESTS = new WeakHashMap<>();

    private ScenarioSimulationHandler() {
    }

    /**
     * 处理客户端的只读缓存状态查询；命中且请求拉取时附带下发完整单表结果。
     */
    public static void handleCache(ServerPlayer player, RequestScenarioCachePayload payload) {
        var request = payload.selection();
        String inputKey = "";
        String error = "";
        boolean cached = false;
        try {
            ScenarioParams params = new ScenarioParams(request.luck(), request.toolId(),
                    request.toolEnchantments(), request.sampleCount());
            inputKey = SimulationInputKey.of(request.enabledConditions(), params);
            long now = System.currentTimeMillis();
            Long last = CACHE_REQUESTS.put(player, now);
            if (last != null && now - last < 100) {
                throw new IllegalArgumentException("busy");
            }
            if (request.generation() != ArchaeologyJournalServerCatalog.currentGenerationId()) {
                throw new IllegalArgumentException("stale_hash");
            }
            var status = ArchaeologyJournalServerCatalog.cachedStatus(player, request.tableId(),
                    request.tableHash(), request.enabledConditions(), params, payload.fetchSelected());
            cached = status.cached();
            if (status.selected() != null) {
                CatalogSimulationDelivery.deliverCached(player, status.selected(), payload.requestId());
            }
        } catch (IllegalArgumentException exception) {
            error = "stale_hash".equals(exception.getMessage()) ? "stale_hash"
                    : "busy".equals(exception.getMessage()) ? "busy" : "rejected_input";
        }
        Services.NETWORK.sendToPlayer(player,
                new SyncScenarioCachePayload(
                        payload.requestId(), request.generation(), request.tableId(), request.tableHash(),
                        inputKey, cached, error));
    }

    /**
     * 处理一次按需模拟请求（在服务端主线程调用）。
     */
    public static void handleRequest(ServerPlayer player, RequestScenarioSimulationPayload payload) {
        var request = payload.selection();
        if (request.generation() != ArchaeologyJournalServerCatalog.currentGenerationId()) {
            reject(player, payload, "", ScenarioRequestRejectedPayload.Reason.STALE_HASH);
            return;
        }

        ScenarioParams params;
        try {
            params = new ScenarioParams(request.luck(), request.toolId(),
                    request.toolEnchantments(), request.sampleCount());
        } catch (IllegalArgumentException exception) {
            LOGGER.debug("拒绝越界的模拟参数请求（{}）：{}",
                    player.getGameProfile().getName(), exception.getMessage());
            reject(player, payload, "", ScenarioRequestRejectedPayload.Reason.REJECTED_INPUT);
            return;
        }

        ArchaeologyJournalServerCatalog.OnDemandResult result =
                ArchaeologyJournalServerCatalog.requestSimulation(player, request.tableId(),
                        request.tableHash(), request.enabledConditions(), params,
                        new SimulationTicket(payload.requestId(), request.generation(), request.tableHash()));
        String inputKey = result.input() == null ? "" : result.input().key();
        switch (result.outcome()) {
            case QUEUED -> {
            }
            case SIMULATED_INLINE, CACHE_HIT -> CatalogSimulationDelivery.deliverSimulation(
                    player, Objects.requireNonNull(result.scenario()), payload.requestId());
            case UNKNOWN_TABLE -> reject(player, payload, inputKey,
                    ScenarioRequestRejectedPayload.Reason.UNKNOWN_TABLE);
            case STALE_HASH -> reject(player, payload, inputKey,
                    ScenarioRequestRejectedPayload.Reason.STALE_HASH);
            case REJECTED_INPUT -> reject(player, payload, inputKey,
                    ScenarioRequestRejectedPayload.Reason.REJECTED_INPUT);
            case REJECTED_QUEUE_FULL -> reject(player, payload, inputKey,
                    ScenarioRequestRejectedPayload.Reason.QUEUE_FULL);
            case PLAYER_LIMIT -> reject(player, payload, inputKey,
                    ScenarioRequestRejectedPayload.Reason.PLAYER_LIMIT);
            case SIMULATION_FAILED -> reject(player, payload, inputKey,
                    ScenarioRequestRejectedPayload.Reason.SIMULATION_FAILED);
        }
    }

    private static void reject(ServerPlayer player, RequestScenarioSimulationPayload payload,
                               String inputKey, ScenarioRequestRejectedPayload.Reason reason) {
        var request = payload.selection();
        Services.NETWORK.sendToPlayer(player, new ScenarioRequestRejectedPayload(
                payload.requestId(), request.generation(), request.tableId(), request.tableHash(), inputKey,
                ArchaeologyJournalServerCatalog.currentGenerationId(), reason));
    }
}
