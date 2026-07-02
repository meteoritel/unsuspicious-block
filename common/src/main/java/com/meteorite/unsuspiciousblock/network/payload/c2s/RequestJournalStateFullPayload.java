package com.meteorite.unsuspiciousblock.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 请求考古日记进度全量同步包（客户端→服务端）。
 * <p>
 * 客户端检测到 revision 间隙（增量包版本号不连续）时主动发起，
 * 服务端收到后调用 {@code JournalStateHandler.syncStateFull} 下发完整状态。
 */
public record RequestJournalStateFullPayload() implements CustomPacketPayload {

    public static final Type<RequestJournalStateFullPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_journal_state_full"));

    @Override
    public @NotNull Type<RequestJournalStateFullPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestJournalStateFullPayload> STREAM_CODEC =
            StreamCodec.of(RequestJournalStateFullPayload::encode, RequestJournalStateFullPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, RequestJournalStateFullPayload payload) {
        // 无额外字段
    }

    private static RequestJournalStateFullPayload decode(RegistryFriendlyByteBuf buf) {
        return new RequestJournalStateFullPayload();
    }
}
