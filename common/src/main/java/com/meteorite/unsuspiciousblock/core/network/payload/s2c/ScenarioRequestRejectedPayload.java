package com.meteorite.unsuspiciousblock.core.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 请求拒绝回显原票据；当前服务端代次独立用于目录刷新。
 */
public record ScenarioRequestRejectedPayload(long requestId, long generation, ResourceLocation tableId,
                                             String tableHash, String inputKey, long currentGeneration, Reason reason)
        implements CustomPacketPayload {

    public static final Type<ScenarioRequestRejectedPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    Constants.MOD_ID, "scenario_request_rejected"));

    @Override
    public @NotNull Type<ScenarioRequestRejectedPayload> type() {
        return TYPE;
    }

    /**
     * 没有结果的原因——与 {@code ArchaeologyJournalServerCatalog.OnDemandOutcome} 一一对应。
     */
    public enum Reason {
        /**
         * 表未收录或本模组无法模拟它。
         */
        UNKNOWN_TABLE,
        /**
         * 客户端持有的表哈希已过期，需要先重新同步目录。
         */
        STALE_HASH,
        /**
         * 输入未被目录签发（自造场景、超界幸运、未签发的档位或附魔等级）。
         */
        REJECTED_INPUT,
        /**
         * 服务端玩家请求队列已满，稍后重试即可。
         */
        QUEUE_FULL,
        /**
         * 该玩家的在途请求已达上限。
         */
        PLAYER_LIMIT,
        /**
         * 请求被受理，但模拟本身失败（表为空，或抽取过程抛异常）。
         * <p>
         * 与上面几项的区别是"受理了却没算出来"：它不写入缓存、也不产生测量值，
         * 客户端因此应当显示"模拟失败"而不是"未计算"——后者会让人以为再点一次就好。
         * 追加在末尾而不是插进中间：编码用的是序数，插入会改写已有取值的含义。
         */
        SIMULATION_FAILED
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ScenarioRequestRejectedPayload> STREAM_CODEC =
            StreamCodec.of(ScenarioRequestRejectedPayload::encode, ScenarioRequestRejectedPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, ScenarioRequestRejectedPayload payload) {
        buf.writeVarLong(payload.requestId());
        buf.writeVarLong(payload.generation());
        buf.writeResourceLocation(payload.tableId());
        buf.writeUtf(payload.tableHash());
        buf.writeUtf(payload.inputKey());
        buf.writeVarLong(payload.currentGeneration());
        buf.writeEnum(payload.reason());
    }

    private static ScenarioRequestRejectedPayload decode(RegistryFriendlyByteBuf buf) {
        long requestId = buf.readVarLong();
        long generation = buf.readVarLong();
        ResourceLocation tableId = buf.readResourceLocation();
        String tableHash = buf.readUtf();
        String inputKey = buf.readUtf();
        long currentGeneration = buf.readVarLong();
        return new ScenarioRequestRejectedPayload(requestId, generation, tableId, tableHash, inputKey, currentGeneration,
                buf.readEnum(Reason.class));
    }
}
