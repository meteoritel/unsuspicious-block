package com.meteorite.unsuspiciousblock.core.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationScenarioPlanner;
import com.meteorite.unsuspiciousblock.core.network.payload.SimulationInputCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按需模拟请求（客户端→服务端）——请求计算某个表在已勾选条件集与参数下的概率。
 */
public record RequestScenarioSimulationPayload(long generation,
                                               ResourceLocation tableId,
                                               String tableHash,
                                               List<String> enabledConditions,
                                               float luck,
                                               ResourceLocation toolId,
                                               Map<ResourceLocation, Integer> toolEnchantments,
                                               int sampleCount) implements CustomPacketPayload {

    public static final Type<RequestScenarioSimulationPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    Constants.MOD_ID, "request_scenario_simulation"));

    public RequestScenarioSimulationPayload {
        enabledConditions = List.copyOf(enabledConditions);
        toolEnchantments = Map.copyOf(toolEnchantments);
    }

    @Override
    public @NotNull Type<RequestScenarioSimulationPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestScenarioSimulationPayload> STREAM_CODEC =
            StreamCodec.of(RequestScenarioSimulationPayload::encode, RequestScenarioSimulationPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, RequestScenarioSimulationPayload payload) {
        buf.writeVarLong(payload.generation());
        buf.writeResourceLocation(payload.tableId());
        buf.writeUtf(payload.tableHash());
        buf.writeVarInt(payload.enabledConditions().size());
        for (String conditionId : payload.enabledConditions()) {
            buf.writeUtf(conditionId);
        }
        buf.writeFloat(payload.luck());
        buf.writeResourceLocation(payload.toolId());
        buf.writeVarInt(payload.toolEnchantments().size());
        payload.toolEnchantments().forEach((enchantmentId, level) -> {
            buf.writeResourceLocation(enchantmentId);
            buf.writeVarInt(level);
        });
        buf.writeVarInt(payload.sampleCount());
    }

    private static RequestScenarioSimulationPayload decode(RegistryFriendlyByteBuf buf) {
        long generation = buf.readVarLong();
        ResourceLocation tableId = buf.readResourceLocation();
        String tableHash = buf.readUtf();
        int conditionCount = SimulationInputCodec.count(buf, SimulationScenarioPlanner.MAX_CONDITIONS);
        List<String> enabledConditions = new ArrayList<>(conditionCount);
        for (int i = 0; i < conditionCount; i++) {
            enabledConditions.add(buf.readUtf());
        }
        float luck = buf.readFloat();
        ResourceLocation toolId = buf.readResourceLocation();
        int enchantmentCount = SimulationInputCodec.count(buf, 256);
        Map<ResourceLocation, Integer> toolEnchantments = new LinkedHashMap<>(enchantmentCount);
        for (int i = 0; i < enchantmentCount; i++) {
            toolEnchantments.put(buf.readResourceLocation(), buf.readVarInt());
        }
        int sampleCount = buf.readVarInt();
        return new RequestScenarioSimulationPayload(generation, tableId, tableHash, enabledConditions,
                luck, toolId, toolEnchantments, sampleCount);
    }
}
