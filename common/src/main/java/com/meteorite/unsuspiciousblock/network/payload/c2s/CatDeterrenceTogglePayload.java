package com.meteorite.unsuspiciousblock.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 客户端→服务端：切换「猫的威慑」被动的开关状态 */
public record CatDeterrenceTogglePayload() implements CustomPacketPayload {

    public static final CatDeterrenceTogglePayload INSTANCE = new CatDeterrenceTogglePayload();

    public static final Type<CatDeterrenceTogglePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cat_deterrence_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CatDeterrenceTogglePayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public @NotNull Type<CatDeterrenceTogglePayload> type() {
        return TYPE;
    }
}
