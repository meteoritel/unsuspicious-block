package com.meteorite.unsuspiciousblock.client.ui.theme;

import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiControlSkin;
import com.meteorite.meteosuikit.api.style.UiControlStyle;
import com.meteorite.meteosuikit.api.style.UiSliderPainter;
import com.meteorite.meteosuikit.api.style.UiSliderRole;
import com.meteorite.meteosuikit.api.style.UiSliderSkin;
import com.meteorite.meteosuikit.api.style.UiSliderStyle;
import com.meteorite.meteosuikit.api.style.UiStyleSource;
import com.meteorite.meteosuikit.api.style.UiSurfaceRole;
import com.meteorite.meteosuikit.api.style.UiSurfaceSkin;
import com.meteorite.meteosuikit.api.style.UiSurfaceStyle;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.meteosuikit.api.style.UiThemeMetrics;
import com.meteorite.meteosuikit.api.style.UiThemes;
import com.meteorite.meteosuikit.api.style.UiTooltipSkin;
import net.minecraft.client.gui.GuiGraphics;

/** 模组暗色页面与书本共用的主题来源，宿主提供语义色与图集控件皮肤。 */
public enum UnsuspiciousUiTheme implements UiStyleSource {
    DARK(buildDarkTheme()),
    PARCHMENT(buildParchmentTheme());

    private final UiStyleSource delegate;

    UnsuspiciousUiTheme(UiStyleSource delegate) {
        this.delegate = delegate;
    }

    private static UiStyleSource buildDarkTheme() {
        return UiThemes.builder()
                .colors(UiSurfaceStyle.MINECRAFT.withText(UiTextScheme.DARK))
                .build();
    }

    private static UiStyleSource buildParchmentTheme() {
        UiSliderSkin sliderSkin = buildParchmentSliderSkin();
        UiSurfaceSkin windowSkin = UiSurfaceSkin.panel(0xFFF2E6CE, 0xFF8B6914, 0x50FFFFFF);
        UiSurfaceSkin panelSkin = UiSurfaceSkin.panel(0xFFF0E3C4, 0xFF8B6914, 0x45FFFFFF);
        UiSurfaceSkin headerSkin = UiSurfaceSkin.header(0xFFE5D5B2, 0xFFD9C59C, 0x808B6914);
        UiSurfaceSkin insetSkin = UiSurfaceSkin.inset(0xFFF5EAD0, 0xFF8B6914, 0x25000000, 0x35FFFFFF);

        return UiThemes.builder()
                .colors(UiSurfaceStyle.MINECRAFT.withText(UiTextScheme.PARCHMENT)
                        .withInput(0xFFF5EAD0, 0xFF8B6914, UiTextScheme.PARCHMENT.body())
                        .withPanel(0xFFF0E3C4, 0xFF8B6914)
                        .withHeader(0xFFE0CEAA, UiTextScheme.PARCHMENT.title())
                        .withSelection(0x38C89B48, 0x70000000))
                .control(UiControlRole.SECONDARY, UiAtlas.PARCHMENT_BUTTON_SKIN)
                .control(UiControlRole.PRIMARY, UiAtlas.ACCENT_BUTTON_SKIN)
                .control(UiControlRole.DANGER, UiAtlas.PARCHMENT_BUTTON_SKIN)
                .control(UiControlRole.DISABLED, UiAtlas.PARCHMENT_DISABLED_SKIN)
                .control(UiControlRole.LABEL, UiAtlas.READOUT_SKIN)
                .surface(UiSurfaceRole.WINDOW, windowSkin)
                .surface(UiSurfaceRole.PANEL, panelSkin)
                .surface(UiSurfaceRole.HEADER, headerSkin)
                .surface(UiSurfaceRole.INSET, insetSkin)
                .surface(UiSurfaceRole.DIVIDER, UiSurfaceSkin.fill(0x508B6914))
                .surface(UiSurfaceRole.SCROLL_TRACK, UiSurfaceSkin.fill(0x288B6914))
                .surface(UiSurfaceRole.SCROLL_THUMB, UiSurfaceSkin.fill(0x908B6914))
                .surface(UiSurfaceRole.SELECT_MARKER, UiSurfaceSkin.fill(0xFF8B6914).withPainter((g, r, state, skin) -> {
                    if (r.width() >= 3 && r.height() > 4) {
                        g.fill(r.x() + 1, r.y() + 2, r.x() + 3, r.bottom() - 2, skin.style().background(state));
                    }
                }))
                .surface(UiSurfaceRole.FOCUS, UiSurfaceSkin.fill(0xFFC89B48).withPainter((g, r, state, skin) ->
                        g.renderOutline(r.x(), r.y(), r.width(), r.height(), skin.style().background(state))))
                .slider(UiSliderRole.COMPACT, sliderSkin)
                .slider(UiSliderRole.ROOMY, sliderSkin)
                .build();
    }

    private static UiSliderSkin buildParchmentSliderSkin() {
        UiSliderStyle style = new UiSliderStyle(
                0x608B6914, // trackColor 柔和古铜底轨
                0xFFA67C42, // fillColor 进度填充暖棕
                0xFF8B6914, // thumbColor 手柄金铜
                0xFFC89B48, // thumbHoverColor 手柄悬停金光
                0x408B6914, // disabledColor
                0xFFC89B48, // focusColor
                0xFF9A3520, // errorColor
                0xFFFFAA00, // overflowColor
                0x00000000, // textSlotColor
                UiTextScheme.PARCHMENT.label(),
                UiTextScheme.PARCHMENT.body(),
                UiTextScheme.PARCHMENT.hint(),
                UiTextScheme.PARCHMENT.negative(),
                4, 6, 3
        );
        return new UiSliderSkin(style, new UiSliderPainter() {
            @Override
            public void paint(GuiGraphics graphics, Frame frame) {
                UiSliderStyle s = frame.style();
                UiRect track = frame.track();
                // 绘制内凹轨道
                graphics.fill(track.x(), track.y(), track.right(), track.bottom(), s.resolveTrack(frame.enabled()));
                graphics.fill(track.x(), track.y(), track.right(), track.y() + 1, 0x40000000);
                graphics.fill(track.x(), track.bottom() - 1, track.right(), track.bottom(), 0x20FFFFFF);

                UiRect filled = frame.filled();
                if (filled.width() > 0 && filled.height() > 0) {
                    graphics.fill(filled.x(), filled.y(), filled.right(), filled.bottom(), s.resolveFill(frame.enabled()));
                    graphics.fill(filled.x(), filled.y(), filled.right(), filled.y() + 1, 0x40FFFFFF);
                }

                paintThumb(graphics, frame);
            }

            @Override
            public void paintThumb(GuiGraphics graphics, Frame frame) {
                UiRect thumb = frame.thumb();
                if (thumb.width() <= 0 || thumb.height() <= 0) {
                    return;
                }
                boolean highlight = frame.hovered() || frame.pressed() || frame.dragging();
                int base = frame.style().resolveThumb(frame.enabled(), highlight);

                // 绘制手柄本体及立体高光边
                graphics.fill(thumb.x(), thumb.y(), thumb.right(), thumb.bottom(), base);
                graphics.fill(thumb.x(), thumb.y(), thumb.right(), thumb.y() + 1, highlight ? 0x90FFFFFF : 0x50FFFFFF);
                graphics.fill(thumb.x(), thumb.y(), thumb.x() + 1, thumb.bottom(), highlight ? 0x70FFFFFF : 0x40FFFFFF);
                graphics.fill(thumb.x(), thumb.bottom() - 1, thumb.right(), thumb.bottom(), 0x60000000);
                graphics.fill(thumb.right() - 1, thumb.y(), thumb.right(), thumb.bottom(), 0x60000000);
            }
        });
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
