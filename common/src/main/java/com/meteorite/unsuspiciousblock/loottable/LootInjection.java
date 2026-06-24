package com.meteorite.unsuspiciousblock.loottable;

import net.minecraft.resources.ResourceLocation;

/**
 * 战利品表注入配置——跨平台共享的目标表 id 与概率常量。
 * <p>
 * NeoForge 与 Fabric 平台分别通过 Global Loot Modifier 与 LootTableEvents.MODIFY
 * 注入条目，但二者使用的目标表 id、概率、数量需要保持一致，统一在此定义。
 */
public final class LootInjection {

    private LootInjection() {
    }

    // 古迹废墟（普通）——原版考古战利品表
    public static final ResourceLocation TRAIL_RUINS_COMMON_ID =
            ResourceLocation.parse("minecraft:archaeology/trail_ruins");

    // 古迹废墟（稀有）——原版考古战利品表
    public static final ResourceLocation TRAIL_RUINS_RARE_ID =
            ResourceLocation.parse("minecraft:archaeology/trail_ruins_rare");

    // ancient_coin 注入到古迹废墟（普通）的触发概率
    public static final float ANCIENT_COIN_CHANCE = 0.05F;

    // lost_page 注入到古迹废墟（稀有）的触发概率
    public static final float LOST_PAGE_CHANCE = 0.08F;
}
