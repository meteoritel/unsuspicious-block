package com.meteorite.unsuspiciousblock.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端→服务端：同步标本箱内部槽位滚动选择。
 */
public record SpecimenBoxScrollPayload(int selectedInnerSlot) implements CustomPacketPayload {

    public static final Type<SpecimenBoxScrollPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "specimen_box_scroll"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpecimenBoxScrollPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, SpecimenBoxScrollPayload::selectedInnerSlot,
                    SpecimenBoxScrollPayload::new);

    @Override
    public @NotNull Type<SpecimenBoxScrollPayload> type() {
        return TYPE;
    }
}