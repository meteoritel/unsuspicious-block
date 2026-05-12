package com.meteorite.unsuspiciousblock.platform.services;

import java.util.List;

/** 战利品表配置——提供追踪前缀列表，由各平台实现并通过 ServiceLoader 注入 */
public interface ILootTableConfig {
    List<String> getArchaeologyPathPrefixes();
}
