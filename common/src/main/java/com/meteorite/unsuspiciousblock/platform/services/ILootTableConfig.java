package com.meteorite.unsuspiciousblock.platform.services;

import java.util.List;

/** 战利品表和日志配置——提供追踪前缀列表和日志参数，由各平台实现并通过 ServiceLoader 注入 */
public interface ILootTableConfig {
    List<String> getArchaeologyPathPrefixes();

    /** 单表日志条目上限，默认 1024 */
    default int getMaxLogEntriesPerTable() {
        return 1024;
    }

    /** 战利品箱追踪超时（游戏刻），默认 6000（5 分钟） */
    default long getTrackingTimeoutTicks() {
        return 6000L;
    }
}
