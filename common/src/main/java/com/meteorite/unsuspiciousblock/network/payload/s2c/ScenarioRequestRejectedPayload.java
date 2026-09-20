package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 按需模拟请求被拒绝的回执（服务端→客户端）。
 * <p>
 * 存在的理由是一句很简单的要求：**点了没反应必须有解释**。请求可能因为目录换代、
 * 输入未被签发、队列满或每玩家在途额度用尽而不被受理，这些情形都不会产出结果包，
 * 若没有回执，玩家看到的就只是"按钮点了没动静"——而那和"还在计算中"在界面上无法区分。
 * <p>
 * 回执**不携带任何概率**，也不参与缓存：它只说明"这一次没有受理，以及为什么"。
 *
 * @param generation 收到请求时服务端的目录代次，供客户端丢弃过期回执
 * @param tableId    被拒绝的表
 * @param inputKey   被拒绝的输入键（客户端据此判断它是否仍与自己当前的选择相关）
 * @param reason     拒绝原因
 */
public record ScenarioRequestRejectedPayload(long generation, ResourceLocation tableId,
                                            String inputKey, Reason reason)
        implements CustomPacketPayload {

    public static final Type<ScenarioRequestRejectedPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    Constants.MOD_ID, "scenario_request_rejected"));

    @Override
    public @NotNull Type<ScenarioRequestRejectedPayload> type() {
        return TYPE;
    }

    /** 拒绝原因——与 {@code ArchaeologyJournalServerCatalog.OnDemandOutcome} 一一对应。 */
    public enum Reason {
        /** 表未收录或本模组无法模拟它。 */
        UNKNOWN_TABLE,
        /** 客户端持有的表哈希已过期，需要先重新同步目录。 */
        STALE_HASH,
        /** 输入未被目录签发（自造场景、超界幸运、未签发的档位或附魔等级）。 */
        REJECTED_INPUT,
        /** 服务端玩家请求队列已满，稍后重试即可。 */
        QUEUE_FULL,
        /** 该玩家的在途请求已达上限。 */
        PLAYER_LIMIT
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ScenarioRequestRejectedPayload> STREAM_CODEC =
            StreamCodec.of(ScenarioRequestRejectedPayload::encode, ScenarioRequestRejectedPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, ScenarioRequestRejectedPayload payload) {
        buf.writeVarLong(payload.generation());
        buf.writeResourceLocation(payload.tableId());
        buf.writeUtf(payload.inputKey());
        buf.writeEnum(payload.reason());
    }

    private static ScenarioRequestRejectedPayload decode(RegistryFriendlyByteBuf buf) {
        long generation = buf.readVarLong();
        ResourceLocation tableId = buf.readResourceLocation();
        String inputKey = buf.readUtf();
        return new ScenarioRequestRejectedPayload(generation, tableId, inputKey,
                buf.readEnum(Reason.class));
    }
}
