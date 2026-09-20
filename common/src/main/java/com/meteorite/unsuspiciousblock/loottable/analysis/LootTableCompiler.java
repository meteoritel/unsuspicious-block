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
        CompileState state = new CompileState();
        walkNode(element, state);
        return new CompiledLootTable(tableId, declaredType(element), state.events);
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
        List<LootConditionInfo> inheritedConditions = List.of();
        List<JsonElement> inheritedFunctions = List.of();
        boolean luckAffected;
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
            try {
                state.luckAffected = previousLuckAffected
                        || mayBeNonZero(object.get("bonus_rolls"))
                        || hasQuality(object.getAsJsonArray("entries"));
                state.inheritedConditions = LootParseUtil.appendConditions(previousConditions, poolConditions);
                state.inheritedFunctions = LootParseUtil.prependFunctions(previousFunctions, object);
                walkArray(object.getAsJsonArray("entries"), state);
            } finally {
                state.inheritedConditions = previousConditions;
                state.inheritedFunctions = previousFunctions;
                state.luckAffected = previousLuckAffected;
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
                    walkChildrenWithInheritedConditions(object, state, entryConditions);
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

    // 组合型条目：只把自身条件并入继承链，子节点用 children 字段
    private void walkChildrenWithInheritedConditions(JsonObject object, CompileState state,
                                                      List<LootConditionInfo> entryConditions) {
        List<LootConditionInfo> previousConditions = state.inheritedConditions;
        try {
            state.inheritedConditions = LootParseUtil.appendConditions(previousConditions, entryConditions);
            walkArrayIfPresent(object, "children", state);
        } finally {
            state.inheritedConditions = previousConditions;
        }
    }

    private void recordItem(JsonObject object, CompileState state, List<LootConditionInfo> entryConditions) {
        ResourceLocation itemId = ResourceLocation.tryParse(LootParseUtil.getString(object, "name", ""));
        if (itemId == null || BuiltInRegistries.ITEM.get(itemId) == Items.AIR) {
            return;
        }
        state.events.add(new CompiledLootTable.ItemPath(itemId, null, entryConditions,
                ownFunctions(object), state.inheritedConditions, state.inheritedFunctions, state.luckAffected));
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
        for (ResourceLocation itemId : itemIds) {
            state.events.add(new CompiledLootTable.ItemPath(itemId, tagId, entryConditions,
                    ownFunctions, state.inheritedConditions, state.inheritedFunctions, state.luckAffected));
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
                state.inheritedConditions, state.inheritedFunctions, state.luckAffected));
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
