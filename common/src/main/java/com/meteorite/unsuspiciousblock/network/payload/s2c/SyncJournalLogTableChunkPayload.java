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

/** 日志单表快照分片包——同一张表按 chunkIndex 顺序重组压缩 NBT。 */
public record SyncJournalLogTableChunkPayload(UUID sessionId,
                                              UUID snapshotId,
                                              ResourceLocation tableId,
                                              int chunkIndex,
                                              int chunkCount,
                                              byte[] data) implements CustomPacketPayload {
    public static final Type<SyncJournalLogTableChunkPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_journal_log_table_chunk"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncJournalLogTableChunkPayload> STREAM_CODEC =
            StreamCodec.of(SyncJournalLogTableChunkPayload::encode,
                    SyncJournalLogTableChunkPayload::decode);

    public SyncJournalLogTableChunkPayload {
        data = data.clone();
    }

    @Override
    public byte[] data() {
        return this.data.clone();
    }

    @Override
    public @NotNull Type<SyncJournalLogTableChunkPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, SyncJournalLogTableChunkPayload payload) {
        buf.writeUUID(payload.sessionId);
        buf.writeUUID(payload.snapshotId);
        buf.writeResourceLocation(payload.tableId);
        buf.writeVarInt(payload.chunkIndex);
        buf.writeVarInt(payload.chunkCount);
        buf.writeByteArray(payload.data);
    }

    private static SyncJournalLogTableChunkPayload decode(RegistryFriendlyByteBuf buf) {
        UUID sessionId = buf.readUUID();
        UUID snapshotId = buf.readUUID();
        ResourceLocation tableId = buf.readResourceLocation();
        int chunkIndex = buf.readVarInt();
        int chunkCount = buf.readVarInt();
        if (chunkCount <= 0 || chunkCount > JournalLogSnapshotCodec.MAX_CHUNKS_PER_TABLE
                || chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new DecoderException("Invalid journal snapshot chunk: " + chunkIndex + "/" + chunkCount);
        }
        byte[] data = buf.readByteArray(JournalLogSnapshotCodec.MAX_CHUNK_BYTES);
        return new SyncJournalLogTableChunkPayload(
                sessionId, snapshotId, tableId, chunkIndex, chunkCount, data);
    }
}
