package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * 按需模拟结果（服务端→客户端）——某一个表在某一个输入下的展示数据。
 * <p>
 * 三个标识缺一不可（决策 36）：
 * <ul>
 *   <li>{@code generation}——目录代次。切参数或 {@code /reload} 之后旧结果可能后到，
 *       代次不符即丢弃，否则界面会被上一代的数据覆盖；</li>
 *   <li>{@code tableHash}——该表的内容哈希。哈希变了说明这些数字属于上一版表内容；</li>
 *   <li>{@code inputKey}——输入的规范键。客户端比对"这是不是我此刻选中的那个输入"，
 *       对不上就只入库不切换界面（玩家可能已经改了参数）。</li>
 * </ul>
 * 载荷复用目录同步的 {@link CatalogTableDto} 形态：它是**单表**的，因此不会像全量目录那样产生大包；
 * 而"场景假设每表只发一次"的优化在这里退化成"每个结果各带一次假设"——单表场景假设很小，
 * 换来的是这条通道完全自洽（不依赖此前发过哪份目录）。线格式见 {@link CatalogStreamCodec}。
 */
public record SyncScenarioResultPayload(long generation, String tableHash, String inputKey,
                                       CatalogTableDto table) implements CustomPacketPayload {

    public static final Type<SyncScenarioResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_scenario_result"));

    @Override
    public @NotNull Type<SyncScenarioResultPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncScenarioResultPayload> STREAM_CODEC =
            StreamCodec.of(SyncScenarioResultPayload::encode, SyncScenarioResultPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, SyncScenarioResultPayload payload) {
        buf.writeVarLong(payload.generation());
        buf.writeUtf(payload.tableHash());
        buf.writeUtf(payload.inputKey());
        CatalogStreamCodec.writeTable(buf, payload.table());
    }

    private static SyncScenarioResultPayload decode(RegistryFriendlyByteBuf buf) {
        long generation = buf.readVarLong();
        String tableHash = buf.readUtf();
        String inputKey = buf.readUtf();
        return new SyncScenarioResultPayload(generation, tableHash, inputKey,
                CatalogStreamCodec.readTable(buf));
    }
}
