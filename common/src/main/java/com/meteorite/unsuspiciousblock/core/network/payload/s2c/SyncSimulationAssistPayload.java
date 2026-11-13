package com.meteorite.unsuspiciousblock.core.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.Recommendation.Witness;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.RecommendationSolver.Outcome;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationAssistTarget;
import com.meteorite.unsuspiciousblock.core.network.payload.ScenarioSelection;
import com.meteorite.unsuspiciousblock.core.network.payload.SimulationInputCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求范围、内容版本与完整路径见证一起回传。
 */
public record SyncSimulationAssistPayload(long requestId, SimulationAssistTarget target, boolean found,
                                          ScenarioSelection selection, @Nullable Outcome outcome,
                                          @Nullable Witness witness, List<Component> notes)
        implements CustomPacketPayload {
    public static final Type<SyncSimulationAssistPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_simulation_assist"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncSimulationAssistPayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeVarLong(p.requestId());
                buf.writeEnum(p.target().kind());
                buf.writeUtf(p.target().value());
                buf.writeBoolean(p.found());
                ScenarioSelection.STREAM_CODEC.encode(buf, p.selection());
                buf.writeBoolean(p.outcome() != null);
                if (p.outcome() != null) buf.writeEnum(p.outcome());
                buf.writeBoolean(p.witness() != null);
                if (p.witness() != null) {
                    Witness w = p.witness();
                    buf.writeUtf(w.branchReference());
                    buf.writeUtf(w.targetKind());
                    buf.writeUtf(w.target());
                    buf.writeUtf(w.itemSignature());
                    buf.writeUtf(w.pathId());
                }
                buf.writeVarInt(p.notes().size());
                for (Component note : p.notes()) buf.writeUtf(Component.Serializer.toJson(note, buf.registryAccess()));
            }, buf -> {
                long id = buf.readVarLong();
                var target = new SimulationAssistTarget(buf.readEnum(SimulationAssistTarget.Kind.class), buf.readUtf());
                boolean found = buf.readBoolean();
                var input = ScenarioSelection.STREAM_CODEC.decode(buf);
                Outcome outcome = buf.readBoolean() ? buf.readEnum(Outcome.class) : null;
                Witness witness = buf.readBoolean() ? new Witness(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf()) : null;
                int count = SimulationInputCodec.count(buf, 4096);
                List<Component> notes = new ArrayList<>();
                for (int i = 0; i < count; i++)
                    notes.add(Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess()));
                return new SyncSimulationAssistPayload(id, target, found, input, outcome, witness, List.copyOf(notes));
            });

    public boolean recommendation() {
        return target.recommendation();
    }

    @Override
    public @NotNull Type<SyncSimulationAssistPayload> type() {
        return TYPE;
    }
}
