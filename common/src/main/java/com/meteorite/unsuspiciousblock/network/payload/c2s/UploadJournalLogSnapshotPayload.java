package com.meteorite.unsuspiciousblock.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 日记日志快照上传包（客户端→服务端）。
 * 客户端登录时将本地日志快照上传至服务端，用于合并同步。
 */
public record UploadJournalLogSnapshotPayload(UUID sessionId,
                                              CompoundTag state) implements CustomPacketPayload {

    public static final Type<UploadJournalLogSnapshotPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "upload_journal_log_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UploadJournalLogSnapshotPayload> STREAM_CODEC =
            StreamCodec.of(
                    UploadJournalLogSnapshotPayload::encode,
                    UploadJournalLogSnapshotPayload::decode
            );

    @Override
    public @NotNull Type<UploadJournalLogSnapshotPayload> type() {
        return TYPE;
    }

    // 将上传包编码写入网络缓冲区
    private static void encode(RegistryFriendlyByteBuf buf, UploadJournalLogSnapshotPayload payload) {
        buf.writeUUID(payload.sessionId);
        buf.writeNbt(payload.state);
    }

    // 从网络缓冲区解码上传包
    private static UploadJournalLogSnapshotPayload decode(RegistryFriendlyByteBuf buf) {
        UUID sessionId = buf.readUUID();
        CompoundTag state = buf.readNbt();
        return new UploadJournalLogSnapshotPayload(sessionId, state != null ? state : new CompoundTag());
    }
}
