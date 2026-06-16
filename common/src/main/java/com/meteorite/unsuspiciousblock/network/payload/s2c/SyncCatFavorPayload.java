package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 服务端→客户端：同步玩家当前的猫之恩惠值，供「猫之手」物品 tooltip 显示 */
public record SyncCatFavorPayload(int favor) implements CustomPacketPayload {

    public static final Type<SyncCatFavorPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_cat_favor"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncCatFavorPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeVarInt(payload.favor),
                    buf -> new SyncCatFavorPayload(buf.readVarInt())
            );

    @Override
    public @NotNull Type<SyncCatFavorPayload> type() {
        return TYPE;
    }
}
