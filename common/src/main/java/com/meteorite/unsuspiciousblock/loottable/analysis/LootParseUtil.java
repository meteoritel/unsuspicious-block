package com.meteorite.unsuspiciousblock.loottable.analysis;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DynamicOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 战利品表 JSON 解析公共工具方法——消除 {@link LootConditionHandlers}、
 * {@link LootFunctionHandlers} 与战利品表编译/投影各层中重复的类型名规范化、
 * JSON 字段读取、条件数组分析与函数链拼接逻辑。
 * <p>
 * 条件分析与函数链拼接必须只有一份实现：编译产物与展开产物要产出逐位一致的
 * {@link LootConditionInfo}（含场景指纹元数据）与函数顺序，否则签名与目录哈希会漂移。
 */
public final class LootParseUtil {
    private static final Logger LOGGER = LogUtils.getLogger();

    private LootParseUtil() {
    }

    /**
     * 规范化战利品表条件/函数的类型名，去除命名空间前缀。
     * 例：{@code "minecraft:random_chance"} → {@code "random_chance"}
     */
    public static String normalizeType(String type) {
        if (type == null || type.isEmpty()) {
            return "";
        }
        int colonIndex = type.indexOf(':');
        return colonIndex >= 0 ? type.substring(colonIndex + 1) : type;
    }

    /**
     * 从 JSON 对象中读取字符串字段，缺失时返回 fallback。
     */
    public static String getString(JsonObject object, String key, String fallback) {
        return object.has(key) ? object.get(key).getAsString() : fallback;
    }

    /**
     * 从 JSON 对象中解析 conditions 数组，返回可读的条件信息列表。
     * 解析失败的条件记一条告警并降级为 fallback 描述，不中断整张表的解析。
     */
    public static List<LootConditionInfo> parseConditions(JsonObject object, DynamicOps<JsonElement> lootOps) {
        if (!object.has("conditions") || !object.get("conditions").isJsonArray()) {
            return List.of();
        }
        List<LootItemCondition> parsedConditions = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray("conditions")) {
            var result = LootItemCondition.DIRECT_CODEC.parse(lootOps, element);
            result.result().ifPresentOrElse(
                    parsedConditions::add,
                    () -> {
                        ResourceLocation conditionId = extractTypeId(element, "condition");
                        String conditionType = conditionId != null ? conditionId.toString() : "<unknown>";
                        LOGGER.warn("解析战利品条件失败: condition={}, error={}",
                                conditionType, result.error().map(Object::toString).orElse("unknown"));
                        parsedConditions.add(null);
                    });
        }
        List<LootConditionInfo> conditions = new ArrayList<>();
        int parsedIndex = 0;
        for (JsonElement element : object.getAsJsonArray("conditions")) {
            LootItemCondition condition = parsedConditions.get(parsedIndex++);
            if (condition != null) {
                conditions.addAll(LootConditionHandlers.analyzeAll(List.of(condition)));
            } else {
                conditions.add(LootConditionHandlers.fallbackInfo(extractTypeId(element, "condition")));
            }
        }
        return conditions;
    }

    /**
     * 读取数字提供器 JSON 的**常量值**；无法静态求值时返回 {@code null}。
     * <p>
     * 识别三种可静态求值的形态：数字字面量、{@code constant} 提供器、以及
     * {@code uniform} 两端相等。其余形态（依分数、依等级、两端不等的 {@code uniform} 等）
     * 一律返回 {@code null}——"未知"与"零"在幸运门槛判定里是两件不同的事：
     * 前者降级为"区间受限"，后者会断言"没有额外抽取"。
     * <p>
     * 只被 {@link LuckSpec} 的采集使用，不参与条件/函数分析。
     */
    @Nullable
    public static Double knownNumber(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsDouble();
        }
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        return switch (normalizeType(getString(object, "type", ""))) {
            case "constant" -> knownNumber(object.get("value"));
            case "uniform" -> {
                Double min = knownNumber(object.get("min"));
                Double max = knownNumber(object.get("max"));
                yield min != null && min.equals(max) ? min : null;
            }
            default -> null;
        };
    }

    /**
     * 从 JSON 元素中读取指定字段的类型 id，字段缺失或非字符串时返回 {@code null}。
     */
    @Nullable
    public static ResourceLocation extractTypeId(JsonElement element, String fieldName) {
        if (!element.isJsonObject()) {
            return null;
        }
        JsonElement typeElement = element.getAsJsonObject().get(fieldName);
        if (typeElement == null || !typeElement.isJsonPrimitive()
                || !typeElement.getAsJsonPrimitive().isString()) {
            return null;
        }
        return ResourceLocation.tryParse(typeElement.getAsString());
    }

    /**
     * 把新增条件追加到已继承的条件链之后，返回新的不可变列表。
     * <p>
     * 遍历方向是"由外到内"，每层把自己追加一次，因此最终顺序是
     * <b>最外层在前、最内层在后</b>；{@code LootAcquisitionPath.allConditions()} 的
     * 展示顺序依赖这个次序，不可改动。新增为空时原样返回，避免无谓复制。
     */
    public static List<LootConditionInfo> appendConditions(List<LootConditionInfo> inherited,
                                                           List<LootConditionInfo> added) {
        if (added.isEmpty()) {
            return inherited;
        }
        List<LootConditionInfo> merged = new ArrayList<>(inherited.size() + added.size());
        merged.addAll(inherited);
        merged.addAll(added);
        return List.copyOf(merged);
    }

    /**
     * 把 JSON 对象自身的 functions 拼接到已继承的函数链之前。
     * <p>
     * 遍历方向是"由外到内"，每层把自己 prepend 一次，因此最终顺序是
     * <b>最内层在前、最外层在后</b>；函数按该顺序依次作用到物品上，不可改动。
     */
    public static List<JsonElement> prependFunctions(List<JsonElement> inheritedFunctions, JsonObject object) {
        if (!object.has("functions") || !object.get("functions").isJsonArray()) {
            return inheritedFunctions;
        }
        List<JsonElement> functions = new ArrayList<>(
                inheritedFunctions.size() + object.getAsJsonArray("functions").size());
        object.getAsJsonArray("functions").forEach(functions::add);
        functions.addAll(inheritedFunctions);
        return List.copyOf(functions);
    }
}
