package com.meteorite.unsuspiciousblock.network.journal;

import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
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
 * 这里只做三件事：**构造参数、交给目录受理、把不受理的原因回执给玩家**。语义判定（输入是否被签发、
 * 表哈希是否过期、表是否可模拟、在途额度是否够）全部在 {@code ArchaeologyJournalServerCatalog.requestSimulation} 里，
 * 因为那里的约束描述才是"什么算合法输入"的权威——把这个判定复制到网络层就等于有了两份真相。
 * <p>
 * 限流**不在这里预检**：同一个输入的测量值可能已经在缓存里（别的玩家、上一次会话或启动批次算过），
 * 那种请求不需要排队、也不消耗 tick 预算，若在入口就按"在途额度用尽"拒掉，玩家会在答案就在眼前时
 * 收到一句"请求过多"。因此每玩家额度改由目录在**真正需要入队时**判定（{@code PLAYER_LIMIT}）。
 * <p>
 * 没有结果的请求一定回执（{@link ScenarioRequestRejectedPayload}）：受理失败与被受理但算不出来
 * 都不产出结果包，没有回执的话"点了没反应"与"还在计算中"在界面上无法区分。回执不携带任何概率，也不进缓存。
 */
public final class ScenarioSimulationHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ScenarioSimulationHandler() {
    }

    /** 处理一次按需模拟请求（在服务端主线程调用）。 */
    public static void handleRequest(ServerPlayer player, RequestScenarioSimulationPayload payload) {
        if (payload.generation() != ArchaeologyJournalServerCatalog.currentGenerationId()) {
            reject(player, payload, "", ScenarioRequestRejectedPayload.Reason.STALE_HASH);
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
            case QUEUED, SIMULATED_INLINE, CACHE_HIT -> {
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
            case PLAYER_LIMIT -> reject(player, payload, inputKey,
                    ScenarioRequestRejectedPayload.Reason.PLAYER_LIMIT);
            case SIMULATION_FAILED -> reject(player, payload, inputKey,
                    ScenarioRequestRejectedPayload.Reason.SIMULATION_FAILED);
        }
    }

    private static void reject(ServerPlayer player, RequestScenarioSimulationPayload payload,
                               String inputKey, ScenarioRequestRejectedPayload.Reason reason) {
        if (inputKey.isEmpty()) {
            try {
                inputKey = new com.meteorite.unsuspiciousblock.loottable.simulation.SimulationInput(
                        payload.scenarioKey(), java.util.Map.of(), new ScenarioParams(payload.luck(),
                        payload.toolId(), payload.toolEnchantments(), payload.sampleCount())).key();
            } catch (IllegalArgumentException ignored) {
                // 非法参数不能生成规范键；客户端以请求超时兜底。
            }
        }
        Services.NETWORK.sendToPlayer(player, new ScenarioRequestRejectedPayload(
                ArchaeologyJournalServerCatalog.currentGenerationId(), payload.tableId(), inputKey, reason));
    }
}
