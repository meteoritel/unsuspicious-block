package com.meteorite.unsuspiciousblock.core.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.journal.catalog.CatalogIdentity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 公告可请求的共享目录身份。 */
public record SyncCatalogHashPayload(CatalogIdentity identity) implements CustomPacketPayload {
    public static final Type<SyncCatalogHashPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_catalog_identity_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncCatalogHashPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> payload.identity().write(buf),
                    buf -> new SyncCatalogHashPayload(CatalogIdentity.read(buf)));

    @Override
    public @NotNull Type<SyncCatalogHashPayload> type() { return TYPE; }
}
