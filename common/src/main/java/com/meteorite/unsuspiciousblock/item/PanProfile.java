package com.meteorite.unsuspiciousblock.item;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;

/***
 * 淘盘档案——一把淘盘的全部差异数据：可采变体集、铁砧修复材料、按变体分档的幸运加成与再生概率。
 * <p>
 * 幸运加成与再生概率都以 {@link DoubleSupplier} 持有：配置项在注册表填充期尚未加载，
 * 若在物品构造时立即求值，NeoForge 端会抛出「配置尚未加载」异常。
 */
public record PanProfile(
        Set<ResourceLocation> harvestTargets,                   // 可采变体集合
        TagKey<Item> repairTag,                                 // 铁砧修复材料
        Map<ResourceLocation, DoubleSupplier> luckByVariant,     // 按目标变体分档的幸运加成，缺省 0
        DoubleSupplier regenerationChance                       // 自然点淘空后的再生概率
) {
    public PanProfile {
        harvestTargets = Set.copyOf(harvestTargets);
        luckByVariant = Map.copyOf(luckByVariant);
    }

    // 该工具能否作用于指定变体——工具与点的能力交集判定
    public boolean supports(ResourceLocation variantId) {
        return this.harvestTargets.contains(variantId);
    }

    // 该工具在指定变体上的幸运加成，未登记时为 0
    public double luckFor(ResourceLocation variantId) {
        DoubleSupplier luck = this.luckByVariant.get(variantId);
        return luck == null ? 0.0D : luck.getAsDouble();
    }
}
