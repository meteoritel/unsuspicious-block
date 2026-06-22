package com.meteorite.unsuspiciousblock.platform;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.platform.services.ILootTableConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Fabric 战利品表配置——使用 GSON / JSON */
public class FabricLootTableConfig implements ILootTableConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE_NAME = "unsuspiciousblock.json";
    private static final List<String> DEFAULT_PREFIXES = List.of("archaeology/", "archeology/", "gameplay/fishing/");
    private static final int DEFAULT_MAX_LOG_ENTRIES_PER_TABLE = 1024;
    private static final long DEFAULT_TRACKING_TIMEOUT_TICKS = 6000L;

    private final List<String> prefixes;
    private final int maxLogEntriesPerTable;
    private final long trackingTimeoutTicks;

    public FabricLootTableConfig() {
        ConfigData data = loadConfig();
        this.prefixes = new ArrayList<>(data.archaeology_path_prefixes != null && !data.archaeology_path_prefixes.isEmpty()
                ? data.archaeology_path_prefixes : DEFAULT_PREFIXES);
        this.maxLogEntriesPerTable = data.max_log_entries_per_table > 0
                ? data.max_log_entries_per_table : DEFAULT_MAX_LOG_ENTRIES_PER_TABLE;
        this.trackingTimeoutTicks = data.tracking_timeout_ticks >= 600L
                ? data.tracking_timeout_ticks : DEFAULT_TRACKING_TIMEOUT_TICKS;
    }

    @Override
    public List<String> getArchaeologyPathPrefixes() {
        return this.prefixes;
    }

    @Override
    public int getMaxLogEntriesPerTable() {
        return this.maxLogEntriesPerTable;
    }

    @Override
    public long getTrackingTimeoutTicks() {
        return this.trackingTimeoutTicks;
    }

    private static ConfigData loadConfig() {
        Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            saveDefaults(configPath);
            return new ConfigData(DEFAULT_PREFIXES, DEFAULT_MAX_LOG_ENTRIES_PER_TABLE, DEFAULT_TRACKING_TIMEOUT_TICKS);
        }

        try (Reader reader = Files.newBufferedReader(configPath)) {
            ConfigData data = GSON.fromJson(reader, ConfigData.class);
            if (data != null && data.archaeology_path_prefixes != null && !data.archaeology_path_prefixes.isEmpty()) {
                return data;
            }
        } catch (IOException e) {
            Constants.LOG.warn("Failed to read loot table config from {}, using defaults.", configPath, e);
        }
        return new ConfigData(DEFAULT_PREFIXES, DEFAULT_MAX_LOG_ENTRIES_PER_TABLE, DEFAULT_TRACKING_TIMEOUT_TICKS);
    }

    private static void saveDefaults(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                GSON.toJson(new ConfigData(DEFAULT_PREFIXES, DEFAULT_MAX_LOG_ENTRIES_PER_TABLE, DEFAULT_TRACKING_TIMEOUT_TICKS), writer);
            }
            Constants.LOG.info("Created default loot table config at {}", configPath);
        } catch (IOException e) {
            Constants.LOG.warn("Failed to create default loot table config at {}", configPath, e);
        }
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
    }

    private static final class ConfigData {
        @SuppressWarnings("unused")
        List<String> archaeology_path_prefixes;
        @SuppressWarnings("unused")
        int max_log_entries_per_table;
        @SuppressWarnings("unused")
        long tracking_timeout_ticks;

        ConfigData(List<String> archaeology_path_prefixes,
                   int max_log_entries_per_table,
                   long tracking_timeout_ticks) {
            this.archaeology_path_prefixes = archaeology_path_prefixes;
            this.max_log_entries_per_table = max_log_entries_per_table;
            this.tracking_timeout_ticks = tracking_timeout_ticks;
        }
    }
}
