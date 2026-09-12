package com.meteorite.unsuspiciousblock.platform;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.platform.services.IPanningConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fabric 淘盘配置实现——全局 JSON，位于 config/unsuspiciousblock/panning.json。
 * <p>
 * 文件缺失或字段缺失时写入默认值；越界值按接口常量夹取，保证运行期取到的始终是合法数值。
 */
public class FabricPanningConfig implements IPanningConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_DIR_NAME = "unsuspiciousblock";
    private static final String CONFIG_FILE_NAME = "panning.json";

    private final ConfigData data;

    public FabricPanningConfig() {
        this.data = loadConfig();
    }

    @Override
    public int getSpawnIntervalTicks() {
        return clamp(this.data.spawn_interval_ticks, DEFAULT_SPAWN_INTERVAL_TICKS,
                MIN_SPAWN_INTERVAL_TICKS, MAX_SPAWN_INTERVAL_TICKS);
    }

    @Override
    public int getMaxNaturalPerDimension() {
        return clamp(this.data.max_natural_per_dimension, DEFAULT_MAX_PER_DIMENSION,
                MIN_MAX_PER_DIMENSION, MAX_MAX_PER_DIMENSION);
    }

    @Override
    public int getMinLifetimeTicks() {
        return clamp(this.data.min_lifetime_ticks, DEFAULT_MIN_LIFETIME_TICKS,
                MIN_LIFETIME_TICKS, MAX_LIFETIME_TICKS);
    }

    @Override
    public int getMaxLifetimeTicks() {
        int min = getMinLifetimeTicks();
        return Math.max(min, clamp(this.data.max_lifetime_ticks, DEFAULT_MAX_LIFETIME_TICKS,
                MIN_LIFETIME_TICKS, MAX_LIFETIME_TICKS));
    }

    @Override
    public double getWorldgenChance() {
        double value = this.data.worldgen_chance != null ? this.data.worldgen_chance : DEFAULT_WORLDGEN_CHANCE;
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    @Override
    public int getPanDurationTicks() {
        return clamp(this.data.pan_duration_ticks, DEFAULT_PAN_DURATION_TICKS,
                MIN_PAN_DURATION_TICKS, MAX_PAN_DURATION_TICKS);
    }

    @Override
    public int getSpacingBlocks() {
        return clamp(this.data.spacing_blocks, DEFAULT_SPACING_BLOCKS,
                MIN_SPACING_BLOCKS, MAX_SPACING_BLOCKS);
    }

    @Override
    public int getPanUses() {
        return clamp(this.data.pan_uses, DEFAULT_PAN_USES, MIN_PAN_USES, MAX_PAN_USES);
    }

    private static int clamp(Integer value, int fallback, int min, int max) {
        int raw = value != null ? value : fallback;
        return Math.max(min, Math.min(max, raw));
    }

    // 读取配置；文件不存在或不可解析时写入一份默认配置
    private static ConfigData loadConfig() {
        Path path = configPath();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, java.nio.charset.StandardCharsets.UTF_8)) {
                ConfigData loaded = GSON.fromJson(reader, ConfigData.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (Exception exception) {
                Constants.LOG.error("无法解析 Fabric 淘盘配置 {}，将使用默认值", path, exception);
            }
        }
        ConfigData defaults = new ConfigData();
        save(path, defaults);
        return defaults;
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve(CONFIG_DIR_NAME)
                .resolve(CONFIG_FILE_NAME);
    }

    private static void save(Path path, ConfigData data) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, java.nio.charset.StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException exception) {
            Constants.LOG.error("无法写入 Fabric 淘盘配置 {}", path, exception);
        }
    }

    /**
     * 配置文件数据结构——字段名即 JSON 键名，缺失时回落到默认值。
     */
    private static final class ConfigData {
        private Integer spawn_interval_ticks = DEFAULT_SPAWN_INTERVAL_TICKS;
        private Integer max_natural_per_dimension = DEFAULT_MAX_PER_DIMENSION;
        private Integer min_lifetime_ticks = DEFAULT_MIN_LIFETIME_TICKS;
        private Integer max_lifetime_ticks = DEFAULT_MAX_LIFETIME_TICKS;
        private Double worldgen_chance = DEFAULT_WORLDGEN_CHANCE;
        private Integer pan_duration_ticks = DEFAULT_PAN_DURATION_TICKS;
        private Integer spacing_blocks = DEFAULT_SPACING_BLOCKS;
        private Integer pan_uses = DEFAULT_PAN_USES;
    }
}
