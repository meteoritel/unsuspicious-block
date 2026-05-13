package com.meteorite.unsuspiciousblock.enchantment;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/** 模组附魔 key 集中定义 */
public final class ModEnchantments {
    public static final ResourceKey<Enchantment> MUD_DREDGING = key("mud_dredging");
    public static final ResourceKey<Enchantment> TEXTILE_RECOVERY = key("textile_recovery");
    public static final ResourceKey<Enchantment> PRECISION_EXCAVATION = key("precision_excavation");
    public static final ResourceKey<Enchantment> PENETRATING_SCAN = key("penetrating_scan");
    public static final ResourceKey<Enchantment> FOSSIL_HUNTER = key("fossil_hunter");

    private ModEnchantments() {
    }

    public static Holder<Enchantment> getOrThrow(RegistryAccess registryAccess, ResourceKey<Enchantment> enchantmentKey) {
        return registryAccess.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(enchantmentKey);
    }

    /** 获取物品上指定附魔的等级，未附魔返回 0。供各附魔 Service 统一使用。 */
    public static int getEnchantmentLevel(RegistryAccess registryAccess, ItemStack stack,
                                           ResourceKey<Enchantment> enchantmentKey) {
        return registryAccess.lookupOrThrow(Registries.ENCHANTMENT)
                .get(enchantmentKey)
                .map(enchantment -> EnchantmentHelper.getItemEnchantmentLevel(enchantment, stack))
                .orElse(0);
    }

    private static ResourceKey<Enchantment> key(String path) {
        return ResourceKey.create(Registries.ENCHANTMENT,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, path));
    }
}
