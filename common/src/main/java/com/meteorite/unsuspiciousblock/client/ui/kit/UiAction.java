package com.meteorite.unsuspiciousblock.client.ui.kit;

/** 面板提供的点击行为；排版层只保存行为，调用方负责执行与输入优先级。 */
@FunctionalInterface
public interface UiAction {
    void run();
}
