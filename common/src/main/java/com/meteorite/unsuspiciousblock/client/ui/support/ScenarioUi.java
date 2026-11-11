package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.meteosuikit.api.control.UiLightbox;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.style.UiControlStyle;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.Map;

/** 场景界面的轻量纸面容器、连线色与灯箱样式装配。 */
public final class ScenarioUi {
    public static final int BRANCH = 0xFFA99370;

    private ScenarioUi() {
    }

    /** 绘制带内页边线和装饰角标的羊皮纸浮层底板。 */
    public static void renderPage(GuiGraphics graphics, UiRect rect) {
        UiAtlas.SCENARIO_PANEL.render(graphics, rect);
        int left = rect.x() + 6;
        int top = rect.y() + 6;
        int right = rect.right() - 6;
        int bottom = rect.bottom() - 6;
        graphics.fill(left, top, right, top + 1, 0x40A99370);
        graphics.fill(left, bottom - 1, right, bottom, 0x40A99370);
        graphics.fill(left, top, left + 1, bottom, 0x28A99370);
        graphics.fill(right - 1, top, right, bottom, 0x28A99370);
        graphics.fill(left + 5, top + 4, left + 8, top + 5, 0x70A99370);
        graphics.fill(right - 8, bottom - 5, right - 5, bottom - 4, 0x70A99370);
    }

    /** 为场景条件灯箱注入羊皮纸主题、九宫格表面与图集按钮图标。 */
    public static void styleLightbox(UiLightbox lightbox) {
        lightbox.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT);
        lightbox.setTextColor(UiTextScheme.PARCHMENT.body());
        lightbox.setSurface(UiAtlas.SCENARIO_PANEL);
        lightbox.setIcons(Map.of(
                "close", UiAtlas.icon(UiAtlas.Icon.CLOSE),
                "fit", UiAtlas.icon(UiAtlas.Icon.FIT),
                "zoom_in", UiAtlas.icon(UiAtlas.Icon.PLUS),
                "zoom_out", UiAtlas.icon(UiAtlas.Icon.MINUS)));
        lightbox.setBarHint(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.simulation.frame.pan_hint"));
    }

    /** 提供安静选中底色供场景条件文档焦点行复用。 */
    public static int selectionHighlight() {
        return UiControlStyle.PARCHMENT.selectedBackground();
    }
}
