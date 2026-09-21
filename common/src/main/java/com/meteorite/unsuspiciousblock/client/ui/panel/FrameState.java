package com.meteorite.unsuspiciousblock.client.ui.panel;

import com.meteorite.unsuspiciousblock.client.ui.kit.UiTransform;
import net.minecraft.nbt.CompoundTag;

/**
 * 单个场景在框内的视图状态：缩放档下标与内容单位下的平移量。
 * 仅描述“看到哪里”，不含任何业务数据，便于随面板状态一起持久化。
 */
record FrameState(int zoomIndex, double panX, double panY) {
    static final FrameState DEFAULT = new FrameState(1, 0.0, 0.0);

    FrameState {
        zoomIndex = Math.clamp(zoomIndex, 0, 3);
        panX = Double.isFinite(panX) ? Math.clamp(panX, -1_000_000, 1_000_000) : 0;
        panY = Double.isFinite(panY) ? Math.clamp(panY, -1_000_000, 1_000_000) : 0;
    }

    static FrameState capture(UiTransform transform) {
        return new FrameState(transform.zoomIndex(), transform.panX(), transform.panY());
    }

    void apply(UiTransform transform) { transform.restore(zoomIndex, panX, panY); }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("zoom", zoomIndex);
        tag.putDouble("x", panX);
        tag.putDouble("y", panY);
        return tag;
    }

    static FrameState load(CompoundTag tag) {
        return new FrameState(tag.contains("zoom") ? tag.getInt("zoom") : 1,
                tag.getDouble("x"), tag.getDouble("y"));
    }
}
