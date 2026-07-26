package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 服务端到客户端：同步猫族关系、羁绊值与九命命数，供猫之手 Tooltip 与 HUD 显示。 */
public record SyncCatFavorPayload(int catBond, int nineLivesCount,
                                  boolean relationshipEstablished) implements CustomPacketPayload {

    public static final Type<SyncCatFavorPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_cat_favor"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncCatFavorPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.catBond);
                        buf.writeVarInt(payload.nineLivesCount);
                        buf.writeBoolean(payload.relationshipEstablished);
                    },
                    buf -> new SyncCatFavorPayload(buf.readVarInt(), buf.readVarInt(), buf.readBoolean())
            );

    @Override
    public @NotNull Type<SyncCatFavorPayload> type() {
        return TYPE;
    }
}
