package com.meteorite.unsuspiciousblock.core.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.pan.variant.PanningMedium;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** 服务端接受的淘洗时钟与介质，结束时只发送玩家身份。 */
public record PanningStatePayload(UUID playerId, PanningMedium medium, InteractionHand hand,
                                  long startedAt, int duration)
        implements CustomPacketPayload {
    public static final Type<PanningStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "panning_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PanningStatePayload> STREAM_CODEC =
            StreamCodec.of(PanningStatePayload::write, PanningStatePayload::read);

    private static void write(RegistryFriendlyByteBuf buf, PanningStatePayload payload) {
        buf.writeUUID(payload.playerId);
        buf.writeBoolean(payload.duration > 0);
        if (payload.duration > 0) {
            buf.writeEnum(payload.medium);
            buf.writeEnum(payload.hand);
            buf.writeVarLong(payload.startedAt);
            buf.writeVarInt(payload.duration);
        }
    }

    private static PanningStatePayload read(RegistryFriendlyByteBuf buf) {
        UUID playerId = buf.readUUID();
        return buf.readBoolean()
                ? new PanningStatePayload(playerId, buf.readEnum(PanningMedium.class),
                        buf.readEnum(InteractionHand.class), buf.readVarLong(), buf.readVarInt())
                : stopped(playerId);
    }

    /** 创建服务端结束快照。 */
    public static PanningStatePayload stopped(UUID playerId) {
        return new PanningStatePayload(playerId, PanningMedium.WATER, InteractionHand.MAIN_HAND, 0, 0);
    }

    @Override
    public @NotNull Type<PanningStatePayload> type() {
        return TYPE;
    }
}
