package com.meteorite.unsuspiciousblock.core.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 客户端请求服务端战利品表管理索引。 */
public record RequestLootTableManagementPayload(View view, long indexRevision) implements CustomPacketPayload {
    public static final Type<RequestLootTableManagementPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_loot_tracking_management"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestLootTableManagementPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> {
                buf.writeEnum(payload.view());
                buf.writeVarLong(payload.indexRevision());
            }, buf -> new RequestLootTableManagementPayload(buf.readEnum(View.class), buf.readVarLong()));

    /** 页面需要的共享数据范围。 */
    public enum View { CLOSED, INDEX, CONFIG }

    @Override
    public @NotNull Type<RequestLootTableManagementPayload> type() {
        return TYPE;
    }
}
