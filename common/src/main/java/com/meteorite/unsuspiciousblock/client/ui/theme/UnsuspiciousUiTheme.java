package com.meteorite.unsuspiciousblock.client.ui.theme;

import com.meteorite.meteosuikit.api.number.UiSliderRole;
import com.meteorite.meteosuikit.api.number.UiSliderSkin;
import com.meteorite.meteosuikit.api.style.*;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;

/** 模组暗色页面与书本共用的主题来源，宿主提供语义色。 */
public enum UnsuspiciousUiTheme implements UiStyleSource {
    DARK(UiMinecraftTheme.INSTANCE, UiSurfaceStyle.MINECRAFT.withText(
            UiTextPalette.Dark.BODY, UiTextPalette.Dark.LABEL, UiTextPalette.Dark.HINT)),
    PARCHMENT(new PaperStyle(), UiSurfaceStyle.MINECRAFT.withText(
            UiTextPalette.Parchment.BODY, UiTextPalette.Parchment.LABEL, UiTextPalette.Parchment.HINT)
            .withInput(0xFFD8C0A0, 0xFF8B6914, UiTextPalette.Parchment.BODY));

    private final UiStyleSource controls;
    private final UiSurfaceStyle colors;
    UnsuspiciousUiTheme(UiStyleSource controls, UiSurfaceStyle colors) { this.controls = controls; this.colors = colors; }
    @Override public UiControlStyle controlStyle(UiControlRole role) { return controls.controlStyle(role); }
    @Override public UiControlSkin controlSkin(UiControlRole role) { return controls.controlSkin(role); }
    @Override public UiSliderSkin sliderSkin(UiSliderRole role) { return controls.sliderSkin(role); }
    @Override public UiSurfaceStyle surfaces() { return colors; }

    /** 书本结构色与 kit 的标准控件画法。 */
    private static final class PaperStyle implements UiStyleSource {
        @Override public UiControlStyle controlStyle(UiControlRole role) { return UiControlStyle.PARCHMENT; }
        @Override public UiSliderSkin sliderSkin(UiSliderRole role) { return UiMinecraftTheme.INSTANCE.sliderSkin(role); }
        @Override public int controlTextColor(UiControlRole role) { return UiTextPalette.Parchment.BODY; }
    }
}
