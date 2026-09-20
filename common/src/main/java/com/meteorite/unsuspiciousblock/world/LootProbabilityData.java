package com.meteorite.unsuspiciousblock.world;

import com.meteorite.unsuspiciousblock.loottable.catalog.SimulatedValue;
import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * 战利品概率数据持久化——按表存储内容哈希与各条目的数值化概率。
 * <p>
 * 存储格式（{@code format_version = 3}）：
 * <pre>
 * root tag:
 *   "format_version" -> int
 *   "entries" -> ListTag, 每个 CompoundTag 包含:
 *     "table_id" -> String (ResourceLocation)
 *     "hash"     -> String (SHA-256 hex)
 *     "items"    -> ListTag, 每个 CompoundTag 包含:
 *       "key"                 -> String (signature storedKey)
 *       "value"               -> CompoundTag，见下
 *       "has_direct_source"   -> boolean
 *       "source_child_tables" -> ListTag&lt;StringTag&gt;
 *       "scenarios"           -> ListTag, 每项 { "scenario_key": String, "value": CompoundTag }
 *
 * 测量值 CompoundTag（{@link SimulatedValue}，只表达"算没算、算出多少"）:
 *   { "state": "unknown" }                          -- 没有测量值
 *   { "state": "measured", "lower": double [, "upper": double] }
 * </pre>
 * 格式 3 相对格式 2 的两处变化（决策 22）：条目级与分场景值都改存窄类型，**不再存**
 * {@code unreachable} ——"静态不可达"与"需要条件"都是从条件树与路径结构推导出的结论，
 * 读取时按当前静态结构重新派生，因此改了静态分析规则不需要迁移存档。
 * <p>
 * 兼容策略（规划 D2）：<b>不做数值迁移</b>。根缺少 {@code format_version}、版本不匹配、
 * 概率仍是旧的 {@code StringTag}（注意这不是"字段缺失"，必须显式按类型判定）、
 * 或单个 entry 的概率无法解析时，一律把对应表视为<b>缓存未命中</b>——
 * 既不报错中断，也不把类型不匹配解成 0，避免"静默的错值"。
 */
public final class LootProbabilityData extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "unsuspiciousblock_loot_probability";
    /** 概率存储格式版本；旧文件没有该字段，因此"缺失"即视为不兼容。 */
    private static final int FORMAT_VERSION = 3;
    private static final String TAG_FORMAT_VERSION = "format_version";
    private static final String TAG_ENTRIES = "entries";
    private static final String TAG_TABLE_ID = "table_id";
    private static final String TAG_HASH = "hash";
    private static final String TAG_ITEMS = "items";
    private static final String TAG_KEY = "key";
    private static final String TAG_VALUE = "value";
    private static final String TAG_SCENARIOS = "scenarios";
    private static final String TAG_SCENARIO_KEY = "scenario_key";
    private static final String TAG_HAS_DIRECT_SOURCE = "has_direct_source";
    private static final String TAG_SOURCE_CHILD_TABLES = "source_child_tables";

    private static final String STATE_UNKNOWN = "unknown";
    private static final String STATE_MEASURED = "measured";
    private static final String TAG_STATE = "state";
    private static final String TAG_LOWER = "lower";
    private static final String TAG_UPPER = "upper";

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

    // 从 NBT 加载；格式版本不兼容时整体按缓存未命中处理
    private static LootProbabilityData load(CompoundTag tag, HolderLookup.Provider registries) {
        LootProbabilityData data = new LootProbabilityData();
        if (!tag.contains(TAG_FORMAT_VERSION, Tag.TAG_INT)) {
            LOGGER.info("概率缓存缺少 format_version（旧格式），本轮按缓存未命中重建");
            return data;
        }
        int version = tag.getInt(TAG_FORMAT_VERSION);
        if (version != FORMAT_VERSION) {
            LOGGER.info("概率缓存格式版本 {} 与当前 {} 不符，本轮按缓存未命中重建", version, FORMAT_VERSION);
            return data;
        }

        ListTag entriesTag = tag.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
        int skipped = 0;
        for (int i = 0; i < entriesTag.size(); i++) {
            CompoundTag entryTag = entriesTag.getCompound(i);
            ResourceLocation tableId = ResourceLocation.tryParse(entryTag.getString(TAG_TABLE_ID));
            if (tableId == null) {
                skipped++;
                continue;
            }

            Map<String, CachedItemProbability> probabilities = readItemProbabilities(entryTag);
            if (probabilities == null) {
                // 单表条目不完整：整表按缓存未命中跳过，其它表照常恢复
                skipped++;
                continue;
            }
            data.entries.put(tableId, new TableProbabilityEntry(entryTag.getString(TAG_HASH), probabilities));
        }
        if (skipped > 0) {
            LOGGER.info("概率缓存有 {} 个表条目不完整，已按缓存未命中跳过", skipped);
        }
        LOGGER.debug("从存档加载了 {} 个战利品概率表数据", data.entries.size());
        return data;
    }

    // 读取单表的全部条目概率；任一条目概率无法解析时返回 null 表示该表整体不可用
    @Nullable
    private static Map<String, CachedItemProbability> readItemProbabilities(CompoundTag entryTag) {
        ListTag itemsTag = entryTag.getList(TAG_ITEMS, Tag.TAG_COMPOUND);
        Map<String, CachedItemProbability> probabilities = new LinkedHashMap<>();
        for (int j = 0; j < itemsTag.size(); j++) {
            CompoundTag itemTag = itemsTag.getCompound(j);
            String key = itemTag.getString(TAG_KEY);
            if (key.isEmpty()) {
                return null;
            }
            SimulatedValue probability = readSimulatedValue(itemTag);
            if (probability == null) {
                return null;
            }

            ListTag scenariosTag = itemTag.getList(TAG_SCENARIOS, Tag.TAG_COMPOUND);
            Map<String, SimulatedValue> scenarios = new LinkedHashMap<>();
            for (int k = 0; k < scenariosTag.size(); k++) {
                CompoundTag scenarioTag = scenariosTag.getCompound(k);
                SimulatedValue scenarioProbability = readSimulatedValue(scenarioTag);
                if (scenarioProbability == null) {
                    return null;
                }
                scenarios.put(scenarioTag.getString(TAG_SCENARIO_KEY), scenarioProbability);
            }

            ListTag sourceTags = itemTag.getList(TAG_SOURCE_CHILD_TABLES, Tag.TAG_STRING);
            List<ResourceLocation> sourceChildTables = new ArrayList<>();
            for (int k = 0; k < sourceTags.size(); k++) {
                ResourceLocation source = ResourceLocation.tryParse(sourceTags.getString(k));
                if (source != null) {
                    sourceChildTables.add(source);
                }
            }
            probabilities.put(key, new CachedItemProbability(
                    probability, scenarios, itemTag.getBoolean(TAG_HAS_DIRECT_SOURCE),
                    sourceChildTables));
        }
        return probabilities;
    }

    // 严格读取测量值：非 CompoundTag（含旧版 StringTag）一律返回 null，不猜测、不转换
    @Nullable
    private static SimulatedValue readSimulatedValue(CompoundTag parent) {
        if (!parent.contains(TAG_VALUE, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag value = parent.getCompound(TAG_VALUE);
        if (!value.contains(TAG_STATE, Tag.TAG_STRING)) {
            return null;
        }
        String state = value.getString(TAG_STATE);
        try {
            return switch (state) {
                case STATE_UNKNOWN -> SimulatedValue.Unknown.INSTANCE;
                case STATE_MEASURED -> {
                    if (!value.contains(TAG_LOWER, Tag.TAG_DOUBLE)) {
                        yield null;
                    }
                    double lower = value.getDouble(TAG_LOWER);
                    OptionalDouble upper = value.contains(TAG_UPPER, Tag.TAG_DOUBLE)
                            ? OptionalDouble.of(value.getDouble(TAG_UPPER))
                            : OptionalDouble.empty();
                    yield new SimulatedValue.Measured(lower, upper);
                }
                default -> null;
            };
        } catch (IllegalArgumentException exception) {
            // 数值越界/非有限：视为该表缓存未命中，而不是把非法值交给下游
            LOGGER.debug("概率缓存中的数值非法（{}），按缓存未命中处理", exception.getMessage());
            return null;
        }
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        tag.putInt(TAG_FORMAT_VERSION, FORMAT_VERSION);

        ListTag entriesTag = new ListTag();
        for (Map.Entry<ResourceLocation, TableProbabilityEntry> entry : entries.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString(TAG_TABLE_ID, entry.getKey().toString());
            entryTag.putString(TAG_HASH, entry.getValue().hash());

            ListTag itemsTag = new ListTag();
            for (Map.Entry<String, CachedItemProbability> probEntry : entry.getValue().probabilities().entrySet()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putString(TAG_KEY, probEntry.getKey());
                itemTag.put(TAG_VALUE, writeSimulatedValue(probEntry.getValue().probability()));

                ListTag scenariosTag = new ListTag();
                for (Map.Entry<String, SimulatedValue> scenario : probEntry.getValue().scenarioProbabilities().entrySet()) {
                    CompoundTag scenarioTag = new CompoundTag();
                    scenarioTag.putString(TAG_SCENARIO_KEY, scenario.getKey());
                    scenarioTag.put(TAG_VALUE, writeSimulatedValue(scenario.getValue()));
                    scenariosTag.add(scenarioTag);
                }
                itemTag.put(TAG_SCENARIOS, scenariosTag);
                itemTag.putBoolean(TAG_HAS_DIRECT_SOURCE, probEntry.getValue().hasDirectSource());
                ListTag sourceTags = new ListTag();
                for (ResourceLocation source : probEntry.getValue().sourceChildTables()) {
                    sourceTags.add(StringTag.valueOf(source.toString()));
                }
                itemTag.put(TAG_SOURCE_CHILD_TABLES, sourceTags);
                itemsTag.add(itemTag);
            }
            entryTag.put(TAG_ITEMS, itemsTag);
            entriesTag.add(entryTag);
        }
        tag.put(TAG_ENTRIES, entriesTag);
        return tag;
    }

    // 只写测量事实：Unknown 与 Measured 两态，派生结论（不可达 / 需要条件）绝不落盘
    private static CompoundTag writeSimulatedValue(SimulatedValue value) {
        CompoundTag tag = new CompoundTag();
        switch (value) {
            case SimulatedValue.Unknown ignored -> tag.putString(TAG_STATE, STATE_UNKNOWN);
            case SimulatedValue.Measured measured -> {
                tag.putString(TAG_STATE, STATE_MEASURED);
                tag.putDouble(TAG_LOWER, measured.lower());
                measured.upper().ifPresent(upper -> tag.putDouble(TAG_UPPER, upper));
            }
        }
        return tag;
    }

    // 判断某个表的 hash 是否变化，需要重新模拟
    public boolean needsResimulation(ResourceLocation tableId, String currentHash) {
        TableProbabilityEntry entry = entries.get(tableId);
        if (entry == null) return true;
        return !entry.hash().equals(currentHash);
    }

    // 保存某个表的模拟结果
    public void putSimulationResult(ResourceLocation tableId, String hash,
                                    Map<String, CachedItemProbability> probabilities) {
        entries.put(tableId, new TableProbabilityEntry(hash, probabilities));
        setDirty();
    }

    // 获取某个表的全部概率映射
    public Map<String, CachedItemProbability> getProbabilities(ResourceLocation tableId) {
        TableProbabilityEntry entry = entries.get(tableId);
        if (entry == null) return Collections.emptyMap();
        return Collections.unmodifiableMap(entry.probabilities());
    }

    // 某个表的数据是否存在
    public boolean hasData(ResourceLocation tableId) {
        return entries.containsKey(tableId);
    }

    // 清空全部概率缓存（强制下次加载时重新模拟所有表）
    public void clear() {
        entries.clear();
        setDirty();
    }

    /** 单个战利品表的概率数据条目 */
    public record TableProbabilityEntry(String hash, Map<String, CachedItemProbability> probabilities) {
        public TableProbabilityEntry {
            probabilities = Collections.unmodifiableMap(new LinkedHashMap<>(probabilities));
        }
    }

    /** 单个签名的测量值与分场景测量值——只有"算没算、算出多少"，没有派生结论。 */
    public record CachedItemProbability(SimulatedValue probability,
                                        Map<String, SimulatedValue> scenarioProbabilities,
                                        boolean hasDirectSource,
                                        List<ResourceLocation> sourceChildTables) {
        public CachedItemProbability {
            scenarioProbabilities = Collections.unmodifiableMap(new LinkedHashMap<>(scenarioProbabilities));
            sourceChildTables = List.copyOf(sourceChildTables);
        }
    }
}
