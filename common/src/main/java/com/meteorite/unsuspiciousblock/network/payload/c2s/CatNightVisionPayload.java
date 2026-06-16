package com.meteorite.unsuspiciousblock.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 客户端→服务端：根据环境亮度边沿请求开启/关闭「猫的眼」夜视 */
public record CatNightVisionPayload(boolean active) implements CustomPacketPayload {

    public static final Type<CatNightVisionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cat_night_vision"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CatNightVisionPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeBoolean(payload.active),
                    buf -> new CatNightVisionPayload(buf.readBoolean())
            );

    @Override
    public @NotNull Type<CatNightVisionPayload> type() {
        return TYPE;
    }
}
