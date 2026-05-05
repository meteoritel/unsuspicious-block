package com.meteorite.unsuspiciousblock.client.ui.widget;

import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 左侧目录选择列表，基于 ObjectSelectionList */
@Deprecated
public class CatalogSelectionList extends ObjectSelectionList<CatalogEntryButton> {

    private final List<CatalogEntryButton> entryButtons = new ArrayList<>();
    private Consumer<Integer> selectionCallback;

    public CatalogSelectionList(Minecraft mc, int width, int height, int y) {
        super(mc, width, height, y, JournalLayout.CATALOG_ROW_HEIGHT);
        this.setRenderHeader(false, 0);
        this.centerListVertically = false;
    }

    public void setSelectionCallback(Consumer<Integer> callback) {
        this.selectionCallback = callback;
    }

    public void setCatalogEntries(List<CatalogEntryData> entries, Font font, int selectedIndex) {
        this.clearEntries();
        this.entryButtons.clear();
        for (CatalogEntryData entry : entries) {
            CatalogEntryButton btn = new CatalogEntryButton(entry.id(), entry.displayName(), entry.unlocked(), font);
            this.entryButtons.add(btn);
            this.addEntry(btn);
        }
        if (selectedIndex >= 0 && selectedIndex < entryButtons.size()) {
            this.setSelected(entryButtons.get(selectedIndex));
            this.ensureVisible(entryButtons.get(selectedIndex));
        }
    }

    public int getSelectedIndex() {
        CatalogEntryButton sel = this.getSelected();
        if (sel == null) return -1;
        return entryButtons.indexOf(sel);
    }

    @Override
    public void setSelected(CatalogEntryButton entry) {
        super.setSelected(entry);
        if (selectionCallback != null) {
            selectionCallback.accept(getSelectedIndex());
        }
    }

    @Override
    public int getRowWidth() {
        return this.width - JournalLayout.CATALOG_SCROLLBAR_WIDTH - 6;
    }

    @Override
    protected void renderListBackground(@NotNull GuiGraphics guiGraphics) {
        // 书页已提供背景，不绘制列表默认背景
    }

    @Override
    protected void renderListSeparators(@NotNull GuiGraphics guiGraphics) {
        // 书页不需要顶/底部分隔线
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    public boolean containsMouse(double mouseX, double mouseY) {
        return this.isMouseOver(mouseX, mouseY);
    }

    /** 用于外部设置条目的简化 record（避免直接依赖 CatalogEntry） */
    public record CatalogEntryData(ResourceLocation id, Component displayName, boolean unlocked) {}
}
