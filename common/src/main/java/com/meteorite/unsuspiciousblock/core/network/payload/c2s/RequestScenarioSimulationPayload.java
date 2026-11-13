package com.meteorite.unsuspiciousblock.core.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.network.payload.ScenarioSelection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 一次模拟交互的票据和完整选择。
 */
public record RequestScenarioSimulationPayload(long requestId,
                                               ScenarioSelection selection) implements CustomPacketPayload {
    public static final Type<RequestScenarioSimulationPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_scenario_simulation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestScenarioSimulationPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> {
                buf.writeVarLong(payload.requestId());
                ScenarioSelection.STREAM_CODEC.encode(buf, payload.selection());
            }, buf -> new RequestScenarioSimulationPayload(buf.readVarLong(), ScenarioSelection.STREAM_CODEC.decode(buf)));

    @Override
    public @NotNull Type<RequestScenarioSimulationPayload> type() {
        return TYPE;
    }
}
