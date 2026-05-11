package com.meteorite.unsuspiciousblock.enchantment;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.Enchantment;

/** 模组附魔 key 集中定义 */
public final class ModEnchantments {
    public static final ResourceKey<Enchantment> MUD_DREDGING = key("mud_dredging");
    public static final ResourceKey<Enchantment> TEXTILE_RECOVERY = key("textile_recovery");

    private ModEnchantments() {
    }

    public static Holder<Enchantment> getOrThrow(RegistryAccess registryAccess, ResourceKey<Enchantment> enchantmentKey) {
        return registryAccess.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(enchantmentKey);
    }

    private static ResourceKey<Enchantment> key(String path) {
        return ResourceKey.create(Registries.ENCHANTMENT,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, path));
    }
}
