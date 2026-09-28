package com.meteorite.unsuspiciousblock.client.ui.panel;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 可分页面板的通用契约——统一 render、containsMouse 与分页操作 */
public interface PagePanel {

    void render(GuiGraphics graphics, Font font, int mouseX, int mouseY);

    boolean containsMouse(double mouseX, double mouseY);

    /**
     * 右页板的命中判定——五个面板共用一份边界语义，避免各自实现漂移。
     *
     * <p>边界口径选**半开区间** {@code [left, right) × [top, bottom)}：整像素矩形覆盖
     * {@code x..right-1}，坐标恰落在 {@code right}/{@code bottom} 时属于相邻元素，
     * 不应被本面板重复占据。这与 {@code ScenarioDetailPanel} 原有写法一致，也与包内其它像素矩形命中
     * （{@code CatalogPanel.Rect.contains}、{@code ItemGridPanel.isMouseOverCell}、
     * {@code ScenarioPanel.inside}）一致；原先另外四处的闭区间 {@code <=} 会让右/下边界像素
     * 同时命中相邻区域（例如页脚与书本右缘、面板与分页按钮）。</p>
     */
    static boolean containsPageBounds(double mouseX, double mouseY, int left, int top, int right, int bottom) {
        return mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom;
    }

    int pageCount();

    int getPage();

    void setPage(int page);

    void changePage(int delta);
}