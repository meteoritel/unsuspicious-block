package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootParseUtil;
import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.storage.loot.LootTable;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模拟专用战利品表工厂 -- 从 ResourceManager 读取表原始 JSON，
 * 递归剥离依赖世界实时状态/填充默认值而无法在模拟中正常判定的条件，
 * 再用 {@link LootTable#DIRECT_CODEC} 解析出仅用于概率模拟的表副本。
 * <p>
 * 被剥离的条件在模拟中视为恒通过（理论概率），包括：
 * <ul>
 *   <li>世界实时状态依赖：location_check（群系/结构/维度）、weather_check、time_check</li>
 *   <li>受填充默认值影响：match_tool、block_state_property、damage_source_properties、
 *       entity_properties、entity_scores</li>
 *   <li>缺参数会抛异常：enchantment_active_check（getParam 在 ENCHANTMENT_ACTIVE 缺失时抛异常）</li>
 * </ul>
 * 同时递归处理复合条件（all_of/any_of/inverted）与 condition_reference 引用内联（含环保护）。
 * 任一环节失败返回 null，由调用方回退到注册表原始表。
 */
public final class SimulationTableFactory {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FileToIdConverter LOOT_TABLES = FileToIdConverter.json("loot_table");
    private static final FileToIdConverter PREDICATES = FileToIdConverter.json("predicates");

    // 需要在模拟中强制通过（即从 JSON 中剥离）的条件类型名（已去命名空间）
    private static final Set<String> FORCE_PASS_CONDITIONS = Set.of(
            "location_check", "weather_check", "time_check",
            "match_tool", "block_state_property", "damage_source_properties",
            "entity_properties", "entity_scores", "enchantment_active_check");

    private SimulationTableFactory() {
    }

    /**
     * 构建模拟专用战利品表。
     *
     * @return 剥离特判条件后的表；JSON 缺失或解析失败时返回 null（调用方回退注册表表）
     */
    public static LootTable buildForSimulation(ResourceLocation tableId, ResourceManager resourceManager,
                                               HolderLookup.Provider registries) {
        try {
            JsonElement root;
            try (BufferedReader reader = resourceManager
                    .getResourceOrThrow(LOOT_TABLES.idToFile(tableId)).openAsReader()) {
                root = JsonParser.parseReader(reader);
            }
            transformInPlace(root, resourceManager, new HashSet<>());
            var result = LootTable.DIRECT_CODEC
                    .parse(RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, registries), root)
                    .result();
            if (result.isEmpty()) {
                LOGGER.warn("模拟表 JSON 解码失败，回退注册表：{}", tableId);
                return null;
            }
            return result.get();
        } catch (Exception e) {
            LOGGER.warn("构建模拟专用表 {} 失败，回退注册表", tableId, e);
            return null;
        }
    }

    // 递归遍历表 JSON，处理所有 "conditions" 数组（pools/entries/子表等位置）
    private static void transformInPlace(JsonElement element, ResourceManager resourceManager,
                                         Set<ResourceLocation> refStack) {
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                transformInPlace(child, resourceManager, refStack);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject obj = element.getAsJsonObject();
        JsonElement conditions = obj.get("conditions");
        if (conditions != null && conditions.isJsonArray()) {
            JsonArray kept = new JsonArray();
            for (JsonElement condition : conditions.getAsJsonArray()) {
                JsonElement transformed = transformCondition(condition, resourceManager, refStack);
                if (transformed != null) {
                    kept.add(transformed);
                }
            }
            if (kept.isEmpty()) {
                obj.remove("conditions");
            } else {
                obj.add("conditions", kept);
            }
        }
        // 递归其余字段（pools/entries/children/functions 等）；transformCondition 已处理过的嵌套条件幂等
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            transformInPlace(entry.getValue(), resourceManager, refStack);
        }
    }

    /**
     * 转换单个条件对象。
     *
     * @return 转换后的条件；null 表示该条件应从父数组移除（模拟中视为恒通过）
     */
    private static JsonElement transformCondition(JsonElement condition, ResourceManager resourceManager,
                                                  Set<ResourceLocation> refStack) {
        if (!condition.isJsonObject()) {
            return condition;
        }
        JsonObject obj = condition.getAsJsonObject();
        String type = LootParseUtil.normalizeType(LootParseUtil.getString(obj, "condition", ""));

        if (FORCE_PASS_CONDITIONS.contains(type)) {
            return null;
        }
        switch (type) {
            case "condition_reference" -> {
                // 内联引用的 predicate JSON 后递归转换；不可解析时保留原样（运行时按原逻辑判定）
                JsonElement inlined = inlineConditionReference(obj, resourceManager, refStack);
                return inlined != null ? transformCondition(inlined, resourceManager, refStack) : condition;
            }
            case "any_of" -> {
                // 任一子条件恒通过则整体恒通过 -> 直接移除整个条件
                JsonArray terms = obj.getAsJsonArray("terms");
                if (terms != null) {
                    for (JsonElement term : terms) {
                        if (transformCondition(term, resourceManager, refStack) == null) {
                            return null;
                        }
                    }
                }
                return condition;
            }
            case "all_of" -> {
                // 剥离恒通过的子条件；全部被剥离则整体恒通过 -> 移除
                JsonArray terms = obj.getAsJsonArray("terms");
                if (terms == null) {
                    return condition;
                }
                List<JsonElement> kept = new ArrayList<>();
                for (JsonElement term : terms) {
                    JsonElement transformed = transformCondition(term, resourceManager, refStack);
                    if (transformed != null) {
                        kept.add(transformed);
                    }
                }
                if (kept.isEmpty()) {
                    return null;
                }
                JsonArray keptArray = new JsonArray();
                kept.forEach(keptArray::add);
                obj.add("terms", keptArray);
                return obj;
            }
            case "inverted" -> {
                // 内部条件恒通过时取反恒不通过 -> 需整体移除（视为恒通过）
                JsonElement term = obj.get("term");
                if (term != null && transformCondition(term, resourceManager, refStack) == null) {
                    return null;
                }
                return condition;
            }
        }
        return condition;
    }

    // 读取 condition_reference 引用的 predicate 资源；带环保护，失败返回 null
    private static JsonElement inlineConditionReference(JsonObject conditionObj, ResourceManager resourceManager,
                                                        Set<ResourceLocation> refStack) {
        JsonElement name = conditionObj.get("name");
        if (name == null || !name.isJsonPrimitive()) {
            return null;
        }
        ResourceLocation predicateId = ResourceLocation.tryParse(name.getAsString());
        if (predicateId == null || !refStack.add(predicateId)) {
            return null;
        }
        try (BufferedReader reader = resourceManager
                .getResourceOrThrow(PREDICATES.idToFile(predicateId)).openAsReader()) {
            return JsonParser.parseReader(reader);
        } catch (Exception e) {
            LOGGER.debug("内联 predicate {} 失败，保留原 condition_reference", predicateId, e);
            return null;
        } finally {
            refStack.remove(predicateId);
        }
    }
}
