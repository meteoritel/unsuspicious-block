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

/** Fabric 战利品表配置——使用 GSON / JSON，规则说明见同目录 README.txt */
public class FabricLootTableConfig implements ILootTableConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_DIR_NAME = "unsuspiciousblock";
    private static final String CONFIG_FILE_NAME = "unsuspiciousblock.json";
    private static final String README_CN_FILE_NAME = "README_CN.txt";
    private static final String README_EN_FILE_NAME = "README_EN.txt";
    private static final List<String> DEFAULT_PREFIXES = List.of(
            "archaeology/", "archeology/", "gameplay/fishing/",
            "unsuspiciousblock:gameplay/fossil_hunter/");
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
        // 同步生成中英文 README（缺失时补写），用于弥补 JSON 无注释的限制
        ensureReadme(getConfigDir().resolve(README_CN_FILE_NAME), buildReadmeCn());
        ensureReadme(getConfigDir().resolve(README_EN_FILE_NAME), buildReadmeEn());
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

    /** 缺失时写入指定 README；已存在则保留玩家可能添加的自定义备注 */
    private static void ensureReadme(Path readmePath, String content) {
        if (Files.exists(readmePath)) {
            return;
        }
        try {
            Files.createDirectories(readmePath.getParent());
            Files.writeString(readmePath, content);
        } catch (IOException e) {
            Constants.LOG.warn("Failed to write loot table config readme at {}", readmePath, e);
        }
    }

    private static String buildReadmeCn() {
        return """
                unsuspiciousblock 战利品表配置说明（中文）
                =========================================

                本目录下的 unsuspiciousblock.json 为本模组战利品表追踪配置文件。
                由于 JSON 不支持注释，规则说明统一写在本文件中。英文版本见 README_EN.txt。

                字段说明
                --------

                archaeology_path_prefixes
                    需要追踪的战利品表匹配规则列表。
                    语法：[命名空间:]路径
                      - 指定命名空间时仅匹配该命名空间下的表，例如：
                          minecraft:archaeology/desert_well    仅匹配该单表
                          unsuspiciousblock:archaeology/       匹配本模组 archaeology/ 前缀下所有表
                      - 省略命名空间时匹配所有命名空间，例如：
                          archaeology/                         匹配任意命名空间下 archaeology/ 前缀的所有表
                    路径以 / 结尾为前缀匹配（命中该前缀下所有表），否则为精确匹配（仅命中单个表）。
                    默认值：
                      "archaeology/"
                      "archeology/"
                      "gameplay/fishing/"
                      "unsuspiciousblock:gameplay/fossil_hunter/"

                max_log_entries_per_table
                    单张战利品表保留的日志条目上限。超出后自动丢弃最旧条目。
                    取值范围：64 – 4096。默认 1024。

                tracking_timeout_ticks
                    战利品箱追踪超时（游戏刻）。超时后自动结算并清除追踪状态。
                    取值范围：600 – 60000。默认 6000（5 分钟）。
                """;
    }

    private static String buildReadmeEn() {
        return """
                unsuspiciousblock Loot Table Config (English)
                ==============================================

                The file unsuspiciousblock.json in this directory is this mod's loot table
                tracking config. Since JSON does not support comments, the rules are
                documented here. Chinese version: README_CN.txt.

                Fields
                ------

                archaeology_path_prefixes
                    List of loot table matching rules to track.
                    Syntax: [namespace:]path
                      - With a namespace, only that namespace is matched, e.g.:
                          minecraft:archaeology/desert_well    only this single table
                          unsuspiciousblock:archaeology/       all tables under this mod's archaeology/ prefix
                      - Without a namespace, all namespaces are matched, e.g.:
                          archaeology/                         all tables under the archaeology/ prefix in any namespace
                    Path ending with / is a prefix match (all tables under that prefix);
                    otherwise an exact match (single table).
                    Defaults:
                      "archaeology/"
                      "archeology/"
                      "gameplay/fishing/"
                      "unsuspiciousblock:gameplay/fossil_hunter/"

                max_log_entries_per_table
                    Max log entries kept per loot table. Oldest entries are dropped when exceeded.
                    Range: 64 – 4096. Default 1024.

                tracking_timeout_ticks
                    Loot container tracking timeout (ticks). Auto-settles and clears tracking state on expiry.
                    Range: 600 – 60000. Default 6000 (5 minutes).
                """;
    }

    private static Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_DIR_NAME);
    }

    private static Path getConfigPath() {
        return getConfigDir().resolve(CONFIG_FILE_NAME);
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
