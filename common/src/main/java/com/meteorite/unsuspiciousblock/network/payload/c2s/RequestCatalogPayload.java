package com.meteorite.unsuspiciousblock.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 请求完整目录包（客户端→服务端）—— 客户端收到哈希后若不一致，请求发送完整目录 */
public record RequestCatalogPayload() implements CustomPacketPayload {

    public static final Type<RequestCatalogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_catalog"));

    @Override
    public @NotNull Type<RequestCatalogPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestCatalogPayload> STREAM_CODEC =
            StreamCodec.of(RequestCatalogPayload::encode, RequestCatalogPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, RequestCatalogPayload payload) {
        // 无额外字段
    }

    private static RequestCatalogPayload decode(RegistryFriendlyByteBuf buf) {
        return new RequestCatalogPayload();
    }
}