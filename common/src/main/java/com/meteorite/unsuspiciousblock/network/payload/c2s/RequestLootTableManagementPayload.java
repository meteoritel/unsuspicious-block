package com.meteorite.unsuspiciousblock.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 客户端请求服务端战利品表管理索引。 */
public record RequestLootTableManagementPayload() implements CustomPacketPayload {
    public static final Type<RequestLootTableManagementPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_loot_table_management"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestLootTableManagementPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> { }, buf -> new RequestLootTableManagementPayload());

    @Override
    public @NotNull Type<RequestLootTableManagementPayload> type() {
        return TYPE;
    }
}
