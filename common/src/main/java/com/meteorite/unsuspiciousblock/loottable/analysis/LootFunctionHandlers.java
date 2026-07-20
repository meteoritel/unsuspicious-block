package com.meteorite.unsuspiciousblock.loottable.analysis;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.SetComponentsFunction;
import net.minecraft.world.level.storage.loot.functions.SetCustomDataFunction;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.functions.SetItemDamageFunction;
import net.minecraft.world.level.storage.loot.functions.SetItemFunction;
import net.minecraft.world.level.storage.loot.functions.SetNameFunction;
import net.minecraft.world.level.storage.loot.functions.SetPotionFunction;
import net.minecraft.world.level.storage.loot.functions.SetCustomModelDataFunction;
import net.minecraft.world.level.storage.loot.functions.SetLoreFunction;
import net.minecraft.world.level.storage.loot.functions.SetOminousBottleAmplifierFunction;
import net.minecraft.world.level.storage.loot.functions.SetBookCoverFunction;
import net.minecraft.world.level.storage.loot.functions.SetFireworksFunction;
import net.minecraft.world.level.storage.loot.functions.SetStewEffectFunction;
import net.minecraft.world.level.storage.loot.functions.ExplorationMapFunction;
import net.minecraft.world.level.storage.loot.functions.ApplyBonusCount;
import net.minecraft.world.level.storage.loot.functions.LimitCount;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;
import net.minecraft.world.level.storage.loot.IntRange;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.world.effect.MobEffect;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 战利品函数处理器注册表——管理所有已知 loot function 的静态解析逻辑。
 * <p>
 * 内建全部原版 1.21.1 的 40 个 loot function 处理器。
 * 外部模组可通过 {@link #register} 注册自定义 function 的处理器。
 * 未知 function 在查询时返回 null，由调用方标记为条件 + 近似签名。
 */
public final class LootFunctionHandlers {
    private static final Map<ResourceLocation, LootFunctionHandler> REGISTRY = new LinkedHashMap<>();
    private static final ResourceLocation BOOK_ID = ResourceLocation.fromNamespaceAndPath("minecraft", "book");

    /** 返回 null 且 addsRandomness=true 的通用 handler，用于无法静态求值的 function */
    private static final LootFunctionHandler NULL_RANDOM = new LootFunctionHandler() {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    };

    static {
        // 类别 A：完整静态处理
        register("set_item", new SetItemHandler());
        register("set_components", new SetComponentsHandler());
        register("set_custom_data", new SetCustomDataHandler());
        register("set_name", new SetNameHandler());
        register("set_potion", new SetPotionHandler());

        // 类别 B：附魔类
        register("enchant_randomly", new EnchantRandomlyHandler());
        register("enchant_with_levels", new EnchantWithLevelsHandler());
        register("set_enchantments", new SetEnchantmentsHandler());
        register("enchanted_count_increase", new EnchantedCountIncreaseHandler());

        // 类别 C：可部分静态处理
        register("set_count", new SetCountHandler());
        register("set_damage", new SetDamageHandler());
        register("set_custom_model_data", new SetCustomModelDataHandler());
        register("set_lore", new SetLoreHandler());
        register("set_attributes", new SetAttributesHandler());
        register("toggle_tooltips", new ToggleTooltipsHandler());
        register("set_ominous_bottle_amplifier", new SetOminousBottleAmplifierHandler());

        // 类别 D：影响结果但无法静态求值
        register("copy_components", NULL_RANDOM);
        register("copy_custom_data", NULL_RANDOM);
        register("copy_name", NULL_RANDOM);
        register("copy_state", NULL_RANDOM);
        register("set_instrument", NULL_RANDOM);
        register("set_fireworks", new SetFireworksHandler());
        register("set_firework_explosion", NULL_RANDOM);
        register("set_banner_pattern", NULL_RANDOM);
        register("set_book_cover", new SetBookCoverHandler());
        register("set_written_book_pages", NULL_RANDOM);
        register("set_writable_book_pages", NULL_RANDOM);
        register("fill_player_head", new FillPlayerHeadHandler());
        register("set_stew_effect", new SetStewEffectHandler());
        register("exploration_map", new ExplorationMapHandler());
        register("furnace_smelt", NULL_RANDOM);
        register("set_contents", NULL_RANDOM);
        register("modify_contents", NULL_RANDOM);
        register("set_loot_table", NULL_RANDOM);

        // 类别 E：数量/概率类
        register("limit_count", new LimitCountHandler());
        register("apply_bonus", new ApplyBonusHandler());
        register("explosion_decay", NULL_RANDOM);

        // 类别 F：元函数
        register("filtered", NULL_RANDOM);
        register("reference", NULL_RANDOM);
        register("sequence", NULL_RANDOM);
    }

    private LootFunctionHandlers() {
    }

    /**
     * 注册自定义 loot function 处理器。
     *
     * @param functionPath function 注册名（如 "set_count"，不含 "minecraft:" 前缀）
     */
    public static void register(String functionPath, LootFunctionHandler handler) {
        REGISTRY.put(ResourceLocation.fromNamespaceAndPath("minecraft", functionPath), handler);
    }

    /**
     * 以完整 ResourceLocation 注册自定义 loot function 处理器。
     */
    public static void register(ResourceLocation functionId, LootFunctionHandler handler) {
        REGISTRY.put(functionId, handler);
    }

    /**
     * 获取指定 function 的处理器。
     *
     * @param functionId function 的完整注册表 key
     * @return 对应的 handler；未注册时返回 null
     */
    @Nullable
    public static LootFunctionHandler get(ResourceLocation functionId) {
        return REGISTRY.get(functionId);
    }

    /**
     * 获取注册表只读视图。
     */
    public static Map<ResourceLocation, LootFunctionHandler> registry() {
        return Collections.unmodifiableMap(REGISTRY);
    }

    /** 根据 function 对象推导其注册表 key */
    static ResourceLocation keyOf(LootItemFunction function) {
        return BuiltInRegistries.LOOT_FUNCTION_TYPE.getKey(function.getType());
    }

    // ==================== 反射工具 ====================

    /**
     * 通过反射读取原版类的 package-private 字段。
     * 仅用于静态分析，不修改对象状态。
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private static <T> T reflectField(Object obj, String fieldName) {
        Class<?> type = obj.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
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

    /**
     * 将反射字段安全转换为目标类型，同时兼容原版 Codec 使用的 Optional 字段。
     */
    @Nullable
    static <T> T unwrapFieldValue(@Nullable Object fieldValue, Class<T> expectedType) {
        Object value = fieldValue instanceof Optional<?> optional
                ? optional.orElse(null)
                : fieldValue;
        return expectedType.isInstance(value) ? expectedType.cast(value) : null;
    }

    // ==================== 共享工具方法 ====================

    private static ResourceLocation itemIdOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    private static ItemStack promoteBookPreviewIfNeeded(ItemStack stack) {
        if (BOOK_ID.equals(itemIdOf(stack))) {
            return stack.transmuteCopy(Items.ENCHANTED_BOOK);
        }
        return stack;
    }

    // ==================== 类别 A：完整静态处理 ====================

    /** 处理 set_item：将物品替换为指定物品 */
    private static final class SetItemHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetItemFunction)) {
                return null;
            }
            Object itemHolderRaw = reflectField(function, "item");
            if (!(itemHolderRaw instanceof Holder<?> itemHolder)) {
                return null;
            }
            ResourceLocation itemId = itemHolder.unwrapKey().map(ResourceKey::location).orElse(null);
            if (itemId != null && BuiltInRegistries.ITEM.get(itemId) != Items.AIR) {
                return previewStack.transmuteCopy(BuiltInRegistries.ITEM.get(itemId));
            }
            return null;
        }

        @Override
        public LootResultSignature deriveSignature(ItemStack previewStack, ResourceLocation itemId) {
            return LootResultSignature.plain(itemIdOf(previewStack));
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 set_components：应用精确 DataComponentPatch */
    private static final class SetComponentsHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetComponentsFunction)) {
                return null;
            }
            DataComponentPatch patch = reflectField(function, "components");
            if (patch == null) {
                return null;
            }
            try {
                previewStack.applyComponentsAndValidate(patch);
                return previewStack;
            } catch (RuntimeException exception) {
                return null;
            }
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 set_custom_data：合并自定义 NBT 数据 */
    private static final class SetCustomDataHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetCustomDataFunction)) {
                return null;
            }
            CompoundTag tag = reflectField(function, "tag");
            if (tag == null) {
                return previewStack;
            }
            CustomData.update(DataComponents.CUSTOM_DATA, previewStack, data -> data.merge(tag));
            return previewStack;
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 set_name：设置自定义名称或物品名称 */
    private static final class SetNameHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetNameFunction)) {
                return null;
            }
            Component component = unwrapFieldValue(reflectField(function, "name"), Component.class);
            if (component == null) {
                return previewStack;
            }
            SetNameFunction.Target target = unwrapFieldValue(
                    reflectField(function, "target"), SetNameFunction.Target.class);
            if (target == null) {
                return previewStack;
            }
            return switch (target) {
                case CUSTOM_NAME -> {
                    previewStack.set(DataComponents.CUSTOM_NAME, component);
                    yield previewStack;
                }
                case ITEM_NAME -> {
                    previewStack.set(DataComponents.ITEM_NAME, component);
                    yield previewStack;
                }
            };
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 set_potion：写入药水内容组件 */
    private static final class SetPotionHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetPotionFunction)) {
                return null;
            }
            Holder<Potion> potionHolder = reflectField(function, "potion");
            if (potionHolder == null) {
                return null;
            }
            previewStack.set(DataComponents.POTION_CONTENTS, new PotionContents(potionHolder));
            return previewStack;
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    // ==================== 类别 B：附魔类 ====================

    private static final class EnchantRandomlyHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return promoteBookPreviewIfNeeded(previewStack);
        }

        @Override
        public LootResultSignature deriveSignature(ItemStack previewStack, ResourceLocation itemId) {
            return LootResultSignature.enchantedApprox(itemIdOf(previewStack));
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    private static final class EnchantWithLevelsHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return promoteBookPreviewIfNeeded(previewStack);
        }

        @Override
        public LootResultSignature deriveSignature(ItemStack previewStack, ResourceLocation itemId) {
            return LootResultSignature.enchantedApprox(itemIdOf(previewStack));
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    private static final class SetEnchantmentsHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return promoteBookPreviewIfNeeded(previewStack);
        }

        @Override
        public LootResultSignature deriveSignature(ItemStack previewStack, ResourceLocation itemId) {
            return LootResultSignature.enchantedApprox(itemIdOf(previewStack));
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    private static final class EnchantedCountIncreaseHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return promoteBookPreviewIfNeeded(previewStack);
        }

        @Override
        public LootResultSignature deriveSignature(ItemStack previewStack, ResourceLocation itemId) {
            return LootResultSignature.enchantedApprox(itemIdOf(previewStack));
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    // ==================== 类别 C：可部分静态处理 ====================

    /** 处理 set_count：若 count 为固定值则直接应用；若为范围则返回 null */
    private static final class SetCountHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetItemCountFunction)) {
                return null;
            }
            NumberProvider value = reflectField(function, "value");
            if (value instanceof ConstantValue(float value1)) {
                Boolean add = reflectField(function, "add");
                if (Boolean.TRUE.equals(add)) {
                    previewStack.grow(Math.round(value1));
                } else {
                    previewStack.setCount(Math.round(value1));
                }
                return previewStack;
            }
            return null;
        }

        @Override
        @Nullable
        public Component describeHint(LootItemFunction function) {
            if (!(function instanceof SetItemCountFunction)) {
                return null;
            }
            NumberProvider value = reflectField(function, "value");
            if (value instanceof UniformGenerator uniform) {
                NumberProvider min = reflectField(uniform, "min");
                NumberProvider max = reflectField(uniform, "max");
                if (min instanceof ConstantValue(float value1) && max instanceof ConstantValue(float value2)) {
                    int minInt = Math.round(value1);
                    int maxInt = Math.round(value2);
                    if (minInt == maxInt) {
                        return null;
                    }
                    return Component.translatable(
                            "screen.unsuspiciousblock.archaeology_journal.item_hint.set_count_range", minInt, maxInt);
                }
            }
            return null;
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 set_damage：设置物品耐久损伤 */
    private static final class SetDamageHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetItemDamageFunction)) {
                return null;
            }
            NumberProvider damage = reflectField(function, "damage");
            if (damage instanceof ConstantValue(float value)) {
                int maxDamage = previewStack.getMaxDamage();
                if (maxDamage > 0) {
                    float remainingDurability = 1.0F - (float) previewStack.getDamageValue() / maxDamage;
                    Boolean add = reflectField(function, "add");
                    float resultDurability = Boolean.TRUE.equals(add)
                            ? remainingDurability + value
                            : value;
                    previewStack.setDamageValue(Mth.floor(
                            (1.0F - Mth.clamp(resultDurability, 0.0F, 1.0F)) * maxDamage));
                }
                return previewStack;
            }
            return null;
        }

        @Override
        @Nullable
        public Component describeHint(LootItemFunction function) {
            if (!(function instanceof SetItemDamageFunction)) {
                return null;
            }
            NumberProvider damage = reflectField(function, "damage");
            if (damage instanceof UniformGenerator uniform) {
                NumberProvider min = reflectField(uniform, "min");
                NumberProvider max = reflectField(uniform, "max");
                if (min instanceof ConstantValue(float minFloat) && max instanceof ConstantValue(float maxFloat)) {
                    if (minFloat == maxFloat) {
                        return null;
                    }
                    return Component.translatable(
                            "screen.unsuspiciousblock.archaeology_journal.item_hint.set_damage_range",
                            Math.round(minFloat * 100), Math.round(maxFloat * 100));
                }
            }
            return null;
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 set_custom_model_data：设置自定义模型数据 */
    private static final class SetCustomModelDataHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetCustomModelDataFunction)) {
                return null;
            }
            NumberProvider value = reflectField(function, "value");
            if (value instanceof ConstantValue(float value1)) {
                previewStack.set(DataComponents.CUSTOM_MODEL_DATA,
                        new CustomModelData(Math.round(value1)));
                return previewStack;
            }
            return null;
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 set_lore：设置物品描述 */
    private static final class SetLoreHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetLoreFunction)) {
                return null;
            }
            List<Component> loreList = reflectField(function, "lore");
            if (loreList != null && !loreList.isEmpty()) {
                previewStack.set(DataComponents.LORE, new ItemLore(loreList));
                return previewStack;
            }
            return null;
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 set_attributes：设置属性修饰符（含随机范围） */
    private static final class SetAttributesHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 toggle_tooltips：切换工具提示可见性 */
    private static final class ToggleTooltipsHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return previewStack;
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    /** 处理 set_ominous_bottle_amplifier：设置不祥之瓶增幅 */
    private static final class SetOminousBottleAmplifierHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            if (!(function instanceof SetOminousBottleAmplifierFunction)) {
                return null;
            }
            NumberProvider amplifier = reflectField(function, "amplifierGenerator");
            if (amplifier instanceof ConstantValue(float value)) {
                previewStack.set(DataComponents.OMINOUS_BOTTLE_AMPLIFIER, Math.round(value));
                return previewStack;
            }
            return null;
        }

        @Override
        public boolean addsRandomness() {
            return false;
        }
    }

    // ==================== 类别 D/E：新增谓词解析 handler ====================

    /** 处理 apply_bonus：展示附魔加成公式类型 */
    private static final class ApplyBonusHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        @Override
        public Component describeHint(LootItemFunction function) {
            if (!(function instanceof ApplyBonusCount)) {
                return null;
            }
            Holder<net.minecraft.world.item.enchantment.Enchantment> enchantmentHolder = reflectField(function, "enchantment");
            ResourceLocation enchantmentId = enchantmentHolder != null
                    ? enchantmentHolder.unwrapKey().map(ResourceKey::location).orElse(null)
                    : null;
            Component enchantmentName = enchantmentId != null
                    ? Component.translatable("enchantment." + enchantmentId.getNamespace() + "." + enchantmentId.getPath())
                    : Component.literal("?");

            // 通过反射获取 formula 内部字段
            Object formula = reflectField(function, "formula");
            String formulaType = "unknown";
            if (formula != null) {
                // formula 实现了 Formula 接口，其 getType() 返回 FormulaType
                try {
                    java.lang.reflect.Method getType = formula.getClass().getMethod("getType");
                    Object formulaTypeObj = getType.invoke(formula);
                    if (formulaTypeObj != null) {
                        ResourceLocation typeId = BuiltInRegistries.LOOT_NUMBER_PROVIDER_TYPE.getKey(
                                    (net.minecraft.world.level.storage.loot.providers.number.LootNumberProviderType) formulaTypeObj);
                        if (typeId != null) {
                            formulaType = typeId.getPath();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            Component formulaName = Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.formula." + formulaType);

            return Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.item_hint.apply_bonus",
                    enchantmentName, formulaName);
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 limit_count：展示数量限制范围 */
    private static final class LimitCountHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        @Override
        @Nullable
        public Component describeHint(LootItemFunction function) {
            if (!(function instanceof LimitCount)) {
                return null;
            }
            IntRange limiter = reflectField(function, "limiter");
            if (limiter == null) {
                return null;
            }
            NumberProvider min = reflectField(limiter, "min");
            NumberProvider max = reflectField(limiter, "max");

            Integer minVal = (min instanceof ConstantValue(float value)) ? Math.round(value) : null;
            Integer maxVal = (max instanceof ConstantValue(float value)) ? Math.round(value) : null;

            if (minVal != null && maxVal != null) {
                if (minVal.equals(maxVal)) {
                    return Component.translatable(
                            "screen.unsuspiciousblock.archaeology_journal.item_hint.limit_count_exact", minVal);
                }
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.limit_count", minVal, maxVal);
            } else if (maxVal != null) {
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.limit_count_max", maxVal);
            } else if (minVal != null) {
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.limit_count_min", minVal);
            }
            return null;
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 fill_player_head：展示玩家头颅来源 */
    private static final class FillPlayerHeadHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        @Override
        public Component describeHint(LootItemFunction function) {
            return Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.item_hint.fill_player_head");
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 set_stew_effect：展示炖菜效果列表 */
    private static final class SetStewEffectHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        @Override
        @Nullable
        public Component describeHint(LootItemFunction function) {
            if (!(function instanceof SetStewEffectFunction)) {
                return null;
            }
            List<?> effects = reflectField(function, "effects");
            if (effects == null || effects.isEmpty()) {
                return null;
            }
            List<Component> effectNames = new ArrayList<>();
            for (Object entry : effects) {
                Holder<MobEffect> effectHolder = reflectField(entry, "effect");
                if (effectHolder != null) {
                    MobEffect effect = effectHolder.value();
                    effectNames.add(effect.getDisplayName());
                }
            }
            if (effectNames.isEmpty()) return null;
            Component joined = effectNames.getFirst();
            for (int i = 1; i < effectNames.size(); i++) {
                joined = Component.literal("").append(joined).append("，").append(effectNames.get(i));
            }
            return Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.item_hint.set_stew_effect", joined);
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 exploration_map：展示探索地图目的地 */
    private static final class ExplorationMapHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        @Override
        public Component describeHint(LootItemFunction function) {
            if (!(function instanceof ExplorationMapFunction)) {
                return null;
            }
            // destination 是 ResourceKey<MapDecorationType>，通过反射获取
            Object destination = reflectField(function, "destination");
            if (destination == null) {
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.exploration_map_default");
            }
            // ResourceKey 有 location() 方法
            try {
                java.lang.reflect.Method locationMethod = destination.getClass().getMethod("location");
                ResourceLocation loc = (ResourceLocation) locationMethod.invoke(destination);
                String tagPath = loc.getPath();
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.exploration_map", tagPath);
            } catch (Exception e) {
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.exploration_map_default");
            }
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 set_fireworks：展示烟花飞行时间 */
    private static final class SetFireworksHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        @Override
        @Nullable
        public Component describeHint(LootItemFunction function) {
            if (!(function instanceof SetFireworksFunction)) {
                return null;
            }
            NumberProvider flightDuration = reflectField(function, "flightDuration");
            if (flightDuration instanceof ConstantValue(float value)) {
                int duration = Math.round(value);
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.set_fireworks_flight", duration);
            }
            return null;
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }

    /** 处理 set_book_cover：展示成书标题与作者 */
    private static final class SetBookCoverHandler implements LootFunctionHandler {
        @Override
        @Nullable
        public ItemStack apply(ItemStack previewStack, LootItemFunction function) {
            return null;
        }

        @Override
        @Nullable
        public Component describeHint(LootItemFunction function) {
            if (!(function instanceof SetBookCoverFunction)) {
                return null;
            }
            // title 是 Filterable<String>，author 是 Optional<String>
            Object titleObj = reflectField(function, "title");
            String title = null;
            if (titleObj != null) {
                // Filterable 有 raw() 方法
                try {
                    java.lang.reflect.Method rawMethod = titleObj.getClass().getMethod("raw");
                    Object raw = rawMethod.invoke(titleObj);
                    if (raw instanceof String s) {
                        title = s;
                    }
                } catch (Exception ignored) {
                }
            }
            String author = null;
            Object authorRaw = reflectField(function, "author");
            if (authorRaw instanceof Optional<?> opt) {
                author = (String) opt.orElse(null);
            }

            if (title != null && author != null) {
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.set_book_cover",
                        title, author);
            } else if (title != null) {
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.set_book_cover_title_only",
                        title);
            } else if (author != null) {
                return Component.translatable(
                        "screen.unsuspiciousblock.archaeology_journal.item_hint.set_book_cover_author_only",
                        author);
            }
            return null;
        }

        @Override
        public boolean addsRandomness() {
            return true;
        }
    }
}
