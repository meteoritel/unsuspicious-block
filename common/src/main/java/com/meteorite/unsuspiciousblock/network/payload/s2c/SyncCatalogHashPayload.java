package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 目录哈希同步包（服务端→客户端）—— 仅发送目录内容的哈希值，用于按需同步 */
public record SyncCatalogHashPayload(String catalogHash) implements CustomPacketPayload {

    public static final Type<SyncCatalogHashPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_catalog_hash"));

    @Override
    public @NotNull Type<SyncCatalogHashPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncCatalogHashPayload> STREAM_CODEC =
            StreamCodec.of(SyncCatalogHashPayload::encode, SyncCatalogHashPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, SyncCatalogHashPayload payload) {
        buf.writeUtf(payload.catalogHash());
    }

    private static SyncCatalogHashPayload decode(RegistryFriendlyByteBuf buf) {
        String hash = buf.readUtf();
        return new SyncCatalogHashPayload(hash);
    }
}