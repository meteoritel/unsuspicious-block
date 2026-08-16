package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** 日志分片快照结束包——客户端收到后校验完整性并提交新状态。 */
public record SyncJournalLogSnapshotEndPayload(UUID sessionId,
                                               UUID snapshotId) implements CustomPacketPayload {
    public static final Type<SyncJournalLogSnapshotEndPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_journal_log_snapshot_end"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncJournalLogSnapshotEndPayload> STREAM_CODEC =
            StreamCodec.of(SyncJournalLogSnapshotEndPayload::encode,
                    SyncJournalLogSnapshotEndPayload::decode);

    @Override
    public @NotNull Type<SyncJournalLogSnapshotEndPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, SyncJournalLogSnapshotEndPayload payload) {
        buf.writeUUID(payload.sessionId);
        buf.writeUUID(payload.snapshotId);
    }

    private static SyncJournalLogSnapshotEndPayload decode(RegistryFriendlyByteBuf buf) {
        return new SyncJournalLogSnapshotEndPayload(buf.readUUID(), buf.readUUID());
    }
}
