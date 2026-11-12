package com.meteorite.unsuspiciousblock.core.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 单输入缓存可用性答复，绑定查询序号、代次、表哈希与规范输入键。 */
public record SyncScenarioCachePayload(long requestId,
                                       long generation,
                                       ResourceLocation tableId,
                                       String tableHash,
                                       String inputKey,
                                       boolean selectedCached,
                                       String error) implements CustomPacketPayload {
    public static final Type<SyncScenarioCachePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_scenario_cache"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncScenarioCachePayload> STREAM_CODEC =
            StreamCodec.of((buffer, payload) -> {
                buffer.writeVarLong(payload.requestId());
                buffer.writeVarLong(payload.generation());
                buffer.writeResourceLocation(payload.tableId());
                buffer.writeUtf(payload.tableHash());
                buffer.writeUtf(payload.inputKey());
                buffer.writeBoolean(payload.selectedCached());
                buffer.writeUtf(payload.error());
            }, buffer -> new SyncScenarioCachePayload(buffer.readVarLong(), buffer.readVarLong(),
                    buffer.readResourceLocation(), buffer.readUtf(), buffer.readUtf(),
                    buffer.readBoolean(), buffer.readUtf()));

    @Override
    public @NotNull Type<SyncScenarioCachePayload> type() {
        return TYPE;
    }
}
