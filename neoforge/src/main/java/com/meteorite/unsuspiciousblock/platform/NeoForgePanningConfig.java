package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.platform.services.IPanningConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge 淘盘配置实现——使用独立的 SERVER spec，由服务端决定淘洗点生成与淘洗节奏。
 */
public class NeoForgePanningConfig implements IPanningConfig {

    private static final ModConfigSpec.IntValue SPAWN_INTERVAL_TICKS;
    private static final ModConfigSpec.IntValue MAX_NATURAL_PER_DIMENSION;
    private static final ModConfigSpec.IntValue MIN_LIFETIME_TICKS;
    private static final ModConfigSpec.IntValue MAX_LIFETIME_TICKS;
    private static final ModConfigSpec.DoubleValue WORLDGEN_CHANCE;
    private static final ModConfigSpec.IntValue PAN_DURATION_TICKS;
    private static final ModConfigSpec.IntValue SPACING_BLOCKS;
    private static final ModConfigSpec.IntValue PAN_USES;

    public static final ModConfigSpec SERVER_CONFIG_SPEC;

    // 同一 mod 的同类配置默认文件名相同（<modid>-server.toml），注册第二个 SERVER spec 时必须显式指定文件名，
    // 否则 ConfigTracker 会判定配置文件冲突并中断 mod 构造
    public static final String SERVER_CONFIG_FILE_NAME = "unsuspiciousblock-panning-server.toml";

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("spawn");
        SPAWN_INTERVAL_TICKS = builder
                .comment("自然生成闪烁的光的尝试间隔（游戏刻）。默认 "
                        + IPanningConfig.DEFAULT_SPAWN_INTERVAL_TICKS + "（30 秒）。",
                        "",
                        "Interval between natural Shimmer spawn attempts, in ticks. Default "
                        + IPanningConfig.DEFAULT_SPAWN_INTERVAL_TICKS + " (30 seconds).")
                .translation("unsuspiciousblock.configgui.panning.spawn_interval_ticks")
                .defineInRange("spawn_interval_ticks", IPanningConfig.DEFAULT_SPAWN_INTERVAL_TICKS,
                        IPanningConfig.MIN_SPAWN_INTERVAL_TICKS, IPanningConfig.MAX_SPAWN_INTERVAL_TICKS);
        MAX_NATURAL_PER_DIMENSION = builder
                .comment("每个维度同时存在的自然生成闪烁的光上限。达到上限后不再尝试生成。默认 "
                        + IPanningConfig.DEFAULT_MAX_PER_DIMENSION + "。",
                        "",
                        "Max natural Shimmer per dimension. Spawn attempts stop while the cap is reached. Default "
                        + IPanningConfig.DEFAULT_MAX_PER_DIMENSION + ".")
                .translation("unsuspiciousblock.configgui.panning.max_natural_per_dimension")
                .defineInRange("max_natural_per_dimension", IPanningConfig.DEFAULT_MAX_PER_DIMENSION,
                        IPanningConfig.MIN_MAX_PER_DIMENSION, IPanningConfig.MAX_MAX_PER_DIMENSION);
        SPACING_BLOCKS = builder
                .comment("自然生成与现存闪烁的光之间需要保持的最小水平间距（格）。默认 "
                        + IPanningConfig.DEFAULT_SPACING_BLOCKS + "。",
                        "",
                        "Minimum horizontal spacing between a new natural Shimmer and any existing one, in blocks. Default "
                        + IPanningConfig.DEFAULT_SPACING_BLOCKS + ".")
                .translation("unsuspiciousblock.configgui.panning.spacing_blocks")
                .defineInRange("spacing_blocks", IPanningConfig.DEFAULT_SPACING_BLOCKS,
                        IPanningConfig.MIN_SPACING_BLOCKS, IPanningConfig.MAX_SPACING_BLOCKS);
        WORLDGEN_CHANCE = builder
                .comment("世界生成时，含河流开阔水域的区块出现闪烁的光的概率。默认 "
                        + IPanningConfig.DEFAULT_WORLDGEN_CHANCE + "。",
                        "",
                        "Chance for a river chunk to receive a world-gen Shimmer when first loaded. Default "
                        + IPanningConfig.DEFAULT_WORLDGEN_CHANCE + ".")
                .translation("unsuspiciousblock.configgui.panning.worldgen_chance")
                .defineInRange("worldgen_chance", IPanningConfig.DEFAULT_WORLDGEN_CHANCE, 0.0D, 1.0D);
        builder.pop();

        builder.push("lifetime");
        MIN_LIFETIME_TICKS = builder
                .comment("自然生成闪烁的光的寿命下限（游戏刻），生成时即固定。默认 "
                        + IPanningConfig.DEFAULT_MIN_LIFETIME_TICKS + "（20 分钟）。",
                        "",
                        "Minimum natural Shimmer lifetime in ticks, rolled at spawn. Default "
                        + IPanningConfig.DEFAULT_MIN_LIFETIME_TICKS + " (20 minutes).")
                .translation("unsuspiciousblock.configgui.panning.min_lifetime_ticks")
                .defineInRange("min_lifetime_ticks", IPanningConfig.DEFAULT_MIN_LIFETIME_TICKS,
                        IPanningConfig.MIN_LIFETIME_TICKS, IPanningConfig.MAX_LIFETIME_TICKS);
        MAX_LIFETIME_TICKS = builder
                .comment("自然生成闪烁的光的寿命上限（游戏刻），生成时即固定。默认 "
                        + IPanningConfig.DEFAULT_MAX_LIFETIME_TICKS + "（40 分钟）。",
                        "",
                        "Maximum natural Shimmer lifetime in ticks, rolled at spawn. Default "
                        + IPanningConfig.DEFAULT_MAX_LIFETIME_TICKS + " (40 minutes).")
                .translation("unsuspiciousblock.configgui.panning.max_lifetime_ticks")
                .defineInRange("max_lifetime_ticks", IPanningConfig.DEFAULT_MAX_LIFETIME_TICKS,
                        IPanningConfig.MIN_LIFETIME_TICKS, IPanningConfig.MAX_LIFETIME_TICKS);
        builder.pop();

        builder.push("panning");
        PAN_DURATION_TICKS = builder
                .comment("单次淘洗需要长按的刻数。默认 "
                        + IPanningConfig.DEFAULT_PAN_DURATION_TICKS + "（5 秒）。",
                        "",
                        "Ticks of holding right-click required per pan. Default "
                        + IPanningConfig.DEFAULT_PAN_DURATION_TICKS + " (5 seconds).")
                .translation("unsuspiciousblock.configgui.panning.pan_duration_ticks")
                .defineInRange("pan_duration_ticks", IPanningConfig.DEFAULT_PAN_DURATION_TICKS,
                        IPanningConfig.MIN_PAN_DURATION_TICKS, IPanningConfig.MAX_PAN_DURATION_TICKS);
        PAN_USES = builder
                .comment("单个闪烁的光可被淘洗的次数。默认 " + IPanningConfig.DEFAULT_PAN_USES + "。",
                        "",
                        "Pan uses available on a single Shimmer. Default "
                        + IPanningConfig.DEFAULT_PAN_USES + ".")
                .translation("unsuspiciousblock.configgui.panning.pan_uses")
                .defineInRange("pan_uses", IPanningConfig.DEFAULT_PAN_USES,
                        IPanningConfig.MIN_PAN_USES, IPanningConfig.MAX_PAN_USES);
        builder.pop();

        SERVER_CONFIG_SPEC = builder.build();
    }

    @Override
    public int getSpawnIntervalTicks() {
        return SPAWN_INTERVAL_TICKS.get();
    }

    @Override
    public int getMaxNaturalPerDimension() {
        return MAX_NATURAL_PER_DIMENSION.get();
    }

    @Override
    public int getMinLifetimeTicks() {
        return MIN_LIFETIME_TICKS.get();
    }

    @Override
    public int getMaxLifetimeTicks() {
        return Math.max(getMinLifetimeTicks(), MAX_LIFETIME_TICKS.get());
    }

    @Override
    public double getWorldgenChance() {
        return WORLDGEN_CHANCE.get();
    }

    @Override
    public int getPanDurationTicks() {
        return PAN_DURATION_TICKS.get();
    }

    @Override
    public int getSpacingBlocks() {
        return SPACING_BLOCKS.get();
    }

    @Override
    public int getPanUses() {
        return PAN_USES.get();
    }
}
