package com.meteorite.unsuspiciousblock.enchantment.framework;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.enchantment.framework.adapter.IEnchantmentEventAdapter;
import com.meteorite.unsuspiciousblock.enchantment.framework.effect.EffectContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.effect.EnchantmentEffect;
import com.meteorite.unsuspiciousblock.enchantment.framework.effect.EnchantmentValueEffect;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerType;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 附魔效果统一调度器——接收来自 Platform Adapter 的触发事件，查找已注册的效果组件并执行 */
public final class EnchantmentManager {

    // 副作用效果注册表：按触发类型分组
    private static final Map<TriggerType, List<EffectEntry>> EFFECT_REGISTRY = new EnumMap<>(TriggerType.class);

    // 值变换效果注册表：按触发类型分组（同类型按注册顺序判定，首个命中即返回）
    private static final Map<TriggerType, List<ValueEffectEntry<?>>> VALUE_EFFECT_REGISTRY = new EnumMap<>(TriggerType.class);

    private EnchantmentManager() {}

    // 初始化：注入平台适配器并注册事件监听
    public static void init(IEnchantmentEventAdapter adapter) {
        adapter.registerListeners();
        Constants.LOG.info("EnchantmentManager 已初始化，适配器: {}", adapter.getClass().getSimpleName());
    }

    // 注册副作用效果组件
    public static void register(TriggerType triggerType, ResourceKey<Enchantment> enchantmentKey,
                                EnchantmentEffect effect) {
        EFFECT_REGISTRY.computeIfAbsent(triggerType, k -> new ArrayList<>())
                .add(new EffectEntry(enchantmentKey, effect));
    }

    // 注册值变换效果组件
    public static <T> void registerValueEffect(TriggerType triggerType, ResourceKey<Enchantment> enchantmentKey,
                                               EnchantmentValueEffect<T> effect) {
        VALUE_EFFECT_REGISTRY.computeIfAbsent(triggerType, k -> new ArrayList<>())
                .add(new ValueEffectEntry<>(enchantmentKey, effect));
    }

    // 副作用事件入口——由 Platform Adapter 调用
    public static void dispatch(TriggerType triggerType, TriggerContext ctx) {
        List<EffectEntry> entries = EFFECT_REGISTRY.get(triggerType);
        if (entries == null || entries.isEmpty()) return;

        RegistryAccess registryAccess = ctx.player.registryAccess();
        for (EffectEntry entry : entries) {
            Holder<Enchantment> holder = lookupEnchantment(registryAccess, entry.enchantmentKey);
            if (holder == null) continue;

            int level = findEnchantmentLevel(ctx, holder);
            if (level <= 0) continue;

            ItemStack enchantedItem = findEnchantedItem(ctx);
            EffectContext<?> effectCtx = new EffectContext<>(
                    ctx, enchantedItem, level, entry.enchantmentKey, null);
            entry.effect.apply(effectCtx);
        }
    }

    // 值变换事件入口——由 Platform Adapter 调用，返回首个命中附魔的变换结果，未命中则返回原值
    @SuppressWarnings("unchecked")
    public static <T> T dispatchValue(TriggerType triggerType, TriggerContext ctx, T originalValue) {
        List<ValueEffectEntry<?>> entries = VALUE_EFFECT_REGISTRY.get(triggerType);
        if (entries == null || entries.isEmpty()) return originalValue;

        RegistryAccess registryAccess = ctx.player.registryAccess();
        T currentValue = originalValue;
        for (ValueEffectEntry<?> entry : entries) {
            Holder<Enchantment> holder = lookupEnchantment(registryAccess, entry.enchantmentKey);
            if (holder == null) continue;

            int level = findEnchantmentLevel(ctx, holder);
            if (level <= 0) continue;

            ItemStack enchantedItem = findEnchantedItem(ctx);
            EffectContext<T> valueCtx = new EffectContext<>(
                    ctx, enchantedItem, level, entry.enchantmentKey, currentValue);
            // 类型安全：注册时 T 已由调用方约定，此处按约定类型调用
            currentValue = ((EnchantmentValueEffect<T>) entry.effect).apply(valueCtx);
        }
        return currentValue;
    }

    // 从注册表查找附魔 Holder，缺失返回 null
    private static Holder<Enchantment> lookupEnchantment(RegistryAccess registryAccess,
                                                         ResourceKey<Enchantment> enchantmentKey) {
        return registryAccess.lookupOrThrow(Registries.ENCHANTMENT)
                .get(enchantmentKey)
                .orElse(null);
    }

    // 查询触发工具上的附魔等级——必须由触发上下文显式提供"正在使用的工具"
    private static int findEnchantmentLevel(TriggerContext ctx, Holder<Enchantment> enchantment) {
        ItemStack tool = ctx.tool;
        if (tool == null || tool.isEmpty()) {
            return 0;
        }
        return EnchantmentHelper.getItemEnchantmentLevel(enchantment, tool);
    }

    // 查找触发本次效果的附魔物品——必须由触发上下文显式提供"正在使用的工具"
    private static ItemStack findEnchantedItem(TriggerContext ctx) {
        ItemStack tool = ctx.tool;
        return tool != null && !tool.isEmpty() ? tool : ItemStack.EMPTY;
    }

    private record EffectEntry(ResourceKey<Enchantment> enchantmentKey, EnchantmentEffect effect) {}

    private record ValueEffectEntry<T>(ResourceKey<Enchantment> enchantmentKey, EnchantmentValueEffect<T> effect) {}
}
