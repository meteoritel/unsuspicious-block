package com.meteorite.unsuspiciousblock.world;

import com.meteorite.unsuspiciousblock.loottable.catalog.SimulatedValue;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationInputKey;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * 战利品概率数据持久化——按表存储内容哈希、表级发现记录与**按输入**的测量值。
 * <p>
 * 存储格式（{@code format_version = 4}）：
 * <pre>
 * root tag:
 *   "format_version" -> int
 *   "entries" -> ListTag, 每个 CompoundTag 包含:
 *     "table_id"  -> String (ResourceLocation)
 *     "hash"      -> String (SHA-256 hex)
 *     "discovery" -> CompoundTag: "items" -> ListTag of {
 *                       "key" -> String (signature storedKey),
 *                       "has_direct_source" -> boolean,
 *                       "source_child_tables" -> ListTag&lt;StringTag&gt; }
 *     "inputs"    -> ListTag of {
 *                       "input_key" -> String,
 *                       "sample_count" -> int,
 *                       "items"    -> ListTag of { "key" -> String, "value" -> valueTag },
 *                       "children" -> ListTag of { "table_id" -> String, "value" -> valueTag } }
 *
 * 测量值 valueTag（{@link SimulatedValue}，只表达"算没算、算出多少"）:
 *   { "state": "unknown" }
 *   { "state": "measured", "lower": double [, "upper": double] }
 * </pre>
 * 三条分层规则是本格式的重点（决策 37/46）：
 * <ol>
 *   <li><b>发现记录挂表级、LRU 不淘汰</b>：动态条目（GLM / LootTableEvents.MODIFY 注入）没有静态路径，
 *       它的"直接来源 / 来源子表"只能从模拟观测里得到。若把它放进会被淘汰的测量值里，
 *       淘汰一次就会让这批条目在重启后**从网格里消失**——那是信息丢失，不是缓存失效。
 *       因此它是"发现过什么"的持久事实，与"算出了多少"分开存。</li>
 *   <li><b>测量值按输入键索引</b>：键是 {@code (表哈希, inputKey)}（表哈希在条目级）。
 *       于是"同一张表换个幸运值"是新增一条，而不是覆盖旧的一条。</li>
 *   <li><b>LRU 按参数组合计数</b>：每个参数组合（不含抽样次数）最多保留全部次数档位，
 *       参数组合数上限 {@link #MAX_PARAMETER_COMBINATIONS}，实际条目 ≤ 8 × 档位数。
 *       理由是换参数是换**问题**，换次数是换**答案的精度**——高精度答案不该把问题本身挤出缓存。</li>
 * </ol>
 * 兼容策略：**不做数值迁移**。根缺少 {@code format_version}、版本不匹配、概率仍是旧的
 * {@code StringTag}（注意这不是"字段缺失"，必须显式按类型判定），或单个表条目无法解析时，
 * 一律把对应表视为<b>缓存未命中</b>——既不报错中断，也不把类型不匹配解成 0。
 */
public final class LootProbabilityData extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "unsuspiciousblock_loot_probability";
    /** 概率存储格式版本；旧文件没有该字段，因此"缺失"即视为不兼容。 */
    private static final int FORMAT_VERSION = 4;
    /**
     * 每表最多保留的参数组合数（决策 46，可调实现参数）。
     * 抽样次数的档位不占用这个额度：同一个"问题"的不同精度一起保留。
     */
    private static final int MAX_PARAMETER_COMBINATIONS = 8;

    private static final String TAG_FORMAT_VERSION = "format_version";
    private static final String TAG_ENTRIES = "entries";
    private static final String TAG_TABLE_ID = "table_id";
    private static final String TAG_HASH = "hash";
    private static final String TAG_DISCOVERY = "discovery";
    private static final String TAG_DISCOVERY_ITEMS = "items";
    private static final String TAG_KEY = "key";
    private static final String TAG_VALUE = "value";
    private static final String TAG_HAS_DIRECT_SOURCE = "has_direct_source";
    private static final String TAG_SOURCE_CHILD_TABLES = "source_child_tables";
    private static final String TAG_INPUTS = "inputs";
    private static final String TAG_INPUT_KEY = "input_key";
    private static final String TAG_SAMPLE_COUNT = "sample_count";
    private static final String TAG_ITEMS = "items";
    private static final String TAG_CHILDREN = "children";

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

    // tableId -> 该表的哈希、发现记录与按输入索引的测量值
    private final Map<ResourceLocation, TableProbabilityEntry> entries = new LinkedHashMap<>();

    public static LootProbabilityData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    private LootProbabilityData() {
    }

    // ==================== 读取 ====================

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
            Map<String, DiscoveryRecord> discovery = readDiscovery(entryTag);
            Map<String, InputMeasurement> inputs = readInputs(entryTag);
            if (discovery == null || inputs == null) {
                // 单表条目不完整：整表按缓存未命中跳过，其它表照常恢复
                skipped++;
                continue;
            }
            data.entries.put(tableId, new TableProbabilityEntry(entryTag.getString(TAG_HASH),
                    discovery, inputs));
        }
        if (skipped > 0) {
            LOGGER.info("概率缓存有 {} 个表条目不完整，已按缓存未命中跳过", skipped);
        }
        LOGGER.debug("从存档加载了 {} 个战利品概率表数据", data.entries.size());
        return data;
    }

    // 任一条目无法解析时返回 null 表示该表整体不可用
    @Nullable
    private static Map<String, DiscoveryRecord> readDiscovery(CompoundTag entryTag) {
        if (!entryTag.contains(TAG_DISCOVERY, Tag.TAG_COMPOUND)) {
            return null;
        }
        ListTag itemsTag = entryTag.getCompound(TAG_DISCOVERY).getList(TAG_DISCOVERY_ITEMS, Tag.TAG_COMPOUND);
        Map<String, DiscoveryRecord> discovery = new LinkedHashMap<>();
        for (int i = 0; i < itemsTag.size(); i++) {
            CompoundTag itemTag = itemsTag.getCompound(i);
            String key = itemTag.getString(TAG_KEY);
            if (key.isEmpty()) {
                return null;
            }
            ListTag sourceTags = itemTag.getList(TAG_SOURCE_CHILD_TABLES, Tag.TAG_STRING);
            List<ResourceLocation> sources = new ArrayList<>(sourceTags.size());
            for (int k = 0; k < sourceTags.size(); k++) {
                ResourceLocation source = ResourceLocation.tryParse(sourceTags.getString(k));
                if (source != null) {
                    sources.add(source);
                }
            }
            discovery.put(key, new DiscoveryRecord(itemTag.getBoolean(TAG_HAS_DIRECT_SOURCE), sources));
        }
        return discovery;
    }

    @Nullable
    private static Map<String, InputMeasurement> readInputs(CompoundTag entryTag) {
        ListTag inputsTag = entryTag.getList(TAG_INPUTS, Tag.TAG_COMPOUND);
        Map<String, InputMeasurement> inputs = new LinkedHashMap<>();
        for (int i = 0; i < inputsTag.size(); i++) {
            CompoundTag inputTag = inputsTag.getCompound(i);
            String inputKey = inputTag.getString(TAG_INPUT_KEY);
            if (inputKey.isEmpty()) {
                return null;
            }
            Map<String, SimulatedValue> items = readValueMap(inputTag.getList(TAG_ITEMS, Tag.TAG_COMPOUND));
            if (items == null) {
                return null;
            }
            Map<ResourceLocation, SimulatedValue> children = new LinkedHashMap<>();
            ListTag childrenTag = inputTag.getList(TAG_CHILDREN, Tag.TAG_COMPOUND);
            for (int k = 0; k < childrenTag.size(); k++) {
                CompoundTag childTag = childrenTag.getCompound(k);
                ResourceLocation childId = ResourceLocation.tryParse(childTag.getString(TAG_TABLE_ID));
                SimulatedValue value = readSimulatedValue(childTag);
                if (childId == null || value == null) {
                    return null;
                }
                children.put(childId, value);
            }
            inputs.put(inputKey, new InputMeasurement(inputTag.getInt(TAG_SAMPLE_COUNT), items, children));
        }
        return inputs;
    }

    // 条目列表里每项形如 { "key": String, "value": valueTag }
    @Nullable
    private static Map<String, SimulatedValue> readValueMap(ListTag listTag) {
        Map<String, SimulatedValue> values = new LinkedHashMap<>();
        for (int i = 0; i < listTag.size(); i++) {
            CompoundTag itemTag = listTag.getCompound(i);
            String key = itemTag.getString(TAG_KEY);
            SimulatedValue value = readSimulatedValue(itemTag);
            if (key.isEmpty() || value == null) {
                return null;
            }
            values.put(key, value);
        }
        return values;
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

    // ==================== 写入 ====================

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        tag.putInt(TAG_FORMAT_VERSION, FORMAT_VERSION);

        ListTag entriesTag = new ListTag();
        synchronized (this) {
            for (Map.Entry<ResourceLocation, TableProbabilityEntry> entry : entries.entrySet()) {
                CompoundTag entryTag = new CompoundTag();
                entryTag.putString(TAG_TABLE_ID, entry.getKey().toString());
                entryTag.putString(TAG_HASH, entry.getValue().hash());
                entryTag.put(TAG_DISCOVERY, writeDiscovery(entry.getValue().discovery()));
                entryTag.put(TAG_INPUTS, writeInputs(entry.getValue().inputs()));
                entriesTag.add(entryTag);
            }
        }
        tag.put(TAG_ENTRIES, entriesTag);
        return tag;
    }

    private static CompoundTag writeDiscovery(Map<String, DiscoveryRecord> discovery) {
        ListTag itemsTag = new ListTag();
        for (Map.Entry<String, DiscoveryRecord> item : discovery.entrySet()) {
            CompoundTag itemTag = new CompoundTag();
            itemTag.putString(TAG_KEY, item.getKey());
            itemTag.putBoolean(TAG_HAS_DIRECT_SOURCE, item.getValue().hasDirectSource());
            ListTag sourceTags = new ListTag();
            for (ResourceLocation source : item.getValue().sourceChildTables()) {
                sourceTags.add(StringTag.valueOf(source.toString()));
            }
            itemTag.put(TAG_SOURCE_CHILD_TABLES, sourceTags);
            itemsTag.add(itemTag);
        }
        CompoundTag discoveryTag = new CompoundTag();
        discoveryTag.put(TAG_DISCOVERY_ITEMS, itemsTag);
        return discoveryTag;
    }

    private static ListTag writeInputs(Map<String, InputMeasurement> inputs) {
        ListTag inputsTag = new ListTag();
        for (Map.Entry<String, InputMeasurement> input : inputs.entrySet()) {
            InputMeasurement measurement = input.getValue();
            CompoundTag inputTag = new CompoundTag();
            inputTag.putString(TAG_INPUT_KEY, input.getKey());
            inputTag.putInt(TAG_SAMPLE_COUNT, measurement.sampleCount());
            inputTag.put(TAG_ITEMS, writeItems(measurement.items()));
            ListTag childrenTag = new ListTag();
            for (Map.Entry<ResourceLocation, SimulatedValue> child : measurement.children().entrySet()) {
                CompoundTag childTag = new CompoundTag();
                childTag.putString(TAG_TABLE_ID, child.getKey().toString());
                childTag.put(TAG_VALUE, writeSimulatedValue(child.getValue()));
                childrenTag.add(childTag);
            }
            inputTag.put(TAG_CHILDREN, childrenTag);
            inputsTag.add(inputTag);
        }
        return inputsTag;
    }

    private static ListTag writeItems(Map<String, SimulatedValue> items) {
        ListTag itemsTag = new ListTag();
        for (Map.Entry<String, SimulatedValue> item : items.entrySet()) {
            CompoundTag itemTag = new CompoundTag();
            itemTag.putString(TAG_KEY, item.getKey());
            itemTag.put(TAG_VALUE, writeSimulatedValue(item.getValue()));
            itemsTag.add(itemTag);
        }
        return itemsTag;
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

    // ==================== 访问接口 ====================

    // 判断某个表的 hash 是否变化，需要重新模拟
    public synchronized boolean needsResimulation(ResourceLocation tableId, String currentHash) {
        TableProbabilityEntry entry = entries.get(tableId);
        if (entry == null) return true;
        return !entry.hash().equals(currentHash);
    }

    /** 表级发现记录（表哈希变化时为空——旧记录属于旧内容，不能混用）。 */
    public synchronized Map<String, DiscoveryRecord> getDiscovery(ResourceLocation tableId) {
        TableProbabilityEntry entry = entries.get(tableId);
        return entry == null ? Map.of() : Collections.unmodifiableMap(entry.discovery());
    }

    /**
     * 取某个输入的测量值；未缓存时返回 {@code null}。
     * <p>
     * 命中会把该条目移动到 LRU 的最近端（{@link LinkedHashMap} 的访问顺序），
     * 从而在下次淘汰时保护刚被用过的参数组合。
     */
    @Nullable
    public synchronized InputMeasurement getMeasurement(ResourceLocation tableId, String inputKey) {
        TableProbabilityEntry entry = entries.get(tableId);
        if (entry == null) {
            return null;
        }
        return entry.inputs().get(inputKey);
    }

    /**
     * 写入一次模拟的结果：表哈希、表级发现记录与该输入的测量值。
     * <p>
     * 发现记录按"只增不减"合并：同一次模拟只会发现新的动态签名，旧记录在哈希未变时依然有效，
     * 且它不受 LRU 淘汰影响（见类注释）。
     */
    public synchronized void putMeasurement(ResourceLocation tableId, String hash, String inputKey,
                                            InputMeasurement measurement,
                                            Map<String, DiscoveryRecord> discoveredNow) {
        TableProbabilityEntry existing = entries.get(tableId);
        boolean sameContent = existing != null && existing.hash().equals(hash);
        Map<String, DiscoveryRecord> discovery = new LinkedHashMap<>();
        if (sameContent) {
            discovery.putAll(existing.discovery());
        }
        discovery.putAll(discoveredNow);

        Map<String, InputMeasurement> inputs = new LinkedHashMap<>();
        if (sameContent) {
            inputs.putAll(existing.inputs());
        }
        inputs.put(inputKey, measurement);
        evictBeyondParameterCombinationLimit(inputs);

        entries.put(tableId, new TableProbabilityEntry(hash, discovery, inputs));
        setDirty();
    }

    /**
     * 按**参数组合**淘汰（决策 46）：换参数是换问题，换抽样次数是换答案的精度。
     * <p>
     * 因此额度按"去掉 {@code n=} 那一段之后的键"计算，同一组合的不同次数档位整体保留。
     * 被淘汰的是**最久未使用**的那个组合（{@link LinkedHashMap} 按访问顺序迭代，最旧在前）。
     */
    private static void evictBeyondParameterCombinationLimit(Map<String, InputMeasurement> inputs) {
        while (distinctParameterGroups(inputs) > MAX_PARAMETER_COMBINATIONS) {
            String oldestGroup = null;
            for (String inputKey : inputs.keySet()) {
                oldestGroup = SimulationInputKey.parameterGroup(inputKey);
                break;
            }
            if (oldestGroup == null) {
                return;
            }
            final String group = oldestGroup;
            inputs.keySet().removeIf(key -> group.equals(SimulationInputKey.parameterGroup(key)));
        }
    }

    private static int distinctParameterGroups(Map<String, InputMeasurement> inputs) {
        Set<String> groups = new HashSet<>();
        for (String inputKey : inputs.keySet()) {
            groups.add(SimulationInputKey.parameterGroup(inputKey));
        }
        return groups.size();
    }

    // 清空全部概率缓存（强制下次加载时重新模拟所有表）
    public synchronized void clear() {
        entries.clear();
        setDirty();
    }

    /** 单个战利品表的概率数据条目：哈希 + 表级发现记录 + 按输入索引的测量值。 */
    public record TableProbabilityEntry(String hash, Map<String, DiscoveryRecord> discovery,
                                        Map<String, InputMeasurement> inputs) {
        public TableProbabilityEntry {
            // 访问顺序 = LRU 顺序（最旧在前）：getMeasurement 的命中会把条目移到最近端
            Map<String, InputMeasurement> ordered = new LinkedHashMap<>(16, 0.75F, true);
            ordered.putAll(inputs);
            inputs = ordered;
            discovery = Collections.unmodifiableMap(new LinkedHashMap<>(discovery));
        }
    }

    /**
     * 动态条目的发现事实——没有静态获取路径的条目靠它在下一次启动后仍然留在网格里。
     * 挂表级、LRU 不淘汰（决策 37）。
     */
    public record DiscoveryRecord(boolean hasDirectSource, List<ResourceLocation> sourceChildTables) {
        public DiscoveryRecord {
            sourceChildTables = List.copyOf(sourceChildTables);
        }
    }

    /** 单个输入下的测量值——只有"算没算、算出多少"，没有派生结论。 */
    public record InputMeasurement(int sampleCount, Map<String, SimulatedValue> items,
                                   Map<ResourceLocation, SimulatedValue> children) {
        public InputMeasurement {
            items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
            children = Collections.unmodifiableMap(new LinkedHashMap<>(children));
        }
    }
}
