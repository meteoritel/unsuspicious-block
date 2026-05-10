package com.meteorite.unsuspiciousblock.network;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public record SyncJournalLogSnapshotPayload(UUID sessionId,
                                            long sequence,
                                            CompoundTag state) implements CustomPacketPayload {

    public static final Type<SyncJournalLogSnapshotPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_journal_log_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncJournalLogSnapshotPayload> STREAM_CODEC =
            StreamCodec.of(
                    SyncJournalLogSnapshotPayload::encode,
                    SyncJournalLogSnapshotPayload::decode
            );

    @Override
    public @NotNull Type<SyncJournalLogSnapshotPayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, SyncJournalLogSnapshotPayload payload) {
        buf.writeUUID(payload.sessionId);
        buf.writeVarLong(payload.sequence);
        buf.writeNbt(payload.state);
    }

    private static SyncJournalLogSnapshotPayload decode(RegistryFriendlyByteBuf buf) {
        UUID sessionId = buf.readUUID();
        long sequence = buf.readVarLong();
        CompoundTag state = buf.readNbt();
        return new SyncJournalLogSnapshotPayload(sessionId, sequence, state != null ? state : new CompoundTag());
    }
}
