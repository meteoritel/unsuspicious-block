package com.meteorite.unsuspiciousblock.pan.variant;

/***
 * 摇洗帧组——物品属性 {@code panning_medium} 的取值，决定该介质使用哪一套摇洗序列帧。
 * <p>
 * 序号即物品属性的数值，因此新增介质需要同时补齐对应的帧模型与帧贴图。
 */
public enum PanningMedium {
    // 水域介质：水与冰共用一套帧
    WATER,
    // 岩浆介质：幽微的光专用帧
    LAVA
}
