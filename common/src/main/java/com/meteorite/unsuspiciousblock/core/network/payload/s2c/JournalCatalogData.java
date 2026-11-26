package com.meteorite.unsuspiciousblock.core.network.payload.s2c;

import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogTableDto;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.CatalogStructure;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;

/** 共享目录完整事务的内容，复用场景线路的表定义 codec。 */
public record JournalCatalogData(List<CatalogTableDto> catalog, CatalogStructure structure,
                                 List<String> signatureExclusions,
                                 Map<ResourceLocation, Set<ResourceLocation>> foldedItems) {

    public JournalCatalogData {
        catalog = List.copyOf(catalog);
        signatureExclusions = List.copyOf(signatureExclusions);
        foldedItems = Map.copyOf(foldedItems);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, JournalCatalogData> STREAM_CODEC =
            StreamCodec.of(JournalCatalogData::encode, JournalCatalogData::decode);

    private static void encode(RegistryFriendlyByteBuf buf, JournalCatalogData payload) {
        buf.writeVarInt(payload.catalog.size());
        for (CatalogTableDto table : payload.catalog) {
            CatalogStreamCodec.writeTable(buf, table);
        }
        CatalogStreamCodec.writeStructure(buf, payload.structure());
        buf.writeCollection(payload.signatureExclusions, FriendlyByteBuf::writeUtf);
        buf.writeVarInt(payload.foldedItems.size());
        for (ResourceLocation tableId : payload.foldedItems.keySet().stream().sorted().toList()) {
            buf.writeResourceLocation(tableId);
            buf.writeCollection(payload.foldedItems.get(tableId).stream().sorted().toList(),
                    FriendlyByteBuf::writeResourceLocation);
        }
    }

    private static JournalCatalogData decode(RegistryFriendlyByteBuf buf) {
        int tableCount = CatalogStreamCodec.readCount(buf);
        List<CatalogTableDto> catalog = new ArrayList<>(tableCount);
        for (int i = 0; i < tableCount; i++) {
            catalog.add(CatalogStreamCodec.readTable(buf));
        }
        CatalogStructure structure = CatalogStreamCodec.readStructure(buf);
        int exclusionCount = CatalogStreamCodec.readCount(buf);
        List<String> exclusions = new ArrayList<>(exclusionCount);
        for (int index = 0; index < exclusionCount; index++) exclusions.add(buf.readUtf());
        int foldedCount = CatalogStreamCodec.readCount(buf);
        Map<ResourceLocation, Set<ResourceLocation>> folded = new LinkedHashMap<>();
        for (int index = 0; index < foldedCount; index++) {
            ResourceLocation table = buf.readResourceLocation();
            int count = CatalogStreamCodec.readCount(buf);
            Set<ResourceLocation> items = new HashSet<>();
            for (int item = 0; item < count; item++) items.add(buf.readResourceLocation());
            folded.put(table, Set.copyOf(items));
        }
        return new JournalCatalogData(catalog, structure, exclusions, folded);
    }
}
