package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 玩家考古状态同步包 —— 服务端→客户端 */
public record SyncJournalStatePayload(CompoundTag state) implements CustomPacketPayload {

    public static final Type<SyncJournalStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_journal_state"));

    @Override
    public @NotNull Type<SyncJournalStatePayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncJournalStatePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeNbt(payload.state),
                    buf -> {
                        CompoundTag tag = buf.readNbt();
                        return new SyncJournalStatePayload(tag != null ? tag : new CompoundTag());
                    }
            );
}
