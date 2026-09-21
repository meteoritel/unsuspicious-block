package com.meteorite.unsuspiciousblock.network.payload.s2c;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.network.payload.c2s.RequestScenarioSimulationPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import java.util.*;
/** 请求上下文与候选输入一起回传；客户端只应用仍匹配当前选择的答复。 */
public record SyncSimulationAssistPayload(long requestId, boolean recommendation, boolean found,
        RequestScenarioSimulationPayload selection, List<Component> notes) implements CustomPacketPayload {
    public static final Type<SyncSimulationAssistPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_simulation_assist"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncSimulationAssistPayload> STREAM_CODEC =
        StreamCodec.of((buf, p) -> {
            buf.writeVarLong(p.requestId()); buf.writeBoolean(p.recommendation()); buf.writeBoolean(p.found());
            RequestScenarioSimulationPayload.STREAM_CODEC.encode(buf, p.selection());
            buf.writeVarInt(p.notes().size());
            for (Component note : p.notes()) buf.writeUtf(Component.Serializer.toJson(note, buf.registryAccess()));
        }, buf -> {
            long id = buf.readVarLong(); boolean rec = buf.readBoolean(), found = buf.readBoolean();
            var input = RequestScenarioSimulationPayload.STREAM_CODEC.decode(buf);
            int count = com.meteorite.unsuspiciousblock.network.payload.SimulationInputCodec.count(buf, 4096);
            List<Component> notes = new ArrayList<>();
            for (int i = 0; i < count; i++) notes.add(Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess()));
            return new SyncSimulationAssistPayload(id, rec, found, input, List.copyOf(notes));
        });
    @Override public @NotNull Type<SyncSimulationAssistPayload> type() { return TYPE; }
}

