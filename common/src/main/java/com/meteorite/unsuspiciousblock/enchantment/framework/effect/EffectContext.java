package com.meteorite.unsuspiciousblock.enchantment.framework.effect;

import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * 统一效果执行上下文——由 {@link com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager}
 * 在触发时构建。
 * <p>
 * 副作用效果使用 {@code EffectContext<?>}（value 为 null），值变换效果使用 {@code EffectContext<T>}（value 为待变换值）。
 */
public record EffectContext<T>(
        TriggerContext triggerContext,
        ItemStack enchantedItem,
        int enchantmentLevel,
        ResourceKey<Enchantment> enchantment,
        T value
) {}
