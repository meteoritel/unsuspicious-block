package com.meteorite.unsuspiciousblock.loot;

import net.minecraft.resources.ResourceLocation;

/**
 * 战利品表注入配置——Fabric 平台考古战利品替换的目标表 id 与概率常量。
 * <p>
 * Fabric 端通过 {@link com.meteorite.unsuspiciousblock.mixin.interaction.BrushableBlockEntityMixin}
 * 拦截 BrushableBlockEntity.unpackLootTable，按概率将原版战利品替换为模组物品。
 * NeoForge 端通过 GlobalLootModifier 实现等价语义，概率与目标表 id 在 JSON 中硬编码，
 * 此处的常量仅 Fabric 端使用，但与 NeoForge JSON 保持一致以便维护时对照。
 */
public final class LootInjection {

    private LootInjection() {
    }

    // 古迹废墟（普通）——原版考古战利品表
    public static final ResourceLocation TRAIL_RUINS_COMMON_ID =
            ResourceLocation.parse("minecraft:archaeology/trail_ruins_common");

    // 古迹废墟（稀有）——原版考古战利品表
    public static final ResourceLocation TRAIL_RUINS_RARE_ID =
            ResourceLocation.parse("minecraft:archaeology/trail_ruins_rare");

    // ancient_coin 注入到古迹废墟（普通）的触发概率
    public static final float ANCIENT_COIN_CHANCE = 0.05F;

    // lost_page 注入到古迹废墟（稀有）的触发概率
    public static final float LOST_PAGE_CHANCE = 0.08F;
}
