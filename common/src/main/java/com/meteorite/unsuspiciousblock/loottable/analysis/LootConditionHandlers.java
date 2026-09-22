package com.meteorite.unsuspiciousblock.loottable.analysis;

import com.meteorite.unsuspiciousblock.loottable.catalog.DeclaredChance;

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
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.advancements.critereon.LocationPredicate;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.advancements.critereon.StatePropertiesPredicate;
import net.minecraft.advancements.critereon.TagPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.loot.IntRange;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.AllOfCondition;
import net.minecraft.world.level.storage.loot.predicates.AnyOfCondition;
import net.minecraft.world.level.storage.loot.predicates.BonusLevelTableCondition;
import net.minecraft.world.level.storage.loot.predicates.ConditionReference;
import net.minecraft.world.level.storage.loot.predicates.DamageSourceCondition;
import net.minecraft.world.level.storage.loot.predicates.EntityHasScoreCondition;
import net.minecraft.world.level.storage.loot.predicates.EnchantmentActiveCheck;
import net.minecraft.world.level.storage.loot.predicates.InvertedLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LocationCheck;
import net.minecraft.world.level.storage.loot.predicates.LootItemBlockStatePropertyCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemEntityPropertyCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceWithEnchantedBonusCondition;
import net.minecraft.world.level.storage.loot.predicates.MatchTool;
import net.minecraft.world.level.storage.loot.predicates.TimeCheck;
import net.minecraft.world.level.storage.loot.predicates.ValueCheckCondition;
import net.minecraft.world.level.storage.loot.predicates.WeatherCheck;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
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
 * <p>
 * 每条分析结果还会带上"描述保真度"元数据（{@link #FIDELITY_METADATA_KEY}）：只能给出成立但
 * 有未展示约束的描述时标 {@link #FIDELITY_PARTIAL}，没有解析器或解析失败时标
 * {@link #FIDELITY_UNREADABLE}，描述完整时不写该键。保真度由服务端解析层决定，
 * 客户端只据此映射样式，不自行推断条件语义。
 */
public final class LootConditionHandlers {
    /** 描述保真度元数据键——服务端写入、客户端只读，跨包复用因此公开。 */
    public static final String FIDELITY_METADATA_KEY = "analysis_fidelity";
    /** 有保留：描述成立，但明知有未展示的约束。 */
    public static final String FIDELITY_PARTIAL = "partial";
    /** 未读到：没有分析 handler，或 handler 未能产出描述。缺失该键表示描述完整。 */
    public static final String FIDELITY_UNREADABLE = "unreadable";

    /** 条件展示文案键前缀——自定义条件处理器拼接自己的文案键时复用同一来源，避免字面量重复。 */
    public static final String I18N_PREFIX = "screen.unsuspiciousblock.archaeology_journal.condition.";

    private static final Map<ResourceLocation, LootConditionHandler> REGISTRY = new LinkedHashMap<>();

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
        register("value_check", new ValueCheckHandler());
        register("weather_check", new WeatherCheckHandler());
        register("table_bonus", new TableBonusHandler());
        register("enchantment_active_check", new EnchantmentActiveHandler());

        // 类别 C：纯运行时
        register("entity_properties", new EntityPropertiesHandler());
        register("killed_by_player", runtimeDesc());
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
                results.add(unreadable(fallbackInfo(null)));
                continue;
            }
            ResourceLocation conditionId = BuiltInRegistries.LOOT_CONDITION_TYPE.getKey(condition.getType());
            LootConditionHandler handler = get(conditionId);
            if (handler != null) {
                LootConditionInfo info;
                try {
                    info = handler.analyze(condition);
                } catch (RuntimeException exception) {
                    info = null;
                }
                results.add(withSimulationMetadata(
                        info != null ? info : unreadable(fallbackInfo(conditionId)), condition));
            } else {
                // 该条件已成功解码但没有分析 handler：与解析失败合并为一态，保留 id 作为定位入口
                results.add(withSimulationMetadata(unreadable(fallbackInfo(conditionId)), condition));
            }
        }
        return results;
    }

    // 写入场景规划所需的机器可读元数据：条件指纹，以及该指纹是否可跨解析期/运行时稳定复现
    private static LootConditionInfo withSimulationMetadata(LootConditionInfo info,
                                                           LootItemCondition condition) {
        return info.withSource(condition).withMetadata(LootConditionFingerprint.METADATA_KEY,
                        LootConditionFingerprint.ofRaw(condition))
                .withMetadata(LootConditionFingerprint.STABLE_METADATA_KEY,
                        Boolean.toString(LootConditionFingerprint.isStableSource(condition)));
    }

    // ==================== 共享工具方法 ====================

    /**
     * 标记为"有保留"：描述成立，但明知有未展示的约束。
     * <p>
     * 自定义条件处理器给出"只说了一半"的描述时也应调用它，客户端会据此改用斜体。
     */
    public static LootConditionInfo partial(LootConditionInfo info) {
        return info.withMetadata(FIDELITY_METADATA_KEY, FIDELITY_PARTIAL);
    }

    // 标记为"未读到"：没有分析 handler，或 handler 未能产出描述
    static LootConditionInfo unreadable(LootConditionInfo info) {
        return info.withMetadata(FIDELITY_METADATA_KEY, FIDELITY_UNREADABLE);
    }

    /**
     * 组合条件继承最差子项：任一子项未读到则整体未读到，任一子项有保留则整体有保留。
     * 与 {@link #uncertaintyLevelOf} 的递归口径一致；子项自身的标记在此前已递归算好。
     */
    static LootConditionInfo inheritChildFidelity(LootConditionInfo info) {
        if (info.children().isEmpty()) {
            return info;
        }
        int worst = fidelityRank(info.metadata().get(FIDELITY_METADATA_KEY));
        for (LootConditionInfo child : info.children()) {
            worst = Math.max(worst, fidelityRank(child.metadata().get(FIDELITY_METADATA_KEY)));
        }
        return switch (worst) {
            case 2 -> unreadable(info);
            case 1 -> partial(info);
            default -> info;
        };
    }

    // 保真度排序用序号：完整（无标记）< 有保留 < 未读到
    private static int fidelityRank(@Nullable String fidelity) {
        if (FIDELITY_UNREADABLE.equals(fidelity)) {
            return 2;
        }
        return FIDELITY_PARTIAL.equals(fidelity) ? 1 : 0;
    }

    /** 为未识别条件生成通用 fallback 描述——保留条件 id，它是玩家定位未读到的唯一入口 */
    static LootConditionInfo fallbackInfo(@Nullable ResourceLocation conditionId) {
        ResourceLocation resolvedId = conditionId != null
                ? conditionId
                : ResourceLocation.fromNamespaceAndPath("unsuspiciousblock", "unknown");
        return new LootConditionInfo(resolvedId,
                Component.translatable(I18N_PREFIX + "unknown", resolvedId.toString()),
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

    // 用原版 Codec 提取 IntRange 的常量边界；动态 NumberProvider 回落为紧凑 JSON，
    // 并由 lossy 告知调用方"这一行没把约束说全"
    private static RangeDescription describeRange(IntRange range) {
        JsonElement encoded = IntRange.CODEC.encodeStart(JsonOps.INSTANCE, range).result().orElse(null);
        if (encoded == null) {
            return new RangeDescription("?", true);
        }
        if (encoded.isJsonPrimitive()) {
            return new RangeDescription(encoded.getAsString(), false);
        }
        if (!encoded.isJsonObject()) {
            return new RangeDescription(encoded.toString(), true);
        }
        JsonObject object = encoded.getAsJsonObject();
        JsonElement min = object.get("min");
        JsonElement max = object.get("max");
        String minText = compactJsonValue(min);
        String maxText = compactJsonValue(max);
        if (min != null && max != null) {
            return new RangeDescription(minText + " - " + maxText,
                    isDynamicJsonValue(min) || isDynamicJsonValue(max));
        }
        if (min != null) {
            return new RangeDescription(">= " + minText, isDynamicJsonValue(min));
        }
        if (max != null) {
            return new RangeDescription("<= " + maxText, isDynamicJsonValue(max));
        }
        // 两端都缺失即无界，描述完整
        return new RangeDescription("*", false);
    }

    private static boolean isDynamicJsonValue(@Nullable JsonElement element) {
        return element != null && !element.isJsonPrimitive();
    }

    /** IntRange 的文本描述，以及该描述是否回落到机器可读 JSON（即没说全）。 */
    private record RangeDescription(String text, boolean lossy) {
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
            JsonElement value = entry.getValue();
            if (value.isJsonObject()) {
                JsonObject bounds = value.getAsJsonObject();
                if ((bounds.has("min") || bounds.has("max"))
                        && bounds.entrySet().stream().allMatch(bound ->
                        "min".equals(bound.getKey()) || "max".equals(bound.getKey()))) {
                    String min = bounds.has("min") ? compactJsonValue(bounds.get("min")) : "";
                    String max = bounds.has("max") ? compactJsonValue(bounds.get("max")) : "";
                    values.add(entry.getKey() + "=" + min + ".." + max);
                    continue;
                }
            }
            values.add(entry.getKey() + "=" + compactJsonValue(value));
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

    /** 展示 value_check 的范围和数值提供器类型；动态提供器参数仍保留。 */
    private static final class ValueCheckHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            if (!(condition instanceof ValueCheckCondition(NumberProvider provider, IntRange range))) {
                return null;
            }
            RangeDescription describedRange = describeRange(range);
            JsonElement encoded = net.minecraft.world.level.storage.loot.providers.number.NumberProviders.CODEC
                    .encodeStart(JsonOps.INSTANCE, provider).result().orElse(null);
            String providerType = provider instanceof ConstantValue(float value)
                    ? Float.toString(value)
                    : encoded != null && encoded.isJsonObject() && encoded.getAsJsonObject().has("type")
                    ? compactJsonValue(encoded.getAsJsonObject().get("type")) : "?";
            LootConditionInfo info = new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "value_check_detail",
                            describedRange.text(), providerType), null);
            return describedRange.lossy() || !(provider instanceof ConstantValue) ? partial(info) : info;
        }

        @Override
        public boolean addsUncertainty() {
            return false;
        }
    }

    /** 按附魔等级顺序展示 table_bonus 的全部概率档位。 */
    private static final class TableBonusHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            if (!(condition instanceof BonusLevelTableCondition(Holder<Enchantment> enchantment,
                    List<Float> values))) {
                return null;
            }
            List<String> chances = new ArrayList<>(values.size());
            for (int level = 0; level < values.size(); level++) {
                chances.add(level + (level == values.size() - 1 ? "+" : "") + ": "
                        + new BigDecimal(Float.toString(values.get(level)))
                        .movePointRight(2).stripTrailingZeros().toPlainString() + "%");
            }
            return new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "table_bonus_detail",
                            enchantment.value().description(), String.join(", ", chances)), null);
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

    /** 区分附魔效果处于激活与未激活两种状态。 */
    private static final class EnchantmentActiveHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            if (!(condition instanceof EnchantmentActiveCheck(boolean active))) {
                return null;
            }
            return new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX
                            + (active ? "enchantment_active_check" : "enchantment_inactive_check")), null);
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

    private static LootConditionHandler runtimeDesc() {
        return new LootConditionHandler() {
            @Override
            public LootConditionInfo analyze(LootItemCondition condition) {
                return new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + "killed_by_player"), null);
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

    /**
     * 处理 random_chance：只在能读出确定数值时展示比例，其余形态一律承认"动态"，不编造百分比。
     * <p>
     * uniform 两端都是常量时可以完整给出区间；只有一端是常量时另一端写 {@code ?} 并标记有保留。
     */
    private static final class RandomChanceHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            NumberProvider provider = condition instanceof LootItemRandomChanceCondition(NumberProvider chance)
                    ? chance
                    : null;
            if (provider instanceof ConstantValue(float value)) {
                return new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + "random_chance", Math.round(value * 100)),
                        value)
                        .withMetadata(DeclaredChance.MIN_KEY,
                                Float.toString(value))
                        .withMetadata(DeclaredChance.MAX_KEY,
                                Float.toString(value));
            }
            if (provider instanceof UniformGenerator(NumberProvider min, NumberProvider max)) {
                boolean minConstant = min instanceof ConstantValue;
                boolean maxConstant = max instanceof ConstantValue;
                if (minConstant && maxConstant) {
                    return new LootConditionInfo(keyOf(condition),
                            Component.translatable(I18N_PREFIX + "random_chance_range",
                                    percentOf(min), percentOf(max)), null)
                            .withMetadata(DeclaredChance.MIN_KEY,
                                    Float.toString(((ConstantValue) min).value()))
                            .withMetadata(DeclaredChance.MAX_KEY,
                                    Float.toString(((ConstantValue) max).value()));
                }
                if (minConstant || maxConstant) {
                    return partial(new LootConditionInfo(keyOf(condition),
                            Component.translatable(I18N_PREFIX + "random_chance_range",
                                    minConstant ? percentOf(min) : "?",
                                    maxConstant ? percentOf(max) : "?"), null));
                }
            }
            // 两端皆动态的 uniform，以及 binomial / score 等 provider：只能承认是动态值
            return partial(new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "random_chance_dynamic"), null));
        }

        // 与 condition.random_chance 保持同一精度口径：整数百分比
        private String percentOf(NumberProvider provider) {
            return provider instanceof ConstantValue(float value)
                    ? Math.round(value * 100) + "%"
                    : "?";
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

    /** 处理 random_chance_with_enchanted_bonus：只展示基础概率，附魔加成从未展示 */
    private static final class RandomChanceWithEnchantedBonusHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            if (!(condition instanceof LootItemRandomChanceWithEnchantedBonusCondition(
                    float baseChance, LevelBasedValue enchantedChance, Holder<Enchantment> enchantment))) {
                return null;
            }
            JsonElement encoded = LevelBasedValue.CODEC.encodeStart(JsonOps.INSTANCE, enchantedChance)
                    .result().orElse(null);
            LootConditionInfo enchanted = partial(new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "random_chance_with_enchanted_bonus_enchanted",
                            enchantment.value().description(),
                            encoded != null ? compactJsonValue(encoded) : "?"), null));
            return partial(new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "random_chance_with_enchanted_bonus",
                            Math.round(baseChance * 100)),
                    baseChance, List.of(enchanted)));
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

    /** 处理 match_tool：优先展示物品或物品标签；count / 组件谓词等未展示约束时标记有保留。 */
    private static final class MatchToolHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            if (!(condition instanceof MatchTool(Optional<ItemPredicate> itemPredicate))
                    || itemPredicate.isEmpty()) {
                return new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + "match_tool"), null);
            }
            ItemPredicate predicate = itemPredicate.get();
            LootConditionInfo info = describeItems(condition, predicate);
            return hasNonItemConstraints(predicate) ? partial(info) : info;
        }

        private LootConditionInfo describeItems(LootItemCondition condition, ItemPredicate predicate) {
            Optional<HolderSet<Item>> items = predicate.items();
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

        // 除 items 外还有 count / 组件 / 子谓词约束时，只说"需要特定工具"是有保留的
        private boolean hasNonItemConstraints(ItemPredicate predicate) {
            return !MinMaxBounds.Ints.ANY.equals(predicate.count())
                    || !predicate.components().alwaysMatches()
                    || !predicate.subPredicates().isEmpty();
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
            if (!(condition instanceof LootItemBlockStatePropertyCondition(Holder<Block> block,
                    Optional<StatePropertiesPredicate> properties))) {
                return null;
            }
            List<LootConditionInfo> children = new ArrayList<>();
            properties.ifPresent(stateProperties -> children.add(new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "block_state_properties",
                            describeStateProperties(stateProperties)), null)));
            return new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "block_state_property_value",
                            block.value().getName()), null, children);
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
            if (!(condition instanceof LocationCheck(Optional<LocationPredicate> predicate, BlockPos offset))) {
                return null;
            }
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
            // 以下四行只说明"有约束"，不给约束内容，因此逐行标记有保留
            if (locPred.position().isPresent()) {
                children.add(partial(simpleChild(condition, "location_check_position")));
            }
            if (!offset.equals(BlockPos.ZERO)) {
                children.add(new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + "location_check_offset",
                                offset.getX(), offset.getY(),
                                offset.getZ()), null));
            }
            locPred.smokey().ifPresent(value -> children.add(simpleChild(condition,
                    value ? "location_check_smokey" : "location_check_not_smokey")));
            locPred.canSeeSky().ifPresent(value -> children.add(simpleChild(condition,
                    value ? "location_check_can_see_sky" : "location_check_cannot_see_sky")));
            if (locPred.light().isPresent()) {
                children.add(partial(simpleChild(condition, "location_check_light")));
            }
            if (locPred.block().isPresent()) {
                children.add(partial(simpleChild(condition, "location_check_block")));
            }
            if (locPred.fluid().isPresent()) {
                children.add(partial(simpleChild(condition, "location_check_fluid")));
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

    /** 处理 time_check：展示时间范围与周期；范围回落到 JSON 时标记有保留。 */
    private static final class TimeCheckHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            if (!(condition instanceof TimeCheck(Optional<Long> periodOption, IntRange value))) {
                return null;
            }
            List<LootConditionInfo> children = periodOption
                    .map(period -> List.of(new LootConditionInfo(keyOf(condition),
                            Component.translatable(I18N_PREFIX + "time_check_period", period), null)))
                    .orElseGet(List::of);
            RangeDescription range = describeRange(value);
            LootConditionInfo info = new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "time_check_range", range.text()),
                    null, children);
            return range.lossy() ? partial(info) : info;
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
            if (!(condition instanceof WeatherCheck(Optional<Boolean> isRaining,
                    Optional<Boolean> isThundering))) {
                return null;
            }
            List<LootConditionInfo> children = new ArrayList<>();
            isRaining.ifPresent(required -> children.add(new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX
                            + (required ? "weather_check_raining" : "weather_check_not_raining")), null)));
            isThundering.ifPresent(required -> children.add(new LootConditionInfo(keyOf(condition),
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
            if (!(condition instanceof LootItemEntityPropertyCondition(
                    Optional<EntityPredicate> predicate, LootContext.EntityTarget entityTarget))) {
                return null;
            }
            if (predicate.isEmpty()) {
                return genericInfo(condition, entityTarget);
            }
            EntityPredicate entityPred = predicate.get();
            Component targetName = entityTargetName(entityTarget);
            List<LootConditionInfo> children = new ArrayList<>();
            Optional<EntityTypePredicate> entityType = entityPred.entityType();
            if (entityType.isPresent()) {
                List<Component> typeNames = new ArrayList<>();
                entityType.get().types().unwrap().ifLeft(tag ->
                        typeNames.add(Component.literal("#" + tag.location())));
                entityType.get().types().unwrap().ifRight(holders -> holders.forEach(holder ->
                        typeNames.add(holder.value().getDescription())));
                if (!typeNames.isEmpty()) {
                    children.add(new LootConditionInfo(keyOf(condition),
                            Component.translatable(I18N_PREFIX + "entity_properties_types",
                                    targetName, joinComponents(typeNames)), null));
                }
            }
            Optional<EntitySubPredicate> subPredicate = entityPred.subPredicate();
            if (subPredicate.isPresent()) {
                EntitySubPredicate sub = subPredicate.get();
                // 使用记录模式判断具体子谓词类型，读取对应字段
                if (sub instanceof FishingHookPredicate(Optional<Boolean> inOpenWater)) {
                    String key = inOpenWater.map(openWater -> openWater
                                    ? "entity_properties_fishing_open_water"
                                    : "entity_properties_fishing_not_open_water")
                            .orElse("entity_properties_fishing");
                    children.add(new LootConditionInfo(keyOf(condition),
                            Component.translatable(I18N_PREFIX + key), null));
                }
            }
            // 其它实体字段仍未展示，保留 partial；已读到的两类约束都作为子行呈现。
            return partial(new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "entity_properties_target", targetName),
                    null, children));
        }

        // 除 entityType 与 fishing_hook 分支外，整个实体谓词都塌成一句话，因此标记有保留
        private LootConditionInfo genericInfo(LootItemCondition condition, LootContext.EntityTarget target) {
            return partial(new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "entity_properties_target", entityTargetName(target)),
                    null));
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

    /** 处理 entity_scores：展示目标实体、objective 名称和分数范围；范围回落到 JSON 时该行标记有保留。 */
    private static final class EntityScoresHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            if (!(condition instanceof EntityHasScoreCondition(Map<String, IntRange> scores,
                    LootContext.EntityTarget entityTarget))) {
                return null;
            }
            List<LootConditionInfo> children = scores.entrySet().stream()
                    .map(score -> scoreInfo(condition, score))
                    .toList();
            return new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "entity_scores_target",
                            entityTargetName(entityTarget)), null, children);
        }

        private LootConditionInfo scoreInfo(LootItemCondition condition, Map.Entry<String, IntRange> score) {
            RangeDescription range = describeRange(score.getValue());
            LootConditionInfo info = new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "entity_scores_value",
                            score.getKey(), range.text()), null);
            return range.lossy() ? partial(info) : info;
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
            if (!(condition instanceof DamageSourceCondition(Optional<DamageSourcePredicate> predicateOptional))
                    || predicateOptional.isEmpty()) {
                return new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + "damage_source_properties"), null);
            }
            DamageSourcePredicate predicate = predicateOptional.get();
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
                children.add(partial(new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + "damage_source_direct_entity"), null)));
            }
            if (predicate.sourceEntity().isPresent()) {
                children.add(partial(new LootConditionInfo(keyOf(condition),
                        Component.translatable(I18N_PREFIX + "damage_source_source_entity"), null)));
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
            return inheritChildFidelity(new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "inverted", childInfo.description()),
                    null,
                    List.of(childInfo)));
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
            return inheritChildFidelity(new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "any_of"), null, children));
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
            return inheritChildFidelity(new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "all_of"), null, children));
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

    /** 处理 reference：只给出被引用的条件 id，不解析其内容，因此标记有保留 */
    private static final class ReferenceHandler implements LootConditionHandler {
        @Override
        public LootConditionInfo analyze(LootItemCondition condition) {
            String name = "?";
            if (condition instanceof ConditionReference(net.minecraft.resources.ResourceKey<LootItemCondition> name1)) {
                name = name1.location().toString();
            }
            return partial(new LootConditionInfo(keyOf(condition),
                    Component.translatable(I18N_PREFIX + "reference", name),
                    null));
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
