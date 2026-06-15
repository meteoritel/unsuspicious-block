package com.meteorite.unsuspiciousblock.enchantment;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.List;
import java.util.stream.Stream;

/***
 * 锻造台合成附魔书的随机附魔生成器。
 * 仅服务端调用：按三种概率分布向附魔书写入 STORED_ENCHANTMENTS。
 */
public final class EnchantedBookRoller {

    // 本模组附魔池，供单一/多重附魔的"模组附魔"环节随机抽取
    private static final List<ResourceKey<Enchantment>> MOD_ENCHANTMENTS = List.of(
            ModEnchantments.MUD_DREDGING,
            ModEnchantments.TEXTILE_RECOVERY,
            ModEnchantments.PRECISION_EXCAVATION,
            ModEnchantments.FOSSIL_HUNTER
    );

    private EnchantedBookRoller() {
    }

    // 向给定附魔书写入随机附魔（按 30%/30%/40% 三类分布）
    public static void roll(ItemStack book, RandomSource random, RegistryAccess registries) {
        HolderLookup.RegistryLookup<Enchantment> lookup = registries.lookupOrThrow(Registries.ENCHANTMENT);
        ItemEnchantments.Mutable result = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);

        float roll = random.nextFloat();
        if (roll < 0.30f) {
            // 情况(1)：单一附魔——随机一种本模组附魔
            applyRandomModEnchantment(result, lookup, random);
        } else if (roll < 0.60f) {
            // 情况(2)：多重——先一种本模组附魔，再叠加 10-20 经验等级的原版随机附魔
            applyRandomModEnchantment(result, lookup, random);
            applyVanillaRandom(result, lookup, random, 10 + random.nextInt(11));
        } else {
            // 情况(3)：随机 15-35 经验等级的原版随机附魔
            applyVanillaRandom(result, lookup, random, 15 + random.nextInt(21));
        }

        book.set(DataComponents.STORED_ENCHANTMENTS, result.toImmutable());
    }

    // 随机抽取一种本模组附魔，等级取 1~maxLevel
    private static void applyRandomModEnchantment(ItemEnchantments.Mutable result,
                                                  HolderLookup.RegistryLookup<Enchantment> lookup,
                                                  RandomSource random) {
        ResourceKey<Enchantment> key = MOD_ENCHANTMENTS.get(random.nextInt(MOD_ENCHANTMENTS.size()));
        Holder<Enchantment> holder = lookup.getOrThrow(key);
        int maxLevel = holder.value().getMaxLevel();
        int level = 1 + random.nextInt(maxLevel);
        result.set(holder, level);
    }

    // 按给定经验等级从"原版可上书的非宝藏附魔"池随机生成并写入
    private static void applyVanillaRandom(ItemEnchantments.Mutable result,
                                           HolderLookup.RegistryLookup<Enchantment> lookup,
                                           RandomSource random,
                                           int experienceLevel) {
        Stream<Holder<Enchantment>> pool = lookup.get(EnchantmentTags.IN_ENCHANTING_TABLE)
                .map(HolderSet::stream)
                .orElseGet(Stream::empty);
        // 以普通书为探针，原版会对 BOOK 特判，接受所有附魔台可用附魔
        ItemStack probe = new ItemStack(Items.BOOK);
        List<EnchantmentInstance> selected = EnchantmentHelper.selectEnchantment(random, probe, experienceLevel, pool);
        for (EnchantmentInstance instance : selected) {
            result.set(instance.enchantment, instance.level);
        }
    }
}
