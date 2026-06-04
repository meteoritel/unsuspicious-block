package com.meteorite.unsuspiciousblock.enchantment.framework.effect;

import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import org.jetbrains.annotations.Nullable;

/** 效果执行上下文——由 EnchantmentManager 在触发时构建，传入 {@link EnchantmentEffect#apply(EffectContext)} */
public record EffectContext(
        ServerPlayer player,
        ServerLevel level,
        @Nullable BlockPos pos,
        ItemStack enchantedItem,
        int enchantmentLevel,
        ResourceKey<Enchantment> enchantment,
        TriggerContext triggerContext
) {}
