package com.meteorite.unsuspiciousblock.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogStructure;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 全量目录同步包 —— 服务端→客户端。
 * <p>
 * 载荷是 {@link CatalogTableDto} 而非服务端内部记录：场景假设条件树按 {@code scenarioKey}
 * 每表只发一次，物品与子表侧只带 key 与概率。这样把"同一条件树被按物品重复序列化"
 * 的冗余去掉，同时让目录哈希的输入与实际上线内容完全一致。
 * <p>
 * 线格式实现见 {@link CatalogStreamCodec}——它与按需结果下发（{@code SyncScenarioResultPayload}）
 * 共用同一份编解码，避免两条通道对同一份数据各写一套。
 */
public record SyncArchaeologyCatalogPayload(List<CatalogTableDto> catalog, CatalogStructure structure)
        implements CustomPacketPayload {

    public static final Type<SyncArchaeologyCatalogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_archaeology_catalog"));

    public SyncArchaeologyCatalogPayload {
        catalog = List.copyOf(catalog);
    }

    @Override
    public @NotNull Type<SyncArchaeologyCatalogPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncArchaeologyCatalogPayload> STREAM_CODEC =
            StreamCodec.of(SyncArchaeologyCatalogPayload::encode, SyncArchaeologyCatalogPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, SyncArchaeologyCatalogPayload payload) {
        buf.writeVarInt(payload.catalog.size());
        for (CatalogTableDto table : payload.catalog) {
            CatalogStreamCodec.writeTable(buf, table);
        }
        CatalogStreamCodec.writeStructure(buf, payload.structure());
    }

    private static SyncArchaeologyCatalogPayload decode(RegistryFriendlyByteBuf buf) {
        int tableCount = buf.readVarInt();
        List<CatalogTableDto> catalog = new ArrayList<>(tableCount);
        for (int i = 0; i < tableCount; i++) {
            catalog.add(CatalogStreamCodec.readTable(buf));
        }
        return new SyncArchaeologyCatalogPayload(catalog, CatalogStreamCodec.readStructure(buf));
    }
}
