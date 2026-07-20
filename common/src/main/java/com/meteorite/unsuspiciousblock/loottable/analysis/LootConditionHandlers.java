package com.meteorite.unsuspiciousblock.loottable.analysis;

import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.EntitySubPredicate;
import net.minecraft.advancements.critereon.FishingHookPredicate;
import net.minecraft.advancements.critereon.LocationPredicate;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.storage.loot.predicates.AllOfCondition;
import net.minecraft.world.level.storage.loot.predicates.AnyOfCondition;
import net.minecraft.world.level.storage.loot.predicates.ConditionReference;
import net.minecraft.world.level.storage.loot.predicates.InvertedLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LocationCheck;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemEntityPropertyCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceWithEnchantedBonusCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 战利品条件处理器注册表——管理所有已知 loot condition 的静态分析逻辑。
 * <p>
 * 内建全部原版 1.21.1 的 19 种 loot condition 处理器。
 * 外部模组可通过 {@link #register} 注册自定义条件的处理器。
 * 设计模式与 {@link LootFunctionHandlers} 一致。
 */
public final class LootConditionHandlers {
    private static final Map<ResourceLocation, LootConditionHandler> REGISTRY = new LinkedHashMap<>();
    private static final String I18N_PREFIX = "screen.unsuspiciousblock.archaeology_journal.condition.";
    private static final String I18N_FALLBACK_PREFIX = "screen.unsuspiciousblock.archaeology_journal.condition.";

    static {
        // 类别 A：概率型条件
        register("random_chance", new RandomChanceHandler());
        register("random_chance_with_enchanted_bonus", new RandomChanceWithEnchantedBonusHandler());

        // 类别 B：可静态描述，无概率值
        register("survives_explosion", new SurvivesExplosionHandler());
        register("match_tool", simpleDesc("match_tool", false));
        register("block_state_property", simpleDesc("block_state_property", false));
        register("location_check", new LocationCheckHandler());
        register("time_check", simpleDesc("time_check", false));
        register("value_check", simpleDesc("value_check", false));
        register("weather_check", simpleDesc("weather_check", true));
        register("table_bonus", simpleDesc("table_bonus", true));
        register("enchantment_active_check", simpleDesc("enchantment_active_check", true));

        // 类别 C：纯运行时
        register("entity_properties", new EntityPropertiesHandler());
        register("killed_by_player", runtimeDesc("killed_by_player"));
        register("entity_scores", runtimeDesc("entity_scores"));
        register("damage_source_properties", runtimeDesc("damage_source_properties"));

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
     *
     * @param conditionPath 条件注册名（如 "random_chance"，不含 "minecraft:" 前缀）
     */
    public static void register(String conditionPath, LootConditionHandler handler) {
        REGISTRY.put(ResourceLocation.fromNamespaceAndPath("minecraft", conditionPath), handler);
    }

    /**
     * 以完整 ResourceLocation 注册自定义条件处理器。
     */
    public static void register(ResourceLocation conditionId, LootConditionHandler handler) {
        REGISTRY.put(conditionId, handler);
    }

    /**
     * 获取指定条件的处理器。
     *
     * @param conditionId 条件的完整注册表 key
     * @return 对应的 handler；未注册时返回 null
     */
    @Nullable
    public static LootConditionHandler get(ResourceLocation conditionId) {
        return REGISTRY.get(conditionId);
    }

    /**
     * 获取注册表只读视图。
     */
    public static Map<ResourceLocation, LootConditionHandler> registry() {
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
            LootConditionHandler.UncertaintyLevel level = uncertaintyLevelOf(info);
            if (level.ordinal() > maxLevel.ordinal()) {
                maxLevel = level;
            }
        }
        return maxLevel;
    }

    private static LootConditionHandler.UncertaintyLevel uncertaintyLevelOf(LootConditionInfo info) {
        LootConditionHandler handler = get(info.conditionType());
        LootConditionHandler.UncertaintyLevel level = handler != null
                ? handler.uncertaintyLevel()
                : LootConditionHandler.UncertaintyLevel.RUNTIME;
        for (LootConditionInfo child : info.children()) {
            LootConditionHandler.UncertaintyLevel childLevel = uncertaintyLevelOf(child);
            if (childLevel.ordinal() > level.ordinal()) {
                level = childLevel;
            }
        }
        return level;
    }

    /**
     * 批量分析入口：遍历已通过 Codec 解析的条件列表，对每个条件调用对应 handler 的 analyze。
     *
     * @param conditions 通过 {@link LootItemCondition#DIRECT_CODEC} 解析后的条件列表
     * @return 非空条件信息列表（纯运行时条件返回 null，不纳入结果）
     */
    public static List<LootConditionInfo> analyzeAll(List<LootItemCondition> conditions) {
        List<LootConditionInfo> results = new ArrayList<>();
        for (LootItemCondition condition : conditions) {
            if (condition == null) {
                results.add(fallbackInfo(null));
                continue;
            }
            ResourceLocation conditionId = BuiltInRegistries.LOOT_CONDITION_TYPE.getKey(condition.getType());
            LootConditionHandler handler = get(conditionId);
            if (handler != null) {
                LootConditionInfo info;
                try {
                    info = handler.analyze(condition);
                } catch (RuntimeException exception) {
                    info = fallbackInfo(conditionId);
                }
                if (info != null) {
                    results.add(info);
                } else {
                    results.add(fallbackInfo(conditionId));
                }
            } else if (conditionId != null) {
                // 两级 fallback：未知条件生成通用描述
                results.add(fallbackInfo(conditionId));
            }
        }
        return results;
    }

    // ==================== 共享工具方法 ====================

    /**
     * 判断给定的条件信息列表是否引入不确定性。
     */
    public static boolean hasAnyUncertainty(List<LootConditionInfo> conditions) {
        for (LootConditionInfo info : conditions) {
            LootConditionHandler handler = get(info.conditionType());
            if (handler == null || handler.addsUncertainty()) {
                return true;
            }
        }
        return false;
    }

    /** 为未知条件生成通用 fallback 描述 */
    static LootConditionInfo fallbackInfo(@Nullable ResourceLocation conditionId) {
        ResourceLocation resolvedId = conditionId != null
                ? conditionId
                : ResourceLocation.fromNamespaceAndPath("unsuspiciousblock", "unknown");
        return new LootConditionInfo(resolvedId,
                Component.translatable(I18N_FALLBACK_PREFIX + "unknown", resolvedId.toString()),
                null);
    }

    /** 根据条件对象推导其注册表 key */
    static ResourceLocation keyOf(LootItemCondition condition) {
        return BuiltInRegistries.LOOT_CONDITION_TYPE.getKey(condition.getType());
    }

    /**
     * 通过反射读取原版类的 package-private 字段。
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private static <T> T reflectField(Object obj) {
        Class<?> type = obj.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField("terms");
                field.setAccessible(true);
                return (T) field.get(obj);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return null;
            }
        }
        return null;
    }

    // ==================== 通用简单描述 handler 工厂 ====================

    private static LootConditionHandler simpleDesc(String i18nKey, boolean uncertain) {
        return new LootConditionHandler() {
            @Override
            public LootConditionInfo analyze(LootItemCondition condition) {
                return new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + i18nKey),
                        null);
            }

            @Override
            public boolean addsUncertainty() {
                return uncertain;
            }

            @Override
            public UncertaintyLevel uncertaintyLevel() {
                return uncertain ? UncertaintyLevel.PROBABILISTIC : UncertaintyLevel.NONE;
            }
        };
    }

    private static LootConditionHandler runtimeDesc(String i18nKey) {
        return new LootConditionHandler() {
            @Override
            public LootConditionInfo analyze(LootItemCondition condition) {
                return new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + i18nKey), null);
            }

            @Override
            public boolean addsUncertainty() {
                return true;
            }

            @Override
            public UncertaintyLevel uncertaintyLevel() {
                return UncertaintyLevel.RUNTIME;
            }
        };
    }

    // ==================== 类别 A：概率型条件 ====================

    /** 处理 random_chance：从 NumberProvider 提取常量概率值 */
    private static final class RandomChanceHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            float chance = 1.0f;
            if (condition instanceof LootItemRandomChanceCondition(NumberProvider chance1)) {
                if (chance1 instanceof ConstantValue(float value)) {
                    chance = value;
                }
            }
            return new LootConditionInfo(keyOf(condition),
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

    /** 处理 random_chance_with_enchanted_bonus：读取基础概率 */
    private static final class RandomChanceWithEnchantedBonusHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            float baseChance = 1.0f;
            if (condition instanceof LootItemRandomChanceWithEnchantedBonusCondition c) {
                baseChance = c.unenchantedChance();
            }
            return new LootConditionInfo(keyOf(condition),
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
        public LootConditionInfo analyze(LootItemCondition condition) {
            return new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "survives_explosion"),
                    null);
        }

        @Override
        public boolean addsUncertainty() {
            return false;
        }
    }

    /** 处理 location_check：解析 predicate.biomes 字段，展示群系名称 */
    private static final class LocationCheckHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            if (!(condition instanceof LocationCheck locationCheck)) {
                return null;
            }
            Optional<LocationPredicate> predicate = locationCheck.predicate();
            if (predicate.isEmpty()) {
                return new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + "location_check"), null);
            }
            LocationPredicate locPred = predicate.get();
            Optional<HolderSet<Biome>> biomes = locPred.biomes();
            if (biomes.isEmpty()) {
                return new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + "location_check"), null);
            }
            List<Component> biomeNames = extractBiomeNames(biomes.get());
            if (biomeNames.isEmpty()) {
                return new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + "location_check"), null);
            }
            Component joined = joinComponents(biomeNames);
            return new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "location_check_biomes", joined), null);
        }

        private List<Component> extractBiomeNames(HolderSet<Biome> biomeSet) {
            List<Component> names = new ArrayList<>();
            biomeSet.unwrap().ifLeft(tag -> {
                // 标签引用
                names.add(Component.literal("#" + tag.location()));
            }).ifRight(directHolders -> {
                for (Holder<Biome> holder : directHolders) {
                    holder.unwrapKey().ifPresent(key -> {
                        ResourceLocation rl = key.location();
                        names.add(Component.translatable("biome." + rl.getNamespace() + "." + rl.getPath()));
                    });
                }
            });
            return names;
        }

        private static Component joinComponents(List<Component> components) {
            if (components.isEmpty()) return Component.empty();
            Component result = components.getFirst();
            for (int i = 1; i < components.size(); i++) {
                result = Component.literal("").append(result).append(", ").append(components.get(i));
            }
            return result;
        }

        @Override
        public boolean addsUncertainty() {
            return false;
        }
    }

    // ==================== 类别 C：纯运行时 ====================

    /** 处理 entity_properties：解析 type_specific 中的已知谓词（如 fishing_hook 的 in_open_water） */
    private static final class EntityPropertiesHandler implements LootConditionHandler {
        @Override
        @Nullable
        public LootConditionInfo analyze(LootItemCondition condition) {
            if (!(condition instanceof LootItemEntityPropertyCondition entityCondition)) {
                return null;
            }
            Optional<EntityPredicate> predicate = entityCondition.predicate();
            if (predicate.isEmpty()) {
                return genericInfo(condition);
            }
            EntityPredicate entityPred = predicate.get();
            Optional<EntitySubPredicate> subPredicate = entityPred.subPredicate();
            if (subPredicate.isEmpty()) {
                return genericInfo(condition);
            }
            EntitySubPredicate sub = subPredicate.get();
            // 使用 instanceof 判断具体子谓词类型，读取对应字段
            if (sub instanceof FishingHookPredicate fishingHook) {
                String key;
                if (fishingHook.inOpenWater().isPresent()) {
                    key = fishingHook.inOpenWater().get()
                            ? "entity_properties_fishing_open_water"
                            : "entity_properties_fishing_not_open_water";
                } else {
                    key = "entity_properties_fishing";
                }
                return new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + key), null);
            }
            return genericInfo(condition);
        }

        private LootConditionInfo genericInfo(LootItemCondition condition) {
            return new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "entity_properties"), null);
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
        public LootConditionInfo analyze(LootItemCondition condition) {
            if (!(condition instanceof InvertedLootItemCondition(LootItemCondition term))) {
                return null;
            }
            ResourceLocation childId = BuiltInRegistries.LOOT_CONDITION_TYPE.getKey(term.getType());
            LootConditionHandler childHandler = get(childId);
            if (childHandler == null) {
                LootConditionInfo childInfo = fallbackInfo(childId);
                return new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + "inverted", childInfo.description()),
                        null, List.of(childInfo));
            }
            LootConditionInfo childInfo = childHandler.analyze(term);
            if (childInfo == null) {
                childInfo = fallbackInfo(childId);
            }
            return new LootConditionInfo(keyOf(condition),
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
        public LootConditionInfo analyze(LootItemCondition condition) {
            if (!(condition instanceof AnyOfCondition anyOf)) {
                return null;
            }
            List<LootItemCondition> terms = reflectField(anyOf);
            if (terms == null) return null;
            List<LootConditionInfo> children = analyzeAll(terms);
            if (children.isEmpty()) {
                return null;
            }
            return new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "any_of"), null, children);
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
        public LootConditionInfo analyze(LootItemCondition condition) {
            if (!(condition instanceof AllOfCondition allOf)) {
                return null;
            }
            List<LootItemCondition> terms = reflectField(allOf);
            if (terms == null) return null;
            List<LootConditionInfo> children = analyzeAll(terms);
            if (children.isEmpty()) {
                return null;
            }
            return new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "all_of"), null, children);
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

    // ==================== 类别 E：引用 ====================

    /** 处理 reference：引用外部条件，无法静态解析 */
    private static final class ReferenceHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            String name = "?";
            if (condition instanceof ConditionReference(net.minecraft.resources.ResourceKey<LootItemCondition> name1)) {
                name = name1.location().toString();
            }
            return new LootConditionInfo(keyOf(condition),
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
