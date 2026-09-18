package com.meteorite.unsuspiciousblock.platform.services;

/**
 * 淘盘淘洗服务端配置，由各平台配置系统提供闪烁的光生成、寿命与淘洗节奏参数。
 * <p>
 * 所有时间量单位为游戏刻（20 刻 = 1 秒）。这些数值只影响玩法强度，不参与存档语义。
 */
public interface IPanningConfig {
    int MIN_SPAWN_INTERVAL_TICKS = 100;
    int MAX_SPAWN_INTERVAL_TICKS = 72_000;
    int MIN_MAX_PER_DIMENSION = 0;
    int MAX_MAX_PER_DIMENSION = 64;
    int MIN_LIFETIME_TICKS = 200;
    int MAX_LIFETIME_TICKS = 1_728_000;
    int MIN_PAN_DURATION_TICKS = 10;
    int MAX_PAN_DURATION_TICKS = 400;
    int MIN_SPACING_BLOCKS = 0;
    int MAX_SPACING_BLOCKS = 512;
    int MIN_PAN_USES = 1;
    int MAX_PAN_USES = 16;

    // 工具幸运加成与再生概率的取值范围。幸运是无量纲的原版幸运值，不是百分比。
    double MIN_PAN_LUCK_MODIFIER = 0.0D;
    double MAX_PAN_LUCK_MODIFIER = 5.0D;
    double MIN_REGENERATION_CHANCE = 0.0D;
    double MAX_REGENERATION_CHANCE = 1.0D;

    int DEFAULT_SPAWN_INTERVAL_TICKS = 600;
    int DEFAULT_MAX_PER_DIMENSION = 5;
    int DEFAULT_MIN_LIFETIME_TICKS = 24_000;
    int DEFAULT_MAX_LIFETIME_TICKS = 48_000;
    int DEFAULT_PAN_DURATION_TICKS = 100;
    int DEFAULT_SPACING_BLOCKS = 32;
    int DEFAULT_PAN_USES = 3;
    int DEFAULT_HARVEST_COOLDOWN_TICKS = 36_000;
    int MAX_HARVEST_COOLDOWN_TICKS = 1_728_000;
    double DEFAULT_GOLD_PAN_LUCK_BONUS = 1.0D;
    double DEFAULT_OBSIDIAN_PAN_LUCK_PENALTY = 0.5D;
    double DEFAULT_GOLD_PAN_REGENERATION_CHANCE = 0.10D;

    // 自然生成的两次尝试之间的间隔刻数
    int getSpawnIntervalTicks();

    // 每个维度同时存在的自然生成闪烁的光上限
    int getMaxNaturalPerDimension();

    // 自然生成的闪烁的光寿命下限（含）
    int getMinLifetimeTicks();

    // 自然生成的闪烁的光寿命上限（含）
    int getMaxLifetimeTicks();

    // 单次淘洗需要长按的刻数
    int getPanDurationTicks();

    // 自然生成与现存闪烁的光之间需要保持的最小水平间距（格）
    int getSpacingBlocks();

    // 采空后周围 3×3 区块的生成冷却（绝对游戏时间）。
    int getHarvestCooldownTicks();

    // 单个闪烁的光可被淘洗的次数
    int getPanUses();

    // 金淘盘在水域变体上的幸运加成（正值提高稀有产出权重）
    double getGoldPanLuckBonus();

    // 黑曜石淘盘在水域变体上的幸运减损（正值表示从幸运中扣除，让水中沉积物的产出变差）
    double getObsidianPanLuckPenalty();

    // 金淘盘单次淘洗后再生一个新的自然淘洗点的概率
    double getGoldPanRegenerationChance();
}
