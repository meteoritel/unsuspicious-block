package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import java.util.List;

/** 缓存可用性答复，绑定查询序号、代次、表哈希与原输入。 */
public record SyncScenarioCachePayload(long requestId, long generation, ResourceLocation tableId,
                                      String tableHash, String inputKey, List<String> availableScenes,
                                      String error) implements CustomPacketPayload {
    public SyncScenarioCachePayload { availableScenes = List.copyOf(availableScenes); }
    public static final Type<SyncScenarioCachePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_scenario_cache"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncScenarioCachePayload> STREAM_CODEC =
            StreamCodec.of((buffer, payload) -> {
                buffer.writeVarLong(payload.requestId());
                buffer.writeVarLong(payload.generation());
                buffer.writeResourceLocation(payload.tableId());
                buffer.writeUtf(payload.tableHash());
                buffer.writeUtf(payload.inputKey());
                buffer.writeCollection(payload.availableScenes(), (output, scene) -> output.writeUtf(scene));
                buffer.writeUtf(payload.error());
            }, buffer -> new SyncScenarioCachePayload(buffer.readVarLong(), buffer.readVarLong(),
                    buffer.readResourceLocation(), buffer.readUtf(), buffer.readUtf(),
                    buffer.readList(input -> input.readUtf()), buffer.readUtf()));

    @Override public @NotNull Type<SyncScenarioCachePayload> type() { return TYPE; }
}
