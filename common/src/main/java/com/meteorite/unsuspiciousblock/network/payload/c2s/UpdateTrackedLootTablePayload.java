package com.meteorite.unsuspiciousblock.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 管理员切换单张战利品表的追踪状态。 */
public record UpdateTrackedLootTablePayload(ResourceLocation tableId, boolean tracked)
        implements CustomPacketPayload {
    public static final Type<UpdateTrackedLootTablePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "update_tracked_loot_table"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateTrackedLootTablePayload> STREAM_CODEC =
            StreamCodec.of(UpdateTrackedLootTablePayload::encode, UpdateTrackedLootTablePayload::decode);

    @Override
    public @NotNull Type<UpdateTrackedLootTablePayload> type() {
        return TYPE;
    }

    private static void encode(RegistryFriendlyByteBuf buf, UpdateTrackedLootTablePayload payload) {
        buf.writeResourceLocation(payload.tableId);
        buf.writeBoolean(payload.tracked);
    }

    private static UpdateTrackedLootTablePayload decode(RegistryFriendlyByteBuf buf) {
        return new UpdateTrackedLootTablePayload(buf.readResourceLocation(), buf.readBoolean());
    }
}
