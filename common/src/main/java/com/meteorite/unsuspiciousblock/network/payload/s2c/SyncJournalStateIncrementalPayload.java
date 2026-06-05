package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 玩家考古状态增量同步包 —— 服务端→客户端（仅包含变更的表进度） */
public record SyncJournalStateIncrementalPayload(long revision, CompoundTag changedTables) implements CustomPacketPayload {

    public static final Type<SyncJournalStateIncrementalPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_journal_state_inc"));

    @Override
    public @NotNull Type<SyncJournalStateIncrementalPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncJournalStateIncrementalPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarLong(payload.revision);
                        buf.writeNbt(payload.changedTables);
                    },
                    buf -> {
                        long revision = buf.readVarLong();
                        CompoundTag tag = buf.readNbt();
                        return new SyncJournalStateIncrementalPayload(revision, tag != null ? tag : new CompoundTag());
                    }
            );
}