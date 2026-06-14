package com.meteorite.unsuspiciousblock.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 战利品概率数据持久化 —— 存储 LootTable hash 和各条目的模拟概率字符串。
 * <p>
 * 存储格式：
 * <pre>
 * root tag:
 *   "entries" -> ListTag, 每个 CompoundTag 包含:
 *     "table_id" -> String (ResourceLocation)
 *     "hash"     -> String (SHA-256 hex)
 *     "items"    -> ListTag, 每个 CompoundTag 包含:
 *       "key"         -> String (signature storedKey)
 *       "probability" -> String (格式化的概率，如 "12.5%", "&lt;0.01%", "?")
 *       "sim_count"  -> int (模拟次数)
 * </pre>
 */
public final class LootProbabilityData extends SavedData {
    private static final Logger LOGGER = LoggerFactory.getLogger(LootProbabilityData.class);
    private static final String FILE_NAME = "unsuspiciousblock_loot_probability";
    private static final String TAG_ENTRIES = "entries";
    private static final String TAG_TABLE_ID = "table_id";
    private static final String TAG_HASH = "hash";
    private static final String TAG_ITEMS = "items";
    private static final String TAG_KEY = "key";
    private static final String TAG_PROBABILITY = "probability";

    private static final SavedData.Factory<LootProbabilityData> FACTORY = new SavedData.Factory<>(
            LootProbabilityData::new,
            LootProbabilityData::load,
            DataFixTypes.LEVEL
    );

    // tableId -> (hash + per-signature 概率数据)
    private final Map<ResourceLocation, TableProbabilityEntry> entries = new LinkedHashMap<>();

    public static LootProbabilityData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    private LootProbabilityData() {
    }

    // 从 NBT 加载
    private static LootProbabilityData load(CompoundTag tag, HolderLookup.Provider registries) {
        LootProbabilityData data = new LootProbabilityData();
        ListTag entriesTag = tag.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < entriesTag.size(); i++) {
            CompoundTag entryTag = entriesTag.getCompound(i);
            ResourceLocation tableId = ResourceLocation.tryParse(entryTag.getString(TAG_TABLE_ID));
            if (tableId == null) continue;

            String hash = entryTag.getString(TAG_HASH);
            Map<String, String> probabilities = new LinkedHashMap<>();
            ListTag itemsTag = entryTag.getList(TAG_ITEMS, Tag.TAG_COMPOUND);
            for (int j = 0; j < itemsTag.size(); j++) {
                CompoundTag itemTag = itemsTag.getCompound(j);
                String key = itemTag.getString(TAG_KEY);
                String probability = itemTag.getString(TAG_PROBABILITY);
                probabilities.put(key, probability);
            }
            data.entries.put(tableId, new TableProbabilityEntry(hash, probabilities));
        }
        LOGGER.debug("从存档加载了 {} 个战利品概率表数据", data.entries.size());
        return data;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        ListTag entriesTag = new ListTag();
        for (Map.Entry<ResourceLocation, TableProbabilityEntry> entry : entries.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString(TAG_TABLE_ID, entry.getKey().toString());
            entryTag.putString(TAG_HASH, entry.getValue().hash());

            ListTag itemsTag = new ListTag();
            for (Map.Entry<String, String> probEntry : entry.getValue().probabilities().entrySet()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putString(TAG_KEY, probEntry.getKey());
                itemTag.putString(TAG_PROBABILITY, probEntry.getValue());
                itemsTag.add(itemTag);
            }
            entryTag.put(TAG_ITEMS, itemsTag);
            entriesTag.add(entryTag);
        }
        tag.put(TAG_ENTRIES, entriesTag);
        return tag;
    }

    // 判断某个表的 hash 是否变化，需要重新模拟
    public boolean needsResimulation(ResourceLocation tableId, String currentHash) {
        TableProbabilityEntry entry = entries.get(tableId);
        if (entry == null) return true;
        return !entry.hash().equals(currentHash);
    }

    // 保存某个表的模拟结果
    public void putSimulationResult(ResourceLocation tableId, String hash, Map<String, String> probabilities) {
        entries.put(tableId, new TableProbabilityEntry(hash, probabilities));
        setDirty();
    }

    // 获取某个条目的概率字符串，不存在时返回 null
    public String getProbability(ResourceLocation tableId, String signatureKey) {
        TableProbabilityEntry entry = entries.get(tableId);
        if (entry == null) return null;
        return entry.probabilities().get(signatureKey);
    }

    // 获取某个表的全部概率映射
    public Map<String, String> getProbabilities(ResourceLocation tableId) {
        TableProbabilityEntry entry = entries.get(tableId);
        if (entry == null) return Collections.emptyMap();
        return Collections.unmodifiableMap(entry.probabilities());
    }

    // 某个表的数据是否存在
    public boolean hasData(ResourceLocation tableId) {
        return entries.containsKey(tableId);
    }

    /** 单个战利品表的概率数据条目 */
    public record TableProbabilityEntry(String hash, Map<String, String> probabilities) {
    }
}