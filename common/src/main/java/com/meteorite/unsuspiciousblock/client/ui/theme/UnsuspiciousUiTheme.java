package com.meteorite.unsuspiciousblock.client.ui.theme;

import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiControlSkin;
import com.meteorite.meteosuikit.api.style.UiControlStyle;
import com.meteorite.meteosuikit.api.style.UiSliderRole;
import com.meteorite.meteosuikit.api.style.UiSliderSkin;
import com.meteorite.meteosuikit.api.style.UiStyleSource;
import com.meteorite.meteosuikit.api.style.UiSurfaceRole;
import com.meteorite.meteosuikit.api.style.UiSurfaceSkin;
import com.meteorite.meteosuikit.api.style.UiSurfaceStyle;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.meteosuikit.api.style.UiThemeMetrics;
import com.meteorite.meteosuikit.api.style.UiThemes;
import com.meteorite.meteosuikit.api.style.UiTooltipSkin;

/** 模组暗色页面与书本共用的主题来源，宿主提供语义色与图集控件皮肤。 */
public enum UnsuspiciousUiTheme implements UiStyleSource {
    DARK(UiThemes.builder().colors(UiSurfaceStyle.MINECRAFT.withText(UiTextScheme.DARK)).build()),
    PARCHMENT(buildParchmentTheme());

    private final UiStyleSource delegate;

    UnsuspiciousUiTheme(UiStyleSource delegate) {
        this.delegate = delegate;
    }

    private static UiStyleSource buildParchmentTheme() {
        return UiThemes.builder()
                .colors(UiSurfaceStyle.MINECRAFT.withText(UiTextScheme.PARCHMENT)
                        .withInput(0xFFD8C0A0, 0xFF8B6914, UiTextScheme.PARCHMENT.body()))
                .control(UiControlRole.SECONDARY, UiAtlas.PARCHMENT_BUTTON_SKIN)
                .control(UiControlRole.PRIMARY, UiAtlas.ACCENT_BUTTON_SKIN)
                .control(UiControlRole.DANGER, UiAtlas.PARCHMENT_BUTTON_SKIN)
                .control(UiControlRole.DISABLED, UiAtlas.PARCHMENT_DISABLED_SKIN)
                .control(UiControlRole.LABEL, UiAtlas.READOUT_SKIN)
                .build();
    }

    @Override public UiControlStyle controlStyle(UiControlRole role) { return delegate.controlStyle(role); }
    @Override public UiControlSkin controlSkin(UiControlRole role) { return delegate.controlSkin(role); }
    @Override public UiSliderSkin sliderSkin(UiSliderRole role) { return delegate.sliderSkin(role); }
    @Override public UiSurfaceSkin surfaceSkin(UiSurfaceRole role) { return delegate.surfaceSkin(role); }
    @Override public UiSurfaceStyle surfaces() { return delegate.surfaces(); }
    @Override public UiTooltipSkin tooltipSkin() { return delegate.tooltipSkin(); }
    @Override public UiThemeMetrics metrics() { return delegate.metrics(); }
    @Override public int controlTextColor(UiControlRole role) { return delegate.controlTextColor(role); }
}
