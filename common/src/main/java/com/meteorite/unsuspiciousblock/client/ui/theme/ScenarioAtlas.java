package com.meteorite.unsuspiciousblock.client.ui.theme;

import com.meteorite.meteosuikit.api.render.UiNineSlice;
import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.resources.ResourceLocation;

/**
 * 场景页与相关浮层专用的底板图集。
 */
public final class ScenarioAtlas {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/scenario_panels.png");
    private static final int WIDTH = 80;
    private static final int HEIGHT = 16;

    /**
     * 场景页和浮层外壳底板。
     */
    public static final UiNineSlice PANEL = slice(0);
    /**
     * 条件树小节标题底板。
     */
    public static final UiNineSlice SECTION = slice(16);
    /**
     * 子表/标签合集卡片常态底板。
     */
    public static final UiNineSlice CARD = slice(32);
    /**
     * 子表/标签合集卡片悬停底板。
     */
    public static final UiNineSlice CARD_HOVER = slice(48);
    /**
     * 条目定位和焦点高亮底板。
     */
    public static final UiNineSlice SELECTED = slice(64);

    private ScenarioAtlas() {
    }

    private static UiNineSlice slice(int originU) {
        return new UiNineSlice(TEXTURE, originU, 0, 16, 3, WIDTH, HEIGHT);
    }
}
