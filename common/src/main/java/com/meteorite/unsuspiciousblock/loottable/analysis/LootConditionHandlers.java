package com.meteorite.unsuspiciousblock.loottable.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 战利品条件处理器注册表——管理所有已知 loot condition 的静态分析逻辑。
 * <p>
 * 内建全部原版 1.21.1 的 19 种 loot condition 处理器。
 * 外部模组可通过 {@link #register} 注册自定义条件的处理器。
 * 设计模式与 {@link LootFunctionHandlers} 一致。
 */
public final class LootConditionHandlers {
    private static final Map<String, LootConditionHandler> REGISTRY = new LinkedHashMap<>();
    private static final String I18N_PREFIX = "screen.unsuspiciousblock.archaeology_journal.condition.";

    static {
        // 类别 A：概率型条件
        register("random_chance", new RandomChanceHandler());
        register("random_chance_with_enchanted_bonus", new RandomChanceWithEnchantedBonusHandler());

        // 类别 B：可静态描述，无概率值
        register("survives_explosion", new SurvivesExplosionHandler());
        register("match_tool", new SimpleDescriptionHandler("match_tool", false));
        register("block_state_property", new SimpleDescriptionHandler("block_state_property", false));
        register("location_check", new SimpleDescriptionHandler("location_check", false));
        register("time_check", new SimpleDescriptionHandler("time_check", false));
        register("value_check", new SimpleDescriptionHandler("value_check", false));
        register("weather_check", new SimpleDescriptionHandler("weather_check", true));
        register("table_bonus", new SimpleDescriptionHandler("table_bonus", true));
        register("enchantment_active_check", new SimpleDescriptionHandler("enchantment_active_check", true));

        // 类别 C：纯运行时
        register("entity_properties", RuntimeOnlyHandler.INSTANCE);
        register("killed_by_player", RuntimeOnlyHandler.INSTANCE);
        register("entity_scores", RuntimeOnlyHandler.INSTANCE);
        register("damage_source_properties", RuntimeOnlyHandler.INSTANCE);

        // 类别 D：组合条件
        register("inverted", new InvertedHandler());
        register("any_of", new AnyOfHandler());
        register("all_of", new AllOfHandler());

        // 类别 E：引用
        register("reference", new ReferenceHandler());
    }

    private LootConditionHandlers() {
    }

    /**
     * 注册自定义条件处理器。若已存在同名 handler 则覆盖。
     */
    public static void register(String conditionName, LootConditionHandler handler) {
        REGISTRY.put(conditionName, handler);
    }

    /**
     * 获取指定条件的处理器。
     *
     * @param conditionName 已去除命名空间前缀的条件名（如 "weather_check"）
     * @return 对应的 handler；未注册时返回 null
     */
    @Nullable
    public static LootConditionHandler get(String conditionName) {
        return REGISTRY.get(conditionName);
    }

    /**
     * 获取注册表只读视图。
     */
    public static Map<String, LootConditionHandler> registry() {
        return Collections.unmodifiableMap(REGISTRY);
    }

    /**
     * 计算条件列表的整体不确定性等级。
     * 取所有条件中最高级别；若 hasUnknownFunction 为 true 则直接返回 RUNTIME。
     *
     * @param conditions         条件信息列表
     * @param hasUnknownFunction 是否有未知 function（无法静态求值）
     * @return 整体不确定性等级
     */
    public static LootConditionHandler.UncertaintyLevel computeUncertaintyLevel(
            List<LootConditionInfo> conditions, boolean hasUnknownFunction) {
        if (hasUnknownFunction) {
            return LootConditionHandler.UncertaintyLevel.RUNTIME;
        }
        LootConditionHandler.UncertaintyLevel maxLevel = LootConditionHandler.UncertaintyLevel.NONE;
        for (LootConditionInfo info : conditions) {
            LootConditionHandler handler = get(info.conditionType());
            if (handler != null) {
                LootConditionHandler.UncertaintyLevel level = handler.uncertaintyLevel();
                if (level.ordinal() > maxLevel.ordinal()) {
                    maxLevel = level;
                }
            }
        }
        return maxLevel;
    }

    /**
     * 批量分析入口：遍历 conditions 数组，对每个条件调用对应 handler 的 analyze。
     *
     * @param conditionsArray entry 的 "conditions" JSON 数组
     * @return 非空条件信息列表（纯运行时条件返回 null，不纳入结果）
     */
    public static List<LootConditionInfo> analyzeAll(JsonArray conditionsArray) {
        List<LootConditionInfo> results = new ArrayList<>();
        for (JsonElement element : conditionsArray) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject conditionJson = element.getAsJsonObject();
            String conditionType = LootParseUtil.normalizeType(LootParseUtil.getString(conditionJson, "condition", ""));
            LootConditionHandler handler = get(conditionType);
            if (handler != null) {
                LootConditionInfo info = handler.analyze(conditionJson);
                if (info != null) {
                    results.add(info);
                }
            }
        }
        return results;
    }

    // ==================== 共享工具方法 ====================

    /**
     * 判断给定的条件信息列表是否引入不确定性。
     * 遍历每个条件，通过注册表查找其 handler 的 addsUncertainty()。
     */
    public static boolean hasAnyUncertainty(List<LootConditionInfo> conditions) {
        for (LootConditionInfo info : conditions) {
            LootConditionHandler handler = get(info.conditionType());
            if (handler != null && handler.addsUncertainty()) {
                return true;
            }
        }
        return false;
    }

    // ==================== 类别 A：概率型条件 ====================

    /** 处理 random_chance：读取 chance 字段，设置概率值 */
    private static final class RandomChanceHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(JsonObject conditionJson) {
            float chance = 1.0f;
            if (conditionJson.has("chance") && conditionJson.get("chance").isJsonPrimitive()) {
                chance = conditionJson.get("chance").getAsFloat();
            }
            return new LootConditionInfo("random_chance",
                    Component.translatable(I18N_PREFIX + "random_chance", Math.round(chance * 100)),
                    chance);
        }

        @Override
        public boolean addsUncertainty() {
            return true;
        }

        @Override
        public UncertaintyLevel uncertaintyLevel() {
            return UncertaintyLevel.PROBABILISTIC;
        }
    }

    /** 处理 random_chance_with_enchanted_bonus：读取基础概率，附魔加成不可静态确定 */
    private static final class RandomChanceWithEnchantedBonusHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(JsonObject conditionJson) {
            float baseChance = 1.0f;
            if (conditionJson.has("unenchanted_chance") && conditionJson.get("unenchanted_chance").isJsonPrimitive()) {
                baseChance = conditionJson.get("unenchanted_chance").getAsFloat();
            } else if (conditionJson.has("chance") && conditionJson.get("chance").isJsonPrimitive()) {
                baseChance = conditionJson.get("chance").getAsFloat();
            }
            return new LootConditionInfo("random_chance_with_enchanted_bonus",
                    Component.translatable(I18N_PREFIX + "random_chance_with_enchanted_bonus",
                            Math.round(baseChance * 100)),
                    baseChance);
        }

        @Override
        public boolean addsUncertainty() {
            return true;
        }

        @Override
        public UncertaintyLevel uncertaintyLevel() {
            return UncertaintyLevel.PROBABILISTIC;
        }
    }

    // ==================== 类别 B：可静态描述，无概率值 ====================

    /** 处理 survives_explosion：考古模拟中爆炸半径为 0，始终满足 */
    private static final class SurvivesExplosionHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(JsonObject conditionJson) {
            return new LootConditionInfo("survives_explosion",
                    Component.translatable(I18N_PREFIX + "survives_explosion"),
                    null);
        }

        @Override
        public boolean addsUncertainty() {
            return false;
        }
    }

    /**
     * 通用简单描述 handler：仅提供本地化描述，不解析具体参数
     */
    private record SimpleDescriptionHandler(String key, boolean uncertain) implements LootConditionHandler {

        @Override
        public LootConditionInfo analyze(JsonObject conditionJson) {
            return new LootConditionInfo(this.key,
                    Component.translatable(I18N_PREFIX + this.key),
                    null);
        }

        @Override
        public boolean addsUncertainty() {
            return this.uncertain;
        }

        @Override
        public UncertaintyLevel uncertaintyLevel() {
            return this.uncertain ? UncertaintyLevel.PROBABILISTIC : UncertaintyLevel.NONE;
        }
    }

    // ==================== 类别 C：纯运行时 ====================

    /** 纯运行时条件：analyze 始终返回 null */
    private static final class RuntimeOnlyHandler implements LootConditionHandler {
        static final RuntimeOnlyHandler INSTANCE = new RuntimeOnlyHandler();

        @Override
        @Nullable
        public LootConditionInfo analyze(JsonObject conditionJson) {
            return null;
        }

        @Override
        public boolean addsUncertainty() {
            return true;
        }

        @Override
        public UncertaintyLevel uncertaintyLevel() {
            return UncertaintyLevel.RUNTIME;
        }
    }

    // ==================== 类别 D：组合条件 ====================

    /** 处理 inverted：递归分析 term 子条件 */
    private static final class InvertedHandler implements LootConditionHandler {
        @Override
        @Nullable
        public LootConditionInfo analyze(JsonObject conditionJson) {
            JsonElement termElement = conditionJson.get("term");
            if (termElement == null || !termElement.isJsonObject()) {
                return null;
            }
            JsonObject termObj = termElement.getAsJsonObject();
            String childType = LootParseUtil.normalizeType(LootParseUtil.getString(termObj, "condition", ""));
            LootConditionHandler childHandler = get(childType);
            if (childHandler == null) {
                return null;
            }
            LootConditionInfo childInfo = childHandler.analyze(termObj);
            if (childInfo == null) {
                return null;
            }
            return new LootConditionInfo("inverted",
                    Component.translatable(I18N_PREFIX + "inverted", childInfo.description()),
                    null,
                    List.of(childInfo));
        }

        @Override
        public boolean addsUncertainty() {
            return true;
        }

        @Override
        public UncertaintyLevel uncertaintyLevel() {
            return UncertaintyLevel.PROBABILISTIC;
        }
    }

    /** 处理 any_of：递归分析 terms 数组，OR 语义 */
    private static final class AnyOfHandler implements LootConditionHandler {
        @Override
        @Nullable
        public LootConditionInfo analyze(JsonObject conditionJson) {
            if (!conditionJson.has("terms") || !conditionJson.get("terms").isJsonArray()) {
                return null;
            }
            List<LootConditionInfo> children = analyzeAll(conditionJson.getAsJsonArray("terms"));
            if (children.isEmpty()) {
                return null;
            }
            Component desc = buildCompositeDescription("any_of", children);
            return new LootConditionInfo("any_of", desc, null, children);
        }

        @Override
        public boolean addsUncertainty() {
            return true;
        }

        @Override
        public UncertaintyLevel uncertaintyLevel() {
            return UncertaintyLevel.PROBABILISTIC;
        }
    }

    /** 处理 all_of：递归分析 terms 数组，AND 语义 */
    private static final class AllOfHandler implements LootConditionHandler {
        @Override
        @Nullable
        public LootConditionInfo analyze(JsonObject conditionJson) {
            if (!conditionJson.has("terms") || !conditionJson.get("terms").isJsonArray()) {
                return null;
            }
            List<LootConditionInfo> children = analyzeAll(conditionJson.getAsJsonArray("terms"));
            if (children.isEmpty()) {
                return null;
            }
            Component desc = buildCompositeDescription("all_of", children);
            return new LootConditionInfo("all_of", desc, null, children);
        }

        @Override
        public boolean addsUncertainty() {
            return true;
        }

        @Override
        public UncertaintyLevel uncertaintyLevel() {
            return UncertaintyLevel.PROBABILISTIC;
        }
    }

    /** 构建组合条件的描述文本 */
    private static Component buildCompositeDescription(String typeKey, List<LootConditionInfo> children) {
        Component header = Component.translatable(I18N_PREFIX + typeKey);
        // 将子条件描述拼接为 "X 且 Y 且 Z" 或 "X 或 Y 或 Z"
        String separator = typeKey.equals("any_of") ? "  " + Component.translatable(I18N_PREFIX + "or").getString() + " "
                : "  " + Component.translatable(I18N_PREFIX + "and").getString() + " ";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                builder.append(separator);
            }
            builder.append(children.get(i).description().getString());
        }
        return Component.literal(header.getString() + ": " + builder);
    }

    // ==================== 类别 E：引用 ====================

    /** 处理 reference：引用外部条件，无法静态解析 */
    private static final class ReferenceHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(JsonObject conditionJson) {
            String name = LootParseUtil.getString(conditionJson, "name", "?");
            return new LootConditionInfo("reference",
                    Component.translatable(I18N_PREFIX + "reference", name),
                    null);
        }

        @Override
        public boolean addsUncertainty() {
            return true;
        }

        @Override
        public UncertaintyLevel uncertaintyLevel() {
            return UncertaintyLevel.RUNTIME;
        }
    }
}