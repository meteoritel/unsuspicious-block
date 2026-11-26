package com.meteorite.unsuspiciousblock.core.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.journal.catalog.CatalogIdentity;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 请求一个已经公告的目录，或重新获取当前目录公告。 */
public record RequestCatalogPayload(long ticket, @Nullable CatalogIdentity expected) implements CustomPacketPayload {
    public static final Type<RequestCatalogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_catalog_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestCatalogPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> {
                buf.writeVarLong(payload.ticket());
                buf.writeBoolean(payload.expected() != null);
                if (payload.expected() != null) payload.expected().write(buf);
            }, buf -> {
                long ticket = buf.readVarLong();
                CatalogIdentity expected = buf.readBoolean() ? CatalogIdentity.read(buf) : null;
                if (ticket < 1 || buf.isReadable()) throw new DecoderException("考古目录请求无效");
                return new RequestCatalogPayload(ticket, expected);
            });

    @Override
    public @NotNull Type<RequestCatalogPayload> type() { return TYPE; }
}
