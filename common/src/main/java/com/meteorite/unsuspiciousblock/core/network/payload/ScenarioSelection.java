package com.meteorite.unsuspiciousblock.core.network.payload;

import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationScenarioPlanner;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 请求和候选共用的原始选择值；参数规范化与签发校验留在受理入口。
 */
public record ScenarioSelection(long generation,
                                ResourceLocation tableId,
                                String tableHash,
                                List<String> enabledConditions,
                                float luck,
                                ResourceLocation toolId,
                                Map<ResourceLocation, Integer> toolEnchantments,
                                int sampleCount) {

    public ScenarioSelection {
        enabledConditions = List.copyOf(enabledConditions);
        toolEnchantments = Map.copyOf(toolEnchantments);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ScenarioSelection> STREAM_CODEC =
            StreamCodec.of(ScenarioSelection::encode, ScenarioSelection::decode);

    private static void encode(RegistryFriendlyByteBuf buf, ScenarioSelection payload) {
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

    private static ScenarioSelection decode(RegistryFriendlyByteBuf buf) {
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
        return new ScenarioSelection(generation, tableId, tableHash, enabledConditions,
                luck, toolId, toolEnchantments, sampleCount);
    }
}
