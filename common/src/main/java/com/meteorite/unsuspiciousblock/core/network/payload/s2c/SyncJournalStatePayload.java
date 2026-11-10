package com.meteorite.unsuspiciousblock.core.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 玩家考古状态全量同步包 —— 服务端→客户端（含版本号） */
public record SyncJournalStatePayload(long revision, CompoundTag state) implements CustomPacketPayload {

    public static final Type<SyncJournalStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_journal_state"));

    @Override
    public @NotNull Type<SyncJournalStatePayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncJournalStatePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarLong(payload.revision);
                        buf.writeNbt(payload.state);
                    },
                    buf -> {
                        long revision = buf.readVarLong();
                        CompoundTag tag = buf.readNbt();
                        return new SyncJournalStatePayload(revision, tag != null ? tag : new CompoundTag());
                    }
            );
}