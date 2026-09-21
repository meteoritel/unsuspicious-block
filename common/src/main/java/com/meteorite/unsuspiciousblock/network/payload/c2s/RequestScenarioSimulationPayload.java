package com.meteorite.unsuspiciousblock.network.payload.c2s;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按需模拟请求（客户端→服务端）——请求计算某个表在某组参数下的概率。
 * <p>
 * 传的是**结构化字段**而不是一个拼好的输入键，理由是服务端必须能逐项校验：
 * 场景由 key 指认（服务端据此还原条件赋值，客户端不构造指纹），工具/次数只能取签发值，
 * 幸运有界。把这些校验建立在一个自造字符串上就等于把校验逻辑也写成一个解析器，
 * 而解析器的每一处"宽松处理"都是越权的入口（决策 15）。
 * <p>
 * {@code tableHash} 是客户端所持目录里该表的内容哈希：它对不上说明客户端在按上一版内容提问，
 * 服务端会拒绝并让客户端先重新同步目录，而不是按一份已经失效的参数去算一个会误导人的数字。
 */
public record RequestScenarioSimulationPayload(long generation, ResourceLocation tableId,
                                              String tableHash, String scenarioKey, float luck,
                                              ResourceLocation toolId,
                                              Map<ResourceLocation, Integer> toolEnchantments,
                                              int sampleCount) implements CustomPacketPayload {

    public static final Type<RequestScenarioSimulationPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    Constants.MOD_ID, "request_scenario_simulation"));

    public RequestScenarioSimulationPayload {
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
        buf.writeUtf(payload.scenarioKey());
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
        String scenarioKey = buf.readUtf();
        float luck = buf.readFloat();
        ResourceLocation toolId = buf.readResourceLocation();
        int enchantmentCount = com.meteorite.unsuspiciousblock.network.payload.SimulationInputCodec.count(buf, 256);
        Map<ResourceLocation, Integer> toolEnchantments = new LinkedHashMap<>(enchantmentCount);
        for (int i = 0; i < enchantmentCount; i++) {
            toolEnchantments.put(buf.readResourceLocation(), buf.readVarInt());
        }
        int sampleCount = buf.readVarInt();
        return new RequestScenarioSimulationPayload(generation, tableId, tableHash, scenarioKey,
                luck, toolId, toolEnchantments, sampleCount);
    }
}
