package com.meteorite.unsuspiciousblock.network.payload.c2s;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationAssistTarget;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
/** 带请求序号、显式目标与原输入的辅助请求。 */
public record RequestSimulationAssistPayload(long requestId, SimulationAssistTarget target, RequestScenarioSimulationPayload selection)
        implements CustomPacketPayload {
    public static final Type<RequestSimulationAssistPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_simulation_assist"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestSimulationAssistPayload> STREAM_CODEC =
        StreamCodec.of((buf, p) -> {
            buf.writeVarLong(p.requestId());
            buf.writeEnum(p.target().kind());
            buf.writeUtf(p.target().value());
            RequestScenarioSimulationPayload.STREAM_CODEC.encode(buf, p.selection());
        }, buf -> new RequestSimulationAssistPayload(buf.readVarLong(),
                new SimulationAssistTarget(buf.readEnum(SimulationAssistTarget.Kind.class), buf.readUtf()),
                RequestScenarioSimulationPayload.STREAM_CODEC.decode(buf)));
    @Override public @NotNull Type<RequestSimulationAssistPayload> type() { return TYPE; }
}
