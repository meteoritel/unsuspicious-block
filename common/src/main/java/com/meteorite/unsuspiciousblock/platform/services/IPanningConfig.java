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

    int DEFAULT_SPAWN_INTERVAL_TICKS = 600;
    int DEFAULT_MAX_PER_DIMENSION = 5;
    int DEFAULT_MIN_LIFETIME_TICKS = 24_000;
    int DEFAULT_MAX_LIFETIME_TICKS = 48_000;
    double DEFAULT_WORLDGEN_CHANCE = 0.02D;
    int DEFAULT_PAN_DURATION_TICKS = 100;
    int DEFAULT_SPACING_BLOCKS = 32;
    int DEFAULT_PAN_USES = 3;

    // 自然生成的两次尝试之间的间隔刻数
    int getSpawnIntervalTicks();

    // 每个维度同时存在的自然生成闪烁的光上限
    int getMaxNaturalPerDimension();

    // 自然生成的闪烁的光寿命下限（含）
    int getMinLifetimeTicks();

    // 自然生成的闪烁的光寿命上限（含）
    int getMaxLifetimeTicks();

    // 世界生成来源的闪烁的光在每个河流区块出现的概率
    double getWorldgenChance();

    // 单次淘洗需要长按的刻数
    int getPanDurationTicks();

    // 自然生成与现存闪烁的光之间需要保持的最小水平间距（格）
    int getSpacingBlocks();

    // 单个闪烁的光可被淘洗的次数
    int getPanUses();
}
