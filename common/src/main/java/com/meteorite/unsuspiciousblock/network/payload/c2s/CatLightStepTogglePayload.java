package com.meteorite.unsuspiciousblock.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 客户端→服务端：切换「轻步」压力板触发的开关状态 */
public record CatLightStepTogglePayload() implements CustomPacketPayload {

    public static final CatLightStepTogglePayload INSTANCE = new CatLightStepTogglePayload();

    public static final Type<CatLightStepTogglePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cat_light_step_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CatLightStepTogglePayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public @NotNull Type<CatLightStepTogglePayload> type() {
        return TYPE;
    }
}
