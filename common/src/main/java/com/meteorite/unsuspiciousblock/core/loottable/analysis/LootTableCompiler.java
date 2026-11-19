package com.meteorite.unsuspiciousblock.core.loottable.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meteorite.unsuspiciousblock.core.loottable.source.LootTableSourceSnapshot;
import com.meteorite.unsuspiciousblock.Constants;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将单张表编译为带权重、条件及函数链的物品路径与具名引用。 */
public final class LootTableCompiler {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String NESTED_TABLE = "loot_table";
    private static final String CONDITION_KEY = "screen.unsuspiciousblock.archaeology_journal.condition.";
    private static final LootConditionInfo ALWAYS = conjunction(List.of());
    private static final LootConditionInfo NEVER = LootConditionInfo.branch(ConditionTree.Kind.ANY,
            ConditionTree.ANY, Component.translatable(CONDITION_KEY + "any_of"), List.of());

    private final DynamicOps<JsonElement> lootOps;

    public LootTableCompiler(HolderLookup.Provider registries) {
        this.lootOps = RegistryOps.create(JsonOps.INSTANCE, registries);
    }

    /**
     * 编译给定表集合。表缺失或 JSON 无法解析时跳过该表并告警，不影响其余表。
     */
    public Map<ResourceLocation, CompiledLootTable> compile(LootTableSourceSnapshot sourceSnapshot,
                                                            Set<ResourceLocation> tableIds) {
        Map<ResourceLocation, CompiledLootTable> compiled = new LinkedHashMap<>();
        for (ResourceLocation tableId : tableIds) {
            JsonElement element = sourceSnapshot.effectiveJson(tableId);
            if (element == null) {
                continue;
            }
            try {
                compiled.put(tableId, compileTable(tableId, element));
            } catch (RuntimeException exception) {
                LOGGER.warn("编译战利品表 {} 失败，跳过该表", tableId, exception);
            }
        }
        return compiled;
    }

    /** 编译单张表；表声明的 {@code type} 一并记入产物，供模拟上下文判定使用。 */
    public CompiledLootTable compileTable(ResourceLocation tableId, JsonElement element) {
        MetadataScan metadata = new MetadataScan();
        collectMetadata(element, metadata);
        for (ResourceLocation type : metadata.unknownConditions) {
            LOGGER.warn("战利品表 {} 含未识别条件类型 {}；条件说明将降级，检查对应模组或数据包", tableId, type);
        }
        CompileState state = new CompileState();
        state.referencedEnchantments.addAll(metadata.enchantments);
        walkNode(element, state);
        return new CompiledLootTable(tableId, declaredType(element), state.events,
                state.referencedEnchantments, metadata.tools);
    }

    /** 旁路扫描覆盖全部 JSON 子树，与持有继承条件/函数链的语义 walkNode 分开。 */
    private static final class MetadataScan {
        private final Set<ResourceLocation> enchantments = new LinkedHashSet<>();
        private final Map<ResourceLocation, String> tools = new LinkedHashMap<>();
        private final Set<ResourceLocation> unknownConditions = new LinkedHashSet<>();
    }

    private static void collectMetadata(JsonElement element, MetadataScan metadata) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collectMetadata(child, metadata));
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        ResourceLocation condition = typeId(object.get("condition"));
        if (condition != null && LootConditionHandlers.descriptor(condition) == null) {
            metadata.unknownConditions.add(condition);
        }
        ResourceLocation enchantment = typeId(object.get("enchantment"));
        if (enchantment != null) metadata.enchantments.add(enchantment);
        if (isMatchTool(object)) {
            JsonElement predicate = object.get("predicate");
            if (predicate != null && !predicate.isJsonNull()) {
                collectPredicateItems(predicate, predicate.toString(), metadata.tools);
            }
        }
        object.entrySet().forEach(entry -> collectMetadata(entry.getValue(), metadata));
    }

    private static ResourceLocation typeId(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? ResourceLocation.tryParse(value.getAsString()) : null;
    }

    // 检查是否引用 match_tool，包括没有可枚举物品的附魔或组件谓词。
    public static boolean hasMatchTool(JsonElement element) {
        if (element == null || element.isJsonNull()) return false;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) if (hasMatchTool(child)) return true;
        } else if (element.isJsonObject()) {
            if (isMatchTool(element.getAsJsonObject())) return true;
            for (var entry : element.getAsJsonObject().entrySet()) if (hasMatchTool(entry.getValue())) return true;
        }
        return false;
    }

    // 条件实体的类型字段在条件数组里叫 "condition"，在独立条件对象里也可能是 "type"
    private static boolean isMatchTool(JsonObject object) {
        for (String key : List.of("condition", "type")) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    && "match_tool".equals(LootParseUtil.normalizeType(value.getAsString()))) {
                return true;
            }
        }
        return false;
    }

    private static void collectPredicateItems(JsonElement predicate, String rawText,
                                              Map<ResourceLocation, String> output) {
        if (predicate == null || !predicate.isJsonObject()) {
            return;
        }
        JsonObject object = predicate.getAsJsonObject();
        collectItemSlot(object.get("items"), rawText, output);
        JsonElement nested = object.get("predicates");
        if (nested != null && nested.isJsonArray()) {
            for (JsonElement child : nested.getAsJsonArray()) {
                collectPredicateItems(child, rawText, output);
            }
        }
    }

    private static void collectItemSlot(JsonElement items, String rawText,
                                        Map<ResourceLocation, String> output) {
        if (items == null || items.isJsonNull()) {
            return;
        }
        if (items.isJsonArray()) {
            for (JsonElement child : items.getAsJsonArray()) {
                collectItemSlot(child, rawText, output);
            }
            return;
        }
        if (!items.isJsonPrimitive() || !items.getAsJsonPrimitive().isString()) {
            return;
        }
        String value = items.getAsString();
        if (value.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(value.substring(1));
            if (tagId == null) {
                return;
            }
            // 决策 45：只取标签首个成员；下拉不是全集，因此 ToolOption 必须同时携带谓词原文
            for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(
                    TagKey.create(Registries.ITEM, tagId))) {
                Item item = holder.value();
                if (item == Items.AIR) {
                    continue;
                }
                output.putIfAbsent(BuiltInRegistries.ITEM.getKey(item), rawText);
                break;
            }
            return;
        }
        ResourceLocation itemId = ResourceLocation.tryParse(value);
        if (itemId == null || BuiltInRegistries.ITEM.get(itemId) == Items.AIR) {
            return;
        }
        output.putIfAbsent(itemId, rawText);
    }

    // 读取表顶层声明的 type；缺失或非法时为空串
    private static String declaredType(JsonElement element) {
        if (!element.isJsonObject()) {
            return "";
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("type") && object.get("type").isJsonPrimitive()) {
            return object.get("type").getAsString();
        }
        return "";
    }

    // ==================== 编译遍历 ====================

    /** 遍历当前表时临时持有的继承上下文，各池处理后恢复。 */
    private static final class CompileState {
        final List<CompiledLootTable.Event> events = new ArrayList<>();
        /** 本表 JSON 引用到的附魔（id 去重、保持出现顺序）。 */
        final Set<ResourceLocation> referencedEnchantments = new LinkedHashSet<>();
        List<LootConditionInfo> inheritedConditions = List.of();
        List<JsonElement> inheritedFunctions = List.of();
        boolean luckAffected;
        List<LuckSpec> inheritedLuckSpecs = List.of();
        /** 当前池的 {@code rolls} 常量；{@code null} 表示动态（"未知"而不是零）。 */
        Double poolRolls = null;
        /** 当前池的 {@code bonus_rolls} 常量；{@code null} 表示动态。 */
        Double poolBonusRolls = null;
    }

    private void walkNode(JsonElement element, CompileState state) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                walkNode(child, state);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        if (object.has("pools") && object.get("pools").isJsonArray()) {
            List<JsonElement> previousFunctions = state.inheritedFunctions;
            try {
                state.inheritedFunctions = LootParseUtil.prependFunctions(previousFunctions, object);
                walkArray(object.getAsJsonArray("pools"), state);
            } finally {
                state.inheritedFunctions = previousFunctions;
            }
            return;
        }

        // 处理 entries：先捕获 pool 级别的 conditions，临时合并到继承链
        if (object.has("entries") && object.get("entries").isJsonArray()) {
            List<LootConditionInfo> poolConditions = LootParseUtil.parseConditions(object, this.lootOps);
            List<LootConditionInfo> previousConditions = state.inheritedConditions;
            List<JsonElement> previousFunctions = state.inheritedFunctions;
            boolean previousLuckAffected = state.luckAffected;
            Double previousRolls = state.poolRolls;
            Double previousBonusRolls = state.poolBonusRolls;
            try {
                state.luckAffected = previousLuckAffected
                        || mayBeNonZero(object.get("bonus_rolls"))
                        || hasQuality(object.getAsJsonArray("entries"));
                // 池级别的 rolls / bonus_rolls 是"幸运能不能改变抽取次数"的唯一入口，原样记下常量值。
                // bonus_rolls 缺省在原版是 constant 0（**不是**"未知"）——把它当未知会让每条路径都进门槛分析，
                // 也让"没有额外抽取"的陈述失去依据；写成 Double 而不是 double 是因为"有该字段但值动态"
                // 必须保留为 null（未装箱的 0.0 分支会把 null 拆箱成 NPE）。rolls 缺省则无效 JSON
                // （表根本不会加载），保守记未知。
                state.poolRolls = object.has("rolls") ? LootParseUtil.knownNumber(object.get("rolls")) : null;
                state.poolBonusRolls = object.has("bonus_rolls")
                        ? LootParseUtil.knownNumber(object.get("bonus_rolls")) : Double.valueOf(0.0);
                state.inheritedConditions = LootParseUtil.appendConditions(previousConditions, poolConditions);
                state.inheritedFunctions = LootParseUtil.prependFunctions(previousFunctions, object);
                walkArray(object.getAsJsonArray("entries"), state);
            } finally {
                state.inheritedConditions = previousConditions;
                state.inheritedFunctions = previousFunctions;
                state.luckAffected = previousLuckAffected;
                state.poolRolls = previousRolls;
                state.poolBonusRolls = previousBonusRolls;
            }
            return;
        }

        walkEntry(object, state);
    }

    private void walkArray(JsonArray array, CompileState state) {
        for (JsonElement child : array) {
            walkNode(child, state);
        }
    }

    // 任一候选权重随幸运变化，同池其他候选也受竞争影响；组合 entry 的叶节点同样参与。
    private static boolean hasQuality(JsonArray entries) {
        for (JsonElement entry : entries) {
            if (!entry.isJsonObject()) continue;
            JsonObject object = entry.getAsJsonObject();
            if (object.has("quality") && object.get("quality").getAsInt() != 0) return true;
            if (object.has("children") && object.get("children").isJsonArray()
                    && hasQuality(object.getAsJsonArray("children"))) return true;
        }
        return false;
    }

    // 能证明恒零的 bonus_rolls 不标记，其余 provider 保守视为可能影响幸运抽取。
    private static boolean mayBeNonZero(JsonElement value) {
        if (value == null || value.isJsonNull()) return false;
        if (value.isJsonPrimitive()) return value.getAsDouble() != 0.0;
        if (!value.isJsonObject()) return true;
        JsonObject object = value.getAsJsonObject();
        return switch (LootParseUtil.getString(object, "type", "")) {
            case "minecraft:constant", "constant" -> !object.has("value") || mayBeNonZero(object.get("value"));
            case "minecraft:uniform", "uniform" -> !object.has("min") || !object.has("max")
                    || mayBeNonZero(object.get("min")) || mayBeNonZero(object.get("max"));
            default -> true;
        };
    }

    // 返回条目是否成功展开。权重只在池选择候选时生效，不能控制组合短路。
    private LootConditionInfo walkEntry(JsonObject object, CompileState state) {
        String type = entryType(object);
        List<LootConditionInfo> conditions = LootParseUtil.parseConditions(object, this.lootOps);
        LootConditionInfo canRun = allOf(conditions);
        return switch (type) {
            case "item" -> {
                recordItem(object, state, conditions);
                yield canRun;
            }
            case "tag" -> {
                recordTag(object, state, conditions);
                yield canRun;
            }
            case "empty", "dynamic" -> canRun;
            case NESTED_TABLE -> {
                recordNestedTable(object, state, conditions);
                yield canRun;
            }
            case "group", "alternatives", "sequence" ->
                    and(canRun, walkChildren(object, state, conditions, type));
            default -> {
                if (object.has("children")) {
                    walkChildren(object, state, conditions, "group");
                }
                ResourceLocation id = ResourceLocation.tryParse(LootParseUtil.getString(object, "type", ""));
                yield and(canRun, new LootConditionInfo(id == null ? ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "unreadable_entry") : id,
                        Component.literal(type), null));
            }
        };
    }

    private LootConditionInfo walkChildren(JsonObject object, CompileState state,
            List<LootConditionInfo> conditions, String type) {
        JsonElement children = object.get("children");
        if (children == null || !children.isJsonArray()) {
            return "alternatives".equals(type) ? NEVER : ALWAYS;
        }

        JsonArray entries = children.getAsJsonArray();
        List<LootConditionInfo> outer = state.inheritedConditions;
        List<LootConditionInfo> inherited = LootParseUtil.appendConditions(outer, conditions);
        LootConditionInfo next = ALWAYS;
        try {
            for (JsonElement child : entries) {
                if (!child.isJsonObject() || next == NEVER) {
                    continue;
                }
                state.inheritedConditions = next == ALWAYS ? inherited
                        : LootParseUtil.appendConditions(inherited, List.of(next));
                LootConditionInfo succeeded = walkEntry(child.getAsJsonObject(), state);
                if (entries.size() == 1) {
                    return succeeded;
                }
                next = switch (type) {
                    case "alternatives" -> and(next, negate(succeeded));
                    case "sequence" -> and(next, succeeded);
                    default -> next;
                };
            }
        } finally {
            state.inheritedConditions = outer;
        }
        return "alternatives".equals(type) ? negate(next) : next;
    }

    private static LootConditionInfo allOf(List<LootConditionInfo> conditions) {
        return switch (conditions.size()) {
            case 0 -> ALWAYS;
            case 1 -> conditions.getFirst();
            default -> conjunction(conditions);
        };
    }

    private static LootConditionInfo conjunction(List<LootConditionInfo> terms) {
        return LootConditionInfo.branch(ConditionTree.Kind.ALL, ConditionTree.ALL,
                Component.translatable(CONDITION_KEY + "all_of"), terms);
    }

    private static LootConditionInfo and(LootConditionInfo first, LootConditionInfo second) {
        if (first == NEVER || second == NEVER) {
            return NEVER;
        }
        if (first == ALWAYS) {
            return second;
        }
        return second == ALWAYS ? first : conjunction(List.of(first, second));
    }

    private static LootConditionInfo negate(LootConditionInfo term) {
        if (term == ALWAYS || term == NEVER) {
            return term == ALWAYS ? NEVER : ALWAYS;
        }
        if (term.kind() == ConditionTree.Kind.NOT) {
            return term.logicChildren().getFirst();
        }
        return LootConditionInfo.branch(ConditionTree.Kind.NOT, ConditionTree.NOT,
                Component.translatable(CONDITION_KEY + "inverted", term.description()), List.of(term));
    }

    private void recordItem(JsonObject object, CompileState state, List<LootConditionInfo> entryConditions) {
        ResourceLocation itemId = ResourceLocation.tryParse(LootParseUtil.getString(object, "name", ""));
        if (itemId == null || BuiltInRegistries.ITEM.get(itemId) == Items.AIR) {
            return;
        }
        state.events.add(new CompiledLootTable.ItemPath(itemId, null, entryConditions,
                ownFunctions(object), state.inheritedConditions, state.inheritedFunctions, state.luckAffected,
                state.inheritedLuckSpecs, luckSpec(object, state)));
    }

    // 物品 tag 在编译期展开为具体物品；展开顺序与注册表遍历顺序一致
    private void recordTag(JsonObject object, CompileState state, List<LootConditionInfo> entryConditions) {
        ResourceLocation tagId = ResourceLocation.tryParse(LootParseUtil.getString(object, "name", ""));
        if (tagId == null) {
            return;
        }

        LinkedHashSet<ResourceLocation> itemIds = new LinkedHashSet<>();
        TagKey<Item> tagKey = TagKey.create(Registries.ITEM, tagId);
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tagKey)) {
            Item item = holder.value();
            if (item == Items.AIR) {
                continue;
            }
            itemIds.add(BuiltInRegistries.ITEM.getKey(item));
        }
        if (itemIds.isEmpty()) {
            return;
        }

        List<JsonElement> ownFunctions = ownFunctions(object);
        LuckSpec luckSpec = luckSpec(object, state);
        for (ResourceLocation itemId : itemIds) {
            state.events.add(new CompiledLootTable.ItemPath(itemId, tagId, entryConditions,
                    ownFunctions, state.inheritedConditions, state.inheritedFunctions, state.luckAffected,
                    state.inheritedLuckSpecs, luckSpec));
        }
    }

    private void recordNestedTable(JsonObject object, CompileState state, List<LootConditionInfo> conditions) {
        JsonElement value = nestedValue(object);
        if (value != null && value.isJsonObject()) {
            List<LootConditionInfo> outerConditions = state.inheritedConditions;
            List<JsonElement> outerFunctions = state.inheritedFunctions;
            List<LuckSpec> outerLuck = state.inheritedLuckSpecs;
            try {
                state.inheritedConditions = LootParseUtil.appendConditions(outerConditions, conditions);
                state.inheritedFunctions = LootParseUtil.prependFunctions(outerFunctions, object);
                List<LuckSpec> luck = new ArrayList<>(outerLuck);
                luck.add(luckSpec(object, state));
                state.inheritedLuckSpecs = List.copyOf(luck);
                walkNode(value, state);
            } finally {
                state.inheritedConditions = outerConditions;
                state.inheritedFunctions = outerFunctions;
                state.inheritedLuckSpecs = outerLuck;
            }
            return;
        }

        ResourceLocation target = namedReference(value);
        if (target != null) {
            state.events.add(new CompiledLootTable.ReferenceSite(target, conditions, ownFunctions(object),
                    state.inheritedConditions, state.inheritedFunctions, state.luckAffected,
                    state.inheritedLuckSpecs, luckSpec(object, state)));
        }
    }

    /** 按条目结构收集本表里的具名引用，包括内联表中的引用。 */
    public static Set<ResourceLocation> namedReferences(@Nullable JsonElement json) {
        Set<ResourceLocation> result = new LinkedHashSet<>();
        collectNamedReferences(json, result);
        return Collections.unmodifiableSet(result);
    }

    private static void collectNamedReferences(@Nullable JsonElement json, Set<ResourceLocation> result) {
        if (json == null) {
            return;
        }
        if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(entry -> collectNamedReferences(entry, result));
        } else if (json.isJsonObject()) {
            JsonObject object = json.getAsJsonObject();
            if (NESTED_TABLE.equals(entryType(object))) {
                JsonElement value = nestedValue(object);
                ResourceLocation name = namedReference(value);
                if (name == null) {
                    collectNamedReferences(value, result);
                } else {
                    result.add(name);
                }
            } else {
                collectNamedReferences(object.get("pools"), result);
                collectNamedReferences(object.get("entries"), result);
                collectNamedReferences(object.get("children"), result);
            }
        }
    }

    private static String entryType(JsonObject object) {
        return LootParseUtil.normalizeType(LootParseUtil.getString(object, "type", ""));
    }

    private static @Nullable JsonElement nestedValue(JsonObject entry) {
        return entry.has("value") ? entry.get("value") : entry.get("name");
    }

    private static @Nullable ResourceLocation namedReference(@Nullable JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? ResourceLocation.tryParse(value.getAsString()) : null;
    }

    private static LuckSpec luckSpec(JsonObject entry, CompileState state) {
        return new LuckSpec(intField(entry, "weight", 1), intField(entry, "quality", 0),
                state.poolRolls, state.poolBonusRolls);
    }

    // 读取可静态求值的整数字段；缺失或非数字时用原版缺省值
    private static int intField(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return value.getAsInt();
    }

    // 该 JSON 对象自身的 functions 列表；缺失时为空列表
    private static List<JsonElement> ownFunctions(JsonObject object) {
        if (!object.has("functions") || !object.get("functions").isJsonArray()) {
            return List.of();
        }
        JsonArray array = object.getAsJsonArray("functions");
        List<JsonElement> functions = new ArrayList<>(array.size());
        array.forEach(functions::add);
        return List.copyOf(functions);
    }
}
