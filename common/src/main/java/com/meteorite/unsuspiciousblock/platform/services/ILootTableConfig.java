package com.meteorite.unsuspiciousblock.platform.services;

import net.minecraft.server.MinecraftServer;

import java.util.List;

/**
 * 服务端战利品表和日志配置——提供按世界生效的追踪规则与日志参数。
 */
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
            "archaeology/",
            "archeology/",
            "pots/",
            "minecraft:gameplay/fishing",
            "minecraft:chests/buried_treasure",
            "minecraft:chests/ancient_city",
            "minecraft:chests/ancient_city_ice_box",
            "unsuspiciousblock:gameplay/fishing/",
            "unsuspiciousblock:gameplay/fossil_hunter/"
    );

    List<String> getArchaeologyPathPrefixes();

    // 服务端启动时加载当前世界配置；原生支持 SERVER 配置的平台可保持默认实现
    default void loadForServer(MinecraftServer server) {
    }

    // 服务端停止时释放当前世界配置，避免集成服务器切换存档后沿用旧值
    default void unloadServer() {
    }

    /** 单表日志条目上限，默认 512 */
    default int getMaxLogEntriesPerTable() {
        return DEFAULT_MAX_LOG_ENTRIES_PER_TABLE;
    }

    /** 战利品箱追踪超时（游戏刻），默认 6000（5 分钟） */
    default long getTrackingTimeoutTicks() {
        return DEFAULT_TRACKING_TIMEOUT_TICKS;
    }
}
