package com.meteorite.unsuspiciousblock.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/** 查询同参数下全部场景的缓存，可选读取选中场景；绝不启动模拟。 */
public record RequestScenarioCachePayload(long requestId, boolean fetchSelected,
                                         RequestScenarioSimulationPayload selection) implements CustomPacketPayload {
    public static final Type<RequestScenarioCachePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_scenario_cache"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestScenarioCachePayload> STREAM_CODEC =
            StreamCodec.of((buffer, payload) -> {
                buffer.writeVarLong(payload.requestId());
                buffer.writeBoolean(payload.fetchSelected());
                RequestScenarioSimulationPayload.STREAM_CODEC.encode(buffer, payload.selection());
            }, buffer -> new RequestScenarioCachePayload(buffer.readVarLong(), buffer.readBoolean(),
                    RequestScenarioSimulationPayload.STREAM_CODEC.decode(buffer)));

    @Override public @NotNull Type<RequestScenarioCachePayload> type() { return TYPE; }
}
