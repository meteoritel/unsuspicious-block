package com.meteorite.unsuspiciousblock.client.ui.panel;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 可分页面板的通用契约——统一 render、containsMouse 与分页操作 */
public interface PagePanel {

    void render(GuiGraphics graphics, Font font, int mouseX, int mouseY);

    boolean containsMouse(double mouseX, double mouseY);

    int pageCount();

    int getPage();

    void setPage(int page);

    void changePage(int delta);
}