package com.meteorite.unsuspiciousblock.client.ui.support;

import net.minecraft.nbt.CompoundTag;

/** 使用独立 NBT 子树保存和恢复自身 UI 状态的面板。 */
public interface UiStateful {
    void saveUiState(CompoundTag tag);
    void loadUiState(CompoundTag tag);
}
