package com.meteorite.unsuspiciousblock.core.network.payload.s2c;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.CatalogStructure;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

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
public record SyncArchaeologyCatalogPayload(List<CatalogTableDto> catalog, CatalogStructure structure,
                                            List<String> signatureExclusions,
                                            Map<ResourceLocation, Set<ResourceLocation>> foldedItems)
        implements CustomPacketPayload {

    public static final Type<SyncArchaeologyCatalogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "sync_archaeology_catalog"));

    public SyncArchaeologyCatalogPayload {
        catalog = List.copyOf(catalog);
        signatureExclusions = List.copyOf(signatureExclusions);
        foldedItems = Map.copyOf(foldedItems);
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
        buf.writeCollection(payload.signatureExclusions, (buffer, id) -> buffer.writeUtf(id));
        buf.writeVarInt(payload.foldedItems.size());
        for (ResourceLocation tableId : payload.foldedItems.keySet().stream().sorted().toList()) {
            buf.writeResourceLocation(tableId);
            buf.writeCollection(payload.foldedItems.get(tableId).stream().sorted().toList(),
                    (buffer, item) -> buffer.writeResourceLocation(item));
        }
    }

    private static SyncArchaeologyCatalogPayload decode(RegistryFriendlyByteBuf buf) {
        int tableCount = buf.readVarInt();
        List<CatalogTableDto> catalog = new ArrayList<>(tableCount);
        for (int i = 0; i < tableCount; i++) {
            catalog.add(CatalogStreamCodec.readTable(buf));
        }
        CatalogStructure structure = CatalogStreamCodec.readStructure(buf);
        List<String> exclusions = buf.readList(buffer -> buffer.readUtf());
        int foldedCount = buf.readVarInt();
        Map<ResourceLocation, Set<ResourceLocation>> folded = new LinkedHashMap<>();
        for (int index = 0; index < foldedCount; index++) {
            folded.put(buf.readResourceLocation(), Set.copyOf(buf.readList(buffer -> buffer.readResourceLocation())));
        }
        return new SyncArchaeologyCatalogPayload(catalog, structure, exclusions, folded);
    }
}
