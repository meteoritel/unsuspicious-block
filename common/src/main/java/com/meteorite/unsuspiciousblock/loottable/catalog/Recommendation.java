package com.meteorite.unsuspiciousblock.loottable.catalog;
import com.meteorite.unsuspiciousblock.loottable.simulation.SimulationInput;
/** 服务端对一条完整路径验证过的输入见证。 */
public record Recommendation(SimulationInput input, int pathIndex) {}

