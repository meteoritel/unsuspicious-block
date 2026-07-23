package com.meteorite.unsuspiciousblock.platform.services;

import java.util.List;

/** 战利品表和日志配置——提供追踪前缀列表和日志参数，由各平台实现并通过 ServiceLoader 注入 */
public interface ILootTableConfig {

    // 单表日志条目上限的范围与默认值——Fabric / NeoForge 两端共用，确保校验一致
    int MIN_MAX_LOG_ENTRIES_PER_TABLE = 64;
    int MAX_MAX_LOG_ENTRIES_PER_TABLE = 4096;
    int DEFAULT_MAX_LOG_ENTRIES_PER_TABLE = 512;

    // 战利品箱追踪超时（游戏刻）的范围与默认值
    long MIN_TRACKING_TIMEOUT_TICKS = 600L;
    long MAX_TRACKING_TIMEOUT_TICKS = 60000L;
    long DEFAULT_TRACKING_TIMEOUT_TICKS = 6000L;

    // 默认追踪前缀列表--两端共享，确保重置行为一致
    List<String> DEFAULT_ARCHAEOLOGY_PATH_PREFIXES = List.of(
            "archaeology/", "archeology/", "gameplay/fishing/",
            "minecraft:gameplay/fishing", "pots/",
            "unsuspiciousblock:gameplay/fossil_hunter/"
    );

    List<String> getArchaeologyPathPrefixes();

    /** 单表日志条目上限，默认 512 */
    default int getMaxLogEntriesPerTable() {
        return DEFAULT_MAX_LOG_ENTRIES_PER_TABLE;
    }

    /** 战利品箱追踪超时（游戏刻），默认 6000（5 分钟） */
    default long getTrackingTimeoutTicks() {
        return DEFAULT_TRACKING_TIMEOUT_TICKS;
    }
}
