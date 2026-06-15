package com.meteorite.unsuspiciousblock.enchantment.framework.effect;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jetbrains.annotations.Nullable;

/**
 * 值变换效果上下文——由 {@link com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager}
 * 在值变换触发时构建，传入 {@link EnchantmentValueEffect#apply(ValueEffectContext)}。
 * <p>
 * originalValue 承载待变换的原值，常见类型为 {@link ItemStack}（刷子物品翻倍）或
 * {@link ResourceKey}&lt;{@link LootTable}&gt;（替换钓鱼战利品表）。
 */
public record ValueEffectContext<T>(
        ServerPlayer player,
        ServerLevel level,
        @Nullable BlockPos pos,
        ItemStack enchantedItem,
        int enchantmentLevel,
        ResourceKey<Enchantment> enchantment,
        T originalValue
) {}
