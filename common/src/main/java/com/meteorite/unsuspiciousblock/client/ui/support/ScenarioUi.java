package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.meteosuikit.api.control.UiLightbox;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.client.ui.theme.UiAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.ScenarioAtlas;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.Map;

/**
 * 场景界面的轻量纸面容器、连线色与灯箱样式装配。
 */
public final class ScenarioUi {
    private ScenarioUi() {
    }

    /**
     * 绘制带内页边线和装饰角标的羊皮纸浮层底板。
     */
    public static void renderPage(GuiGraphics graphics, UiRect rect) {
        ScenarioAtlas.PANEL.render(graphics, rect);
    }

    /**
     * 为场景条件灯箱注入羊皮纸主题、九宫格表面与图集按钮图标。
     */
    public static void styleLightbox(UiLightbox lightbox) {
        lightbox.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT);
        lightbox.setTextColor(UiTextScheme.PARCHMENT.body());
        lightbox.setSurface(ScenarioAtlas.PANEL);
        lightbox.setIcons(Map.of(
                "close", UiAtlas.icon(UiAtlas.Icon.CLOSE),
                "fit", UiAtlas.icon(UiAtlas.Icon.RESET),
                "pan_lock", UiAtlas.icon(UiAtlas.Icon.PAN_LOCK),
                "pan_unlock", UiAtlas.icon(UiAtlas.Icon.PAN_UNLOCK),
                "zoom_in", UiAtlas.icon(UiAtlas.Icon.PLUS),
                "zoom_out", UiAtlas.icon(UiAtlas.Icon.MINUS)));
        lightbox.setPanLockControlVisible(true);
        lightbox.setBarHint(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.simulation.frame.pan_hint"));
    }
}
