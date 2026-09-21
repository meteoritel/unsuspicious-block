package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.client.ui.JournalBookBackground.BookLayout;
import com.meteorite.unsuspiciousblock.client.ui.layout.LayoutAware;
import net.minecraft.nbt.CompoundTag;

import java.util.LinkedHashMap;
import java.util.Map;

/** 新面板只注册一次，屏幕统一遍历布局与状态；旧面板保持原有生命周期。 */
public final class UiPanelRegistry implements LayoutAware, UiStateful {
    private final Map<String, Object> panels = new LinkedHashMap<>();

    public <T extends LayoutAware & UiStateful> T register(String id, T panel) {
        if (panels.putIfAbsent(id, panel) != null) throw new IllegalArgumentException("Duplicate panel: " + id);
        return panel;
    }

    public void clear() { panels.clear(); }

    @Override public void applyLayout(BookLayout layout) {
        panels.values().forEach(panel -> ((LayoutAware) panel).applyLayout(layout));
    }

    @Override public void saveUiState(CompoundTag tag) {
        panels.forEach((id, panel) -> {
            CompoundTag state = new CompoundTag();
            ((UiStateful) panel).saveUiState(state);
            tag.put(id, state);
        });
    }

    @Override public void loadUiState(CompoundTag tag) {
        panels.forEach((id, panel) -> ((UiStateful) panel).loadUiState(tag.getCompound(id)));
    }

    public CompoundTag snapshot() {
        CompoundTag tag = new CompoundTag();
        saveUiState(tag);
        return tag;
    }
}
