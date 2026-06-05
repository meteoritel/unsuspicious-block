package com.meteorite.unsuspiciousblock.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 客户端→服务端：同步扫描仪的扫描等级切换 */
public record UpdateReaderScanLevelPayload(int newLevel) implements CustomPacketPayload {

    public static final Type<UpdateReaderScanLevelPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "update_reader_scan_level"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateReaderScanLevelPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeVarInt(payload.newLevel),
                    buf -> new UpdateReaderScanLevelPayload(buf.readVarInt())
            );

    @Override
    public @NotNull Type<UpdateReaderScanLevelPayload> type() {
        return TYPE;
    }
}
