package com.meteorite.unsuspiciousblock.loottable.simulation;

import net.minecraft.resources.ResourceLocation;

/** 辅助请求的显式目标；子表入口与物品签名不共享隐式字符串约定。 */
public record SimulationAssistTarget(Kind kind, String value) {
    /** 线协议使用枚举序号，调整顺序需要同步协议版本。 */
    public enum Kind { PLAYER_STATE, ITEM, CHILD_TABLE }

    public static SimulationAssistTarget item(String signature) {
        return new SimulationAssistTarget(Kind.ITEM, signature);
    }

    public static SimulationAssistTarget childTable(ResourceLocation table) {
        return new SimulationAssistTarget(Kind.CHILD_TABLE, table.toString());
    }

    public boolean recommendation() { return kind != Kind.PLAYER_STATE; }
}
