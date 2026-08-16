package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.network.journal.JournalLogSnapshotCodec;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** 日志分片快照开始包——声明会话、基线序列号和预期表数量。 */
public record SyncJournalLogSnapshotStartPayload(UUID sessionId,
                                                 long sequence,
                                                 UUID snapshotId,
                                                 int tableCount) implements CustomPacketPayload {
    public static final Type<SyncJournalLogSnapshotStartPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_journal_log_snapshot_start"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncJournalLogSnapshotStartPayload> STREAM_CODEC =
            StreamCodec.of(SyncJournalLogSnapshotStartPayload::encode,
                    SyncJournalLogSnapshotStartPayload::decode);

    @Override
    public @NotNull Type<SyncJournalLogSnapshotStartPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, SyncJournalLogSnapshotStartPayload payload) {
        buf.writeUUID(payload.sessionId);
        buf.writeVarLong(payload.sequence);
        buf.writeUUID(payload.snapshotId);
        buf.writeVarInt(payload.tableCount);
    }

    private static SyncJournalLogSnapshotStartPayload decode(RegistryFriendlyByteBuf buf) {
        UUID sessionId = buf.readUUID();
        long sequence = buf.readVarLong();
        UUID snapshotId = buf.readUUID();
        int tableCount = buf.readVarInt();
        if (tableCount < 0 || tableCount > JournalLogSnapshotCodec.MAX_TABLES_PER_SNAPSHOT) {
            throw new DecoderException("Invalid journal snapshot table count: " + tableCount);
        }
        return new SyncJournalLogSnapshotStartPayload(sessionId, sequence, snapshotId, tableCount);
    }
}
