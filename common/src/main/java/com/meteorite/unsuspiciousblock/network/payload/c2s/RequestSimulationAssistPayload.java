package com.meteorite.unsuspiciousblock.network.payload.c2s;
import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
/** 带请求序号的推荐或当前状态读取请求；空目标表示读取玩家状态。 */
public record RequestSimulationAssistPayload(long requestId, String target, RequestScenarioSimulationPayload selection)
        implements CustomPacketPayload {
    public static final Type<RequestSimulationAssistPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_simulation_assist"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestSimulationAssistPayload> STREAM_CODEC =
        StreamCodec.of((buf, p) -> {
            buf.writeVarLong(p.requestId()); buf.writeUtf(p.target());
            RequestScenarioSimulationPayload.STREAM_CODEC.encode(buf, p.selection());
        }, buf -> new RequestSimulationAssistPayload(buf.readVarLong(), buf.readUtf(),
                RequestScenarioSimulationPayload.STREAM_CODEC.decode(buf)));
    @Override public @NotNull Type<RequestSimulationAssistPayload> type() { return TYPE; }
}

