package com.meteorite.unsuspiciousblock.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 请求考古日志快照重同步包（客户端→服务端）。
 * <p>
 * 客户端检测到 sessionId 不匹配（服务端会话已失效/重启）时主动发起，
 * 服务端收到后调用 {@code JournalLogHandler.syncLogSnapshot} 下发当前镜像全量快照。
 */
public record RequestJournalLogSnapshotPayload() implements CustomPacketPayload {

    public static final Type<RequestJournalLogSnapshotPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_journal_log_snapshot"));

    @Override
    public @NotNull Type<RequestJournalLogSnapshotPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestJournalLogSnapshotPayload> STREAM_CODEC =
            StreamCodec.of(RequestJournalLogSnapshotPayload::encode, RequestJournalLogSnapshotPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, RequestJournalLogSnapshotPayload payload) {
        // 无额外字段
    }

    private static RequestJournalLogSnapshotPayload decode(RegistryFriendlyByteBuf buf) {
        return new RequestJournalLogSnapshotPayload();
    }
}
