package com.meteorite.unsuspiciousblock.loottable.analysis;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationCompositeConditionAccess;
import com.meteorite.unsuspiciousblock.loottable.simulation.LootConditionFingerprint;
import com.mojang.serialization.JsonOps;
import net.minecraft.advancements.critereon.DamageSourcePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.EntitySubPredicate;
import net.minecraft.advancements.critereon.EntityTypePredicate;
import net.minecraft.advancements.critereon.FishingHookPredicate;
import net.minecraft.advancements.critereon.LocationPredicate;
import net.minecraft.advancements.critereon.StatePropertiesPredicate;
import net.minecraft.advancements.critereon.TagPredicate;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.loot.IntRange;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.AllOfCondition;
import net.minecraft.world.level.storage.loot.predicates.AnyOfCondition;
import net.minecraft.world.level.storage.loot.predicates.ConditionReference;
import net.minecraft.world.level.storage.loot.predicates.DamageSourceCondition;
import net.minecraft.world.level.storage.loot.predicates.EntityHasScoreCondition;
import net.minecraft.world.level.storage.loot.predicates.InvertedLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LocationCheck;
import net.minecraft.world.level.storage.loot.predicates.LootItemBlockStatePropertyCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemEntityPropertyCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceWithEnchantedBonusCondition;
import net.minecraft.world.level.storage.loot.predicates.MatchTool;
import net.minecraft.world.level.storage.loot.predicates.TimeCheck;
import net.minecraft.world.level.storage.loot.predicates.WeatherCheck;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import org.jetbrains.annotations.Nullable;

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
        register("match_tool", new MatchToolHandler());
        register("block_state_property", new BlockStatePropertyHandler());
        register("location_check", new LocationCheckHandler());
        register("time_check", new TimeCheckHandler());
        register("value_check", simpleDesc("value_check", false));
        register("weather_check", new WeatherCheckHandler());
        register("table_bonus", simpleDesc("table_bonus", true));
        register("enchantment_active_check", simpleDesc("enchantment_active_check", true));

        // 类别 C：纯运行时
        register("entity_properties", new EntityPropertiesHandler());
        register("killed_by_player", runtimeDesc("killed_by_player"));
        register("entity_scores", new EntityScoresHandler());
        register("damage_source_properties", new DamageSourcePropertiesHandler());

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
                    results.add(info.withMetadata(LootConditionFingerprint.METADATA_KEY,
                            LootConditionFingerprint.ofRaw(condition)));
                } else {
                    results.add(fallbackInfo(conditionId).withMetadata(
                            LootConditionFingerprint.METADATA_KEY,
                            LootConditionFingerprint.ofRaw(condition)));
                }
            } else if (conditionId != null) {
                // 两级 fallback：未知条件生成通用描述
                results.add(fallbackInfo(conditionId).withMetadata(
                        LootConditionFingerprint.METADATA_KEY,
                        LootConditionFingerprint.ofRaw(condition)));
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

    // 将 Component 列表拼成逗号分隔文本，保留每个元素自己的本地化信息
    private static Component joinComponents(List<Component> components) {
        if (components.isEmpty()) {
            return Component.empty();
        }
        MutableComponent result = Component.empty().append(components.getFirst());
        for (int i = 1; i < components.size(); i++) {
            result.append(", ").append(components.get(i));
        }
        return result;
    }

    // 用原版 Codec 提取 IntRange 的常量边界，动态 NumberProvider 回退为紧凑 JSON
    private static String describeRange(IntRange range) {
        JsonElement encoded = IntRange.CODEC.encodeStart(JsonOps.INSTANCE, range).result().orElse(null);
        if (encoded == null) {
            return "?";
        }
        if (encoded.isJsonPrimitive()) {
            return encoded.getAsString();
        }
        if (!encoded.isJsonObject()) {
            return encoded.toString();
        }
        JsonObject object = encoded.getAsJsonObject();
        JsonElement min = object.get("min");
        JsonElement max = object.get("max");
        String minText = compactJsonValue(min);
        String maxText = compactJsonValue(max);
        if (min != null && max != null) {
            return minText + " - " + maxText;
        }
        if (min != null) {
            return ">= " + minText;
        }
        if (max != null) {
            return "<= " + maxText;
        }
        return "*";
    }

    private static String compactJsonValue(@Nullable JsonElement element) {
        if (element == null) {
            return "?";
        }
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    private static String describeStateProperties(StatePropertiesPredicate properties) {
        JsonElement encoded = StatePropertiesPredicate.CODEC.encodeStart(JsonOps.INSTANCE, properties)
                .result().orElse(null);
        if (encoded == null || !encoded.isJsonObject()) {
            return encoded != null ? encoded.toString() : "?";
        }
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : encoded.getAsJsonObject().entrySet()) {
            values.add(entry.getKey() + "=" + compactJsonValue(entry.getValue()));
        }
        return String.join(", ", values);
    }

    private static Component entityTargetName(LootContext.EntityTarget target) {
        return Component.translatable(I18N_PREFIX + "entity_target." + target.getSerializedName());
    }

    /** 读取由窄 Mixin 接口暴露的组合条件子项。 */
    @Nullable
    @SuppressWarnings("unchecked")
    private static <T> T compositeTerms(Object obj) {
        return obj instanceof SimulationCompositeConditionAccess access
                ? (T) access.unsuspiciousblock$getTerms()
                : null;
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

    /** 处理 match_tool：优先展示物品或物品标签。 */
    private static final class MatchToolHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            if (!(condition instanceof MatchTool matchTool) || matchTool.predicate().isEmpty()) {
                return new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + "match_tool"), null);
            }
            Optional<HolderSet<Item>> items = matchTool.predicate().get().items();
            if (items.isEmpty()) {
                return new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + "match_tool"), null);
            }
            List<Component> names = new ArrayList<>();
            items.get().unwrap().ifLeft(tag -> names.add(Component.literal("#" + tag.location())));
            items.get().unwrap().ifRight(holders -> holders.forEach(holder ->
                    names.add(Component.translatable(holder.value().getDescriptionId()))));
            return new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "match_tool_items", joinComponents(names)), null);
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

    /** 处理 block_state_property：展示方块名及状态属性。 */
    private static final class BlockStatePropertyHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            if (!(condition instanceof LootItemBlockStatePropertyCondition blockCondition)) {
                return null;
            }
            List<LootConditionInfo> children = new ArrayList<>();
            blockCondition.properties().ifPresent(properties -> {
                children.add(new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + "block_state_properties",
                                describeStateProperties(properties)), null));
            });
            return new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "block_state_property_value",
                            blockCondition.block().value().getName()), null, children);
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

    /** 处理 location_check：展示群系、维度和结构约束。 */
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
            List<LootConditionInfo> children = new ArrayList<>();
            locPred.biomes().ifPresent(biomes -> {
                List<Component> biomeNames = extractBiomeNames(biomes);
                if (!biomeNames.isEmpty()) {
                    children.add(new LootConditionInfo(keyOf(condition),
                            Component.translatable(I18N_PREFIX + "location_check_biomes",
                                    joinComponents(biomeNames)), null));
                }
            });
            locPred.dimension().ifPresent(dimension -> children.add(new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "location_check_dimension",
                            dimension.location().getNamespace().equals("minecraft")
                                    ? Component.translatable("dimension.minecraft." + dimension.location().getPath())
                                    : Component.literal(dimension.location().toString())), null)));
            locPred.structures().ifPresent(structures -> {
                List<Component> structureNames = extractStructureNames(structures);
                if (!structureNames.isEmpty()) {
                    children.add(new LootConditionInfo(keyOf(condition),
                            Component.translatable(I18N_PREFIX + "location_check_structures",
                                    joinComponents(structureNames)), null));
                }
            });
            if (locPred.position().isPresent()) {
                children.add(simpleChild(condition, "location_check_position"));
            }
            if (!locationCheck.offset().equals(net.minecraft.core.BlockPos.ZERO)) {
                children.add(new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + "location_check_offset",
                                locationCheck.offset().getX(), locationCheck.offset().getY(),
                                locationCheck.offset().getZ()), null));
            }
            locPred.smokey().ifPresent(value -> children.add(simpleChild(condition,
                    value ? "location_check_smokey" : "location_check_not_smokey")));
            locPred.canSeeSky().ifPresent(value -> children.add(simpleChild(condition,
                    value ? "location_check_can_see_sky" : "location_check_cannot_see_sky")));
            if (locPred.light().isPresent()) {
                children.add(simpleChild(condition, "location_check_light"));
            }
            if (locPred.block().isPresent()) {
                children.add(simpleChild(condition, "location_check_block"));
            }
            if (locPred.fluid().isPresent()) {
                children.add(simpleChild(condition, "location_check_fluid"));
            }
            return new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "location_check"), null, children);
        }

        private LootConditionInfo simpleChild(LootItemCondition condition, String key) {
            return new LootConditionInfo(keyOf(condition), Component.translatable(I18N_PREFIX + key), null);
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

        private List<Component> extractStructureNames(HolderSet<Structure> structureSet) {
            List<Component> names = new ArrayList<>();
            structureSet.unwrap().ifLeft(tag -> names.add(Component.literal("#" + tag.location())));
            structureSet.unwrap().ifRight(holders -> holders.forEach(holder -> holder.unwrapKey()
                    .ifPresent(key -> names.add(Component.literal(key.location().toString())))));
            return names;
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

    /** 处理 time_check：展示时间范围与周期。 */
    private static final class TimeCheckHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            if (!(condition instanceof TimeCheck timeCheck)) {
                return null;
            }
            List<LootConditionInfo> children = timeCheck.period()
                    .map(period -> List.of(new LootConditionInfo(keyOf(condition),
                            Component.translatable(I18N_PREFIX + "time_check_period", period), null)))
                    .orElseGet(List::of);
            return new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "time_check_range", describeRange(timeCheck.value())),
                    null, children);
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

    /** 处理 weather_check：分别展示降雨与雷暴要求。 */
    private static final class WeatherCheckHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            if (!(condition instanceof WeatherCheck weatherCheck)) {
                return null;
            }
            List<LootConditionInfo> children = new ArrayList<>();
            weatherCheck.isRaining().ifPresent(required -> children.add(new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX
                            + (required ? "weather_check_raining" : "weather_check_not_raining")), null)));
            weatherCheck.isThundering().ifPresent(required -> children.add(new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX
                            + (required ? "weather_check_thundering" : "weather_check_not_thundering")), null)));
            return new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "weather_check"), null, children);
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
                return genericInfo(condition, entityCondition.entityTarget());
            }
            EntityPredicate entityPred = predicate.get();
            Component targetName = entityTargetName(entityCondition.entityTarget());
            Optional<EntityTypePredicate> entityType = entityPred.entityType();
            if (entityType.isPresent()) {
                List<Component> typeNames = new ArrayList<>();
                entityType.get().types().unwrap().ifLeft(tag ->
                        typeNames.add(Component.literal("#" + tag.location())));
                entityType.get().types().unwrap().ifRight(holders -> holders.forEach(holder ->
                        typeNames.add(holder.value().getDescription())));
                if (!typeNames.isEmpty()) {
                    return new LootConditionInfo(keyOf(condition),
                            Component.translatable(I18N_PREFIX + "entity_properties_types",
                                    targetName, joinComponents(typeNames)), null);
                }
            }
            Optional<EntitySubPredicate> subPredicate = entityPred.subPredicate();
            if (subPredicate.isEmpty()) {
                return genericInfo(condition, entityCondition.entityTarget());
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
            return genericInfo(condition, entityCondition.entityTarget());
        }

        private LootConditionInfo genericInfo(LootItemCondition condition, LootContext.EntityTarget target) {
            return new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "entity_properties_target", entityTargetName(target)), null);
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

    /** 处理 entity_scores：展示目标实体、objective 名称和分数范围。 */
    private static final class EntityScoresHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            if (!(condition instanceof EntityHasScoreCondition scoresCondition)) {
                return null;
            }
            List<LootConditionInfo> children = scoresCondition.scores().entrySet().stream()
                    .map(entry -> new LootConditionInfo(keyOf(condition),
                            Component.translatable(I18N_PREFIX + "entity_scores_value",
                                    entry.getKey(), describeRange(entry.getValue())), null))
                    .toList();
            return new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "entity_scores_target",
                            entityTargetName(scoresCondition.entityTarget())), null, children);
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

    /** 处理 damage_source_properties：展示伤害类型标签与直接伤害要求。 */
    private static final class DamageSourcePropertiesHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            if (!(condition instanceof DamageSourceCondition damageCondition)
                    || damageCondition.predicate().isEmpty()) {
                return new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + "damage_source_properties"), null);
            }
            DamageSourcePredicate predicate = damageCondition.predicate().get();
            List<LootConditionInfo> children = new ArrayList<>();
            for (TagPredicate<DamageType> tag : predicate.tags()) {
                children.add(new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX
                                        + (tag.expected() ? "damage_source_tag" : "damage_source_not_tag"),
                                Component.literal("#" + tag.tag().location())), null));
            }
            predicate.isDirect().ifPresent(direct -> children.add(new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX
                            + (direct ? "damage_source_direct" : "damage_source_indirect")), null)));
            if (predicate.directEntity().isPresent()) {
                children.add(new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + "damage_source_direct_entity"), null));
            }
            if (predicate.sourceEntity().isPresent()) {
                children.add(new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + "damage_source_source_entity"), null));
            }
            return new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "damage_source_properties"), null, children);
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
            List<LootConditionInfo> analyzed = analyzeAll(List.of(term));
            LootConditionInfo childInfo = analyzed.isEmpty()
                    ? fallbackInfo(BuiltInRegistries.LOOT_CONDITION_TYPE.getKey(term.getType()))
                    : analyzed.getFirst();
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
            List<LootItemCondition> terms = compositeTerms(anyOf);
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
            List<LootItemCondition> terms = compositeTerms(allOf);
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
