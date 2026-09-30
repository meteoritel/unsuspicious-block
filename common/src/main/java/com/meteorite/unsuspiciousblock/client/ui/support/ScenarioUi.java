package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiControlStyle;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiIcon;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiLightbox;
import com.meteorite.unsuspiciousblock.client.ui.kit.UiNineSlice;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/** 场景界面的轻量纸面、控件样式与独立图标槽位，不改变共享工具栏图集。 */
public final class ScenarioUi {
    private static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/scenario_controls.png");
    public static final UiNineSlice PANEL = new UiNineSlice(ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/scenario_panel.png"), 12, 3);
    public static final int BRANCH = 0xFFA99370;
    public static final UiControlStyle QUIET = new UiControlStyle(
            0x00000000, 0x35A3875B, 0x55A3875B, 0x50B29150, 0x00000000,
            0xFF3A6EA5, 0xFFDCD0B4, 0xFF8A7350);
    public static final UiControlStyle ACTION = new UiControlStyle(
            0xFFDCC493, 0xFFE8CF96, 0xFFCCB17E, 0xFFDCC493, 0xFFE8DFC9,
            0xFF3A6EA5, 0xFFDCD0B4, 0xFF8A7350);

    /** 图标顺序与生成脚本中的九个槽位一致。 */
    public enum Icon { CLOSE, EXPAND, MINUS, PLUS, FIT, DOWN, PARAMS, CALCULATE, RECOMMEND }
    private static final UiIcon[] ICONS = java.util.Arrays.stream(Icon.values())
            .map(icon -> new UiIcon.Sprite(ATLAS, icon.ordinal() * 9, 0, 9, 9, 81, 9)).toArray(UiIcon[]::new);

    private ScenarioUi() {}

    public static UiIcon icon(Icon icon) {
        return ICONS[icon.ordinal()];
    }

    public static void styleLightbox(UiLightbox lightbox) {
        lightbox.setStyle(QUIET);
        lightbox.setTextColor(UiTextPalette.Parchment.BODY);
        lightbox.setSurface(PANEL);
        lightbox.setIcons(Map.of("close", icon(Icon.CLOSE), "fit", icon(Icon.FIT),
                "zoom_in", icon(Icon.PLUS), "zoom_out", icon(Icon.MINUS)));
        lightbox.setBarHint(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.simulation.frame.pan_hint"));
    }
}
