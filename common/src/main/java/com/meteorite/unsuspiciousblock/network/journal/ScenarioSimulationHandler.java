package com.meteorite.unsuspiciousblock.network.journal;

import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.loottable.simulation.LootProbabilitySimulationWorker;
import com.meteorite.unsuspiciousblock.loottable.simulation.ScenarioParams;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestScenarioSimulationPayload;
import com.meteorite.unsuspiciousblock.network.payload.s2c.ScenarioRequestRejectedPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * 按需模拟请求的服务端处理。
 * <p>
 * 这里只做三件事：**限流、构造参数、交给目录受理**。语义判定（输入是否被签发、
 * 表哈希是否过期、表是否可模拟）全部在 {@code ArchaeologyJournalServerCatalog.requestSimulation} 里，
 * 因为那里的约束描述才是"什么算合法输入"的权威——把这个判定复制到网络层就等于有了两份真相。
 * <p>
 * 顺序是先限流再构造参数：限流只需要玩家身份，而参数构造会做数值校验；
 * 把便宜的检查放前面，可以让被限流的连点请求连解析都不做。
 * <p>
 * 被拒绝的请求一定回执（{@link ScenarioRequestRejectedPayload}）：这些情形都不会产出结果包，
 * 没有回执的话"点了没反应"与"还在计算中"在界面上无法区分。回执不携带任何概率，也不进缓存。
 */
public final class ScenarioSimulationHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ScenarioSimulationHandler() {
    }

    /** 处理一次按需模拟请求（在服务端主线程调用）。 */
    public static void handleRequest(ServerPlayer player, RequestScenarioSimulationPayload payload) {
        LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
        if (worker != null && !worker.canAcceptFor(player.getUUID())) {
            reject(player, payload, "", ScenarioRequestRejectedPayload.Reason.PLAYER_LIMIT);
            return;
        }

        ScenarioParams params;
        try {
            params = new ScenarioParams(payload.luck(), payload.toolId(),
                    payload.toolEnchantments(), payload.sampleCount());
        } catch (IllegalArgumentException exception) {
            // 越界幸运、未签发的档位、超范围等级都落在这里。不修正、不猜测，直接拒绝并回执。
            LOGGER.debug("拒绝越界的模拟参数请求（{}）：{}",
                    player.getGameProfile().getName(), exception.getMessage());
            reject(player, payload, "", ScenarioRequestRejectedPayload.Reason.REJECTED_INPUT);
            return;
        }

        ArchaeologyJournalServerCatalog.OnDemandResult result =
                ArchaeologyJournalServerCatalog.requestSimulation(player, payload.tableId(),
                        payload.tableHash(), payload.scenarioKey(), params);
        String inputKey = result.input() == null ? "" : result.input().key();
        switch (result.outcome()) {
            case QUEUED, SIMULATED_INLINE -> {
                // 结果由 SyncScenarioResultPayload 下发，这里不回声
            }
            case UNKNOWN_TABLE -> reject(player, payload, inputKey,
                    ScenarioRequestRejectedPayload.Reason.UNKNOWN_TABLE);
            case STALE_HASH -> reject(player, payload, inputKey,
                    ScenarioRequestRejectedPayload.Reason.STALE_HASH);
            case REJECTED_INPUT -> reject(player, payload, inputKey,
                    ScenarioRequestRejectedPayload.Reason.REJECTED_INPUT);
            case REJECTED_QUEUE_FULL -> reject(player, payload, inputKey,
                    ScenarioRequestRejectedPayload.Reason.QUEUE_FULL);
        }
    }

    private static void reject(ServerPlayer player, RequestScenarioSimulationPayload payload,
                               String inputKey, ScenarioRequestRejectedPayload.Reason reason) {
        Services.NETWORK.sendToPlayer(player, new ScenarioRequestRejectedPayload(
                ArchaeologyJournalServerCatalog.currentGenerationId(), payload.tableId(), inputKey, reason));
    }
}
