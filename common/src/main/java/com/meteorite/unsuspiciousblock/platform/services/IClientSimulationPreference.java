package com.meteorite.unsuspiciousblock.platform.services;
import java.util.Map;
/** 客户端模拟选择的本地存储接口；不参与世界数据和玩家间同步。 */
public interface IClientSimulationPreference {
    Map<String, String> load();
    void save(Map<String, String> values);
}

