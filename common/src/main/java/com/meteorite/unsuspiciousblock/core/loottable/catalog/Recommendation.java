package com.meteorite.unsuspiciousblock.core.loottable.catalog;

import com.meteorite.unsuspiciousblock.core.loottable.simulation.SimulationInput;

/**
 * 服务端对一条完整路径验证过的输入见证。
 */
public record Recommendation(SimulationInput input, Witness witness) {
    /**
     * 内容版本内的完整路径归属；不写入玩家偏好。
     */
    public record Witness(String branchReference, String targetKind, String target,
                          String itemSignature, String pathId) {
    }
}
