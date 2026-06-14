package com.meteorite.unsuspiciousblock.platform.services;

import java.util.List;

/** 战利品表和日志配置——提供追踪前缀列表和日志参数，由各平台实现并通过 ServiceLoader 注入 */
public interface ILootTableConfig {
    List<String> getArchaeologyPathPrefixes();

    /** 单表日志条目上限，默认 1024 */
    default int getMaxLogEntriesPerTable() {
        return 1024;
    }

    /** "缓存大师"成就的日志总条数门槛，默认 1024 */
    default int getCacheMeIfYouCanThreshold() {
        return 1024;
    }
}
