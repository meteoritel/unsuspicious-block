package com.meteorite.unsuspiciousblock.core.loottable.simulation;

import java.util.Objects;

/** 模拟输入只选择场景和参数；条件赋值由服务端签发场景的 profile 提供。 */
public record SimulationInput(String scenarioKey, ScenarioParams params) {
    public SimulationInput {
        if (scenarioKey == null || scenarioKey.isBlank())
            throw new IllegalArgumentException("模拟输入的场景身份不可为空");
        Objects.requireNonNull(params, "params");
    }

    /** 缓存和网络共用的规范键。 */
    public String key() {
        return SimulationInputKey.of(scenarioKey, params);
    }
}
