package com.meteorite.unsuspiciousblock.loottable.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meteorite.unsuspiciousblock.loottable.source.LootTableSourceSnapshot;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 战利品表编译器——把单张表的 JSON 编译成上下文无关的 {@link CompiledLootTable}。
 * <p>
 * 编译只走本表自己的 JSON：遇到 {@code loot_table} 引用记录一个
 * {@link CompiledLootTable.ReferenceSite} 就停下，不跟随展开；遇到 {@code item} / {@code tag}
 * 记录物品路径，其中 item tag 在编译期展开为具体物品（签名按具体物品生成，存档缓存也按
 * 具体签名索引，因此 tag 展开结果必须进入编译产物，见规划 §3.4）。
 * <p>
 * 遍历结构（pool → entry → 组合 entry 子节点）与条件/函数链的累积次序与解析路径逐条对齐：
 * 条件链自外向内追加、函数链自外向内 prepend，任何顺序变化都会改变获取路径展示与签名。
 * 组合型条目（{@code group} / {@code alternatives} / {@code sequence}）只把自身条件并入继承链、
 * 不并入自身函数，这与既有解析行为一致。
 */
public final class LootTableCompiler {
    private static final Logger LOGGER = LogUtils.getLogger();

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
        warnUnhandledConditions(tableId, element, new LinkedHashSet<>());
        CompileState state = new CompileState();
        state.referencedEnchantments.addAll(collectEnchantments(element));
        walkNode(element, state);
        return new CompiledLootTable(tableId, declaredType(element), state.events,
                state.referencedEnchantments, collectToolReferences(element));
    }

    // 每次编译只按“表 + 类型”告警一次；重载重新编译时可以再次发现问题。
    private static void warnUnhandledConditions(ResourceLocation tableId, JsonElement element,
                                                Set<ResourceLocation> warnedTypes) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                warnUnhandledConditions(tableId, child, warnedTypes);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement condition = object.get("condition");
        if (condition != null && condition.isJsonPrimitive() && condition.getAsJsonPrimitive().isString()) {
            ResourceLocation id = ResourceLocation.tryParse(condition.getAsString());
            if (id != null && LootConditionHandlers.get(id) == null && warnedTypes.add(id)) {
                LOGGER.warn("战利品表 {} 含未识别条件类型 {}；条件说明将降级，检查对应模组或数据包", tableId, id);
            }
        }
        for (var member : object.entrySet()) {
            warnUnhandledConditions(tableId, member.getValue(), warnedTypes);
        }
    }

    /**
     * 收集本表 {@code match_tool} 谓词允许的基座物品（物品 id → 谓词原文）。
     * <p>
     * 为什么不从解析出的 {@code ItemPredicate} 里取：{@code LootConditionInfo} 只携带人类可读描述，
     * 不含结构化的物品集合；而编译期已经拿着整份 JSON，扫一遍字段就是最直接的来源，也与
     * {@link #collectEnchantments} 同型。
     * <p>
     * 三条限制（都在提示里如实呈现，不做猜测）：
     * <ul>
     *   <li>标签谓词只取**首个成员**（决策 45）——这是数据包成员顺序决定的实现偶然，
     *       因此下拉必然不是谓词允许的全集；</li>
     *   <li>谓词里的组件、数量、子谓词约束不参与筛选，只影响"下拉里选它是否真的匹配"；</li>
     *   <li>平台运行时注入的条件不在 JSON 里，因此扫不到。</li>
     * </ul>
     */
    private static Map<ResourceLocation, String> collectToolReferences(JsonElement element) {
        LinkedHashMap<ResourceLocation, String> tools = new LinkedHashMap<>();
        collectToolReferences(element, tools);
        return tools;
    }

    private static void collectToolReferences(JsonElement element, Map<ResourceLocation, String> output) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectToolReferences(child, output);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        if (isMatchTool(object)) {
            JsonElement predicate = object.get("predicate");
            if (predicate != null && !predicate.isJsonNull()) {
                collectPredicateItems(predicate, predicate.toString(), output);
            }
        }
        for (Map.Entry<String, JsonElement> child : object.entrySet()) {
            collectToolReferences(child.getValue(), output);
        }
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

    /**
     * 收集本表 JSON 里被引用到的附魔。
     * <p>
     * 判定键就是 JSON 字段名 {@code enchantment}：读附魔的五个机制
     * （{@code apply_bonus} / {@code table_bonus} / {@code tool_enchantment} /
     * {@code enchanted_count_increase} / {@code random_chance_with_enchanted_bonus}）
     * 都用这个字段指名附魔，而 {@code set_enchantments} / {@code enchant_randomly} 用的是复数
     * {@code enchantments} 映射，不会被误收。等级数值在 1.21.1 的 JSON 里根本不存在
     * （等级是函数/条件运行时从 TOOL 读的输入，见决策 26），因此这里只收 id。
     */
    private static Set<ResourceLocation> collectEnchantments(JsonElement element) {
        LinkedHashSet<ResourceLocation> enchantments = new LinkedHashSet<>();
        collectEnchantments(element, enchantments);
        return enchantments;
    }

    private static void collectEnchantments(JsonElement element, Set<ResourceLocation> output) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectEnchantments(child, output);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement enchantment = object.get("enchantment");
        if (enchantment != null && enchantment.isJsonPrimitive()
                && enchantment.getAsJsonPrimitive().isString()) {
            ResourceLocation id = ResourceLocation.tryParse(enchantment.getAsString());
            if (id != null) {
                output.add(id);
            }
        }
        for (Map.Entry<String, JsonElement> child : object.entrySet()) {
            collectEnchantments(child.getValue(), output);
        }
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
        /** 当前事件自身的 weight/quality 是否参与选择；group/sequence 的子节点不参与（见 walkChildren…）。 */
        boolean weightApplies = true;
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

    private boolean walkArrayIfPresent(JsonObject object, String key, CompileState state) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            return false;
        }
        walkArray(object.getAsJsonArray(key), state);
        return true;
    }

    private void walkEntry(JsonObject object, CompileState state) {
        String type = LootParseUtil.getString(object, "type", "");
        List<LootConditionInfo> entryConditions = LootParseUtil.parseConditions(object, this.lootOps);

        switch (LootParseUtil.normalizeType(type)) {
            case "item" -> recordItem(object, state, entryConditions);
            case "tag" -> recordTag(object, state, entryConditions);
            case "loot_table" -> recordReferenceSite(object, state, entryConditions);
            case "group", "alternatives", "sequence" ->
                    walkChildrenWithInheritedConditions(object, state, entryConditions,
                            !"alternatives".equals(LootParseUtil.normalizeType(type)));
            default -> {
                List<LootConditionInfo> previousConditions = state.inheritedConditions;
                try {
                    state.inheritedConditions = LootParseUtil.appendConditions(previousConditions, entryConditions);
                    if (!walkArrayIfPresent(object, "children", state)) {
                        walkArrayIfPresent(object, "entries", state);
                    }
                } finally {
                    state.inheritedConditions = previousConditions;
                }
            }
        }
    }

    // 组合型条目：只把自身条件并入继承链，子节点用 children 字段。
    // 子节点是否参与"权重竞争"取决于组合类型：alternatives 按权重选一个，group/sequence 则全部产出，
    // 因此后者的子节点权重根本不参与选择——把它当成门槛会在门槛分析里把可达条目误判为不可达。
    private void walkChildrenWithInheritedConditions(JsonObject object, CompileState state,
                                                      List<LootConditionInfo> entryConditions,
                                                      boolean weightIgnored) {
        List<LootConditionInfo> previousConditions = state.inheritedConditions;
        boolean previousWeightApplies = state.weightApplies;
        try {
            state.inheritedConditions = LootParseUtil.appendConditions(previousConditions, entryConditions);
            state.weightApplies = previousWeightApplies && !weightIgnored;
            walkArrayIfPresent(object, "children", state);
        } finally {
            state.inheritedConditions = previousConditions;
            state.weightApplies = previousWeightApplies;
        }
    }

    private void recordItem(JsonObject object, CompileState state, List<LootConditionInfo> entryConditions) {
        ResourceLocation itemId = ResourceLocation.tryParse(LootParseUtil.getString(object, "name", ""));
        if (itemId == null || BuiltInRegistries.ITEM.get(itemId) == Items.AIR) {
            return;
        }
        state.events.add(new CompiledLootTable.ItemPath(itemId, null, entryConditions,
                ownFunctions(object), state.inheritedConditions, state.inheritedFunctions, state.luckAffected,
                luckSpec(object, state)));
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
                    luckSpec));
        }
    }

    // 引用位置只记录不跟随；目标缺失、成环与排除集判定都由投影层按图处理
    private void recordReferenceSite(JsonObject object, CompileState state,
                                      List<LootConditionInfo> entryConditions) {
        String rawId = object.has("value")
                ? object.get("value").getAsString()
                : LootParseUtil.getString(object, "name", "");
        ResourceLocation target = ResourceLocation.tryParse(rawId);
        if (target == null) {
            LOGGER.warn("loot_table 引用缺少 value/name 字段，跳过展开");
            return;
        }
        state.events.add(new CompiledLootTable.ReferenceSite(target, entryConditions, ownFunctions(object),
                state.inheritedConditions, state.inheritedFunctions, state.luckAffected,
                luckSpec(object, state)));
    }

    // 条目自身权重/品质 + 所在池的抽取次数——门槛算法所需的全部静态输入（决策 34）。
    // 权重不参与选择时（group/sequence 的子节点）按原版缺省处理：那里的 weight/quality 不构成门槛，
    // 照抄数值会让门槛分析把"组内必然产出"的条目误判为需要幸运或不可达。
    private static LuckSpec luckSpec(JsonObject entry, CompileState state) {
        if (!state.weightApplies) {
            return new LuckSpec(1, 0, state.poolRolls, state.poolBonusRolls);
        }
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
