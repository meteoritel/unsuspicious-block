package com.meteorite.unsuspiciousblock.client.ui.theme;

import com.meteorite.meteosuikit.api.control.UiAction;
import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.render.UiIcon;
import com.meteorite.meteosuikit.api.style.UiControlSkin;
import com.meteorite.meteosuikit.api.style.UiControlStyle;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.List;

/** 笔记按钮的图集与皮肤配方；输入、焦点和按压均由返回的 kit 控件持有。 */
public final class JournalControls {
    public enum Icon {
        ARROW_UP(0, 0),
        ARROW_DOWN(1, 0),
        SHOW_LOCKED(2, 0),
        HIDE_LOCKED(3, 0),
        SEARCH(4, 0),
        CLOSE(5, 0),
        PENCIL(7, 0),
        CLOCK(8, 0),
        SORT_DEFAULT(0, 1),
        SORT_NAME(1, 1),
        SORT_ITEM_COUNT(2, 1),
        SORT_FAVORITE(3, 1),
        SORT_UNLOCK(4, 1),
        SORT_UPDATE_TIME(5, 1),
        DISABLED(6, 1),
        ENABLED(7, 1),
        CHECK(7, 1),
        GEAR(0, 2),
        SQUARE(1, 2),
        GROUP(2, 2),
        TRASH_FULL(3, 2),
        TRASH_EMPTY(4, 2),
        BIOME(5, 2),
        DIMENSION(6, 2);

        private final int atlasColumn;
        private final int atlasRow;

        Icon(int atlasColumn, int atlasRow) {
            this.atlasColumn = atlasColumn;
            this.atlasRow = atlasRow;
        }

        public int atlasX() {
            return this.atlasColumn * ICON_ATLAS_CELL_SIZE;
        }

        public int atlasY() {
            return this.atlasRow * ICON_ATLAS_CELL_SIZE;
        }
    }

    private static final int ICON_ATLAS_CELL_SIZE = 9;
    private static final ResourceLocation ICON_ATLAS = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/toolbar_icons.png");
    private JournalControls() { }

    public static UiIcon icon(Icon icon) {
        return new UiIcon.Sprite(ICON_ATLAS, icon.atlasX(), icon.atlasY(), 9, 9, 81, 27);
    }

    public static void configureIcon(UiControl button, Icon icon, List<Component> tooltip, UiAction action) {
        button.configure(Minecraft.getInstance().font, Component.empty(), UiTextPalette.Parchment.BODY,
                icon(icon), tooltip, action);
        button.setAccessibleName(tooltip.isEmpty() ? Component.empty() : tooltip.getFirst());
    }

    public static void configureIcon(UiControl button, Icon icon, Component tooltip, UiAction action) {
        configureIcon(button, icon, List.of(tooltip), action);
    }

    public static UiControl iconButton(int x, int y, int size, Icon icon, List<Component> tooltip, Runnable action) {
        UiControl button = paperButton(new UiRect(x, y, size, size));
        configureIcon(button, icon, tooltip, action::run);
        return button;
    }

    public static UiControl iconButton(int x, int y, int size, Icon icon, Component tooltip, Runnable action) {
        return iconButton(x, y, size, icon, List.of(tooltip), action);
    }

    public static UiControl iconButton(int x, int y, int size, char symbol, Component tooltip, Runnable action) {
        return iconButton(x, y, size, symbol, List.of(tooltip), action);
    }

    public static UiControl iconButton(int x, int y, int size, char symbol, List<Component> tooltip, Runnable action) {
        UiControl button = paperButton(new UiRect(x, y, size, size));
        button.configure(Minecraft.getInstance().font, Component.literal(Character.toString(symbol)),
                UiTextPalette.Parchment.BODY, null, tooltip, action::run);
        button.setAccessibleName(tooltip.isEmpty() ? Component.empty() : tooltip.getFirst());
        return button;
    }

    private static UiControl paperButton(UiRect rect) {
        UiControl button = new UiControl();
        button.setStyle(UiControlStyle.PARCHMENT);
        button.setFocusable(true);
        button.setBounds(rect.x(), rect.y(), rect.width(), rect.height());
        return button;
    }

    public static UiControl sideTab(int x, int y, int width, int height, ResourceLocation texture,
                                    Component tooltip, Runnable action) {
        return sideTab(x, y, width, height, texture, List.of(tooltip), action);
    }

    public static UiControl sideTab(int x, int y, int width, int height, ResourceLocation texture,
                                    List<Component> tooltip, Runnable action) {
        UiControl button = paperButton(new UiRect(x, y, width, height));
        button.configure(Minecraft.getInstance().font, Component.empty(), UiTextPalette.Parchment.BODY,
                null, tooltip, action::run);
        button.setAccessibleName(tooltip.isEmpty() ? Component.empty() : tooltip.getFirst());
        button.setSkin(new UiControlSkin(UiControlStyle.PARCHMENT, (graphics, frame) -> {
            UiRect r = frame.bounds();
            graphics.blit(texture, r.x(), r.y(), 0, 0, r.width(), r.height(), r.width(), r.height());
            if (frame.focused() || frame.state() == UiControlStyle.State.HOVER)
                graphics.fill(r.x() + 2, r.y() + 2, r.right() - 1, r.bottom() - 2, 0x20FFFFFF);
        }, UiTextPalette.Parchment.BODY, UiTextPalette.Parchment.HINT));
        return button;
    }

    public static UiControl bookmark(int x, int y, Component label, Runnable action) {
        UiControl button = paperButton(new UiRect(x, y, 38, 22));
        button.configure(Minecraft.getInstance().font, Component.empty(), UiTextPalette.Parchment.BODY,
                null, List.of(label), action::run);
        button.setAccessibleName(label);
        ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/gui/bookmark_tab.png");
        button.setSkin(new UiControlSkin(UiControlStyle.PARCHMENT, (graphics, frame) -> {
            UiRect r = frame.bounds();
            boolean expanded = button.isSelected() || frame.focused() || frame.state() == UiControlStyle.State.HOVER;
            int width = expanded ? 38 : 14;
            int v = button.isSelected() ? 44 : expanded ? 22 : 0;
            graphics.blit(texture, r.x(), r.y(), 40 - width, v, width, 22, 40, 66);
            if (expanded) graphics.drawString(Minecraft.getInstance().font, label,
                    r.x() + Math.max(3, (width - Minecraft.getInstance().font.width(label)) / 2), r.y() + 7,
                    UiTextPalette.Parchment.BODY, false);
        }, UiTextPalette.Parchment.BODY, UiTextPalette.Parchment.HINT));
        return button;
    }

    // 已由业务卡片绘制正文的操作区，只补充 kit 的键盘焦点轮廓。
    public static void configureHotspot(UiControl control, Component label, Runnable action) {
        configureHotspot(control, label, List.of(), action);
    }

    public static void configureHotspot(UiControl control, Component label, List<Component> tooltip, Runnable action) {
        control.configure(Minecraft.getInstance().font, Component.empty(), UiTextPalette.Parchment.BODY,
                null, tooltip, action::run);
        control.setAccessibleName(label);
        control.setSkin(new UiControlSkin(UiControlStyle.PARCHMENT, (graphics, frame) -> {
            if (frame.focused()) {
                UiRect r = frame.bounds();
                int color = UiTextPalette.Parchment.ACCENT;
                graphics.fill(r.x(), r.y(), r.right(), r.y() + 1, color);
                graphics.fill(r.x(), r.bottom() - 1, r.right(), r.bottom(), color);
                graphics.fill(r.x(), r.y(), r.x() + 1, r.bottom(), color);
                graphics.fill(r.right() - 1, r.y(), r.right(), r.bottom(), color);
            }
        }, UiTextPalette.Parchment.BODY, UiTextPalette.Parchment.HINT));
    }

    public static UiControl page(int x, int y, boolean forward, Runnable action) {
        UiControl button = paperButton(new UiRect(x, y, JournalLayout.PAGE_BUTTON_WIDTH, JournalLayout.PAGE_BUTTON_HEIGHT));
        button.configure(Minecraft.getInstance().font, Component.empty(), UiTextPalette.Parchment.BODY,
                null, List.of(), action::run);
        button.setAccessibleName(Component.translatable(forward ? "screen.unsuspiciousblock.navigation.next_page" : "screen.unsuspiciousblock.navigation.previous_page"));
        button.setSkin(new UiControlSkin(UiControlStyle.PARCHMENT, (graphics, frame) -> {
            UiRect r = frame.bounds();
            boolean highlighted = frame.focused() || frame.state() == UiControlStyle.State.HOVER;
            String name = "widget/page_" + (forward ? "forward" : "backward") + (highlighted ? "_highlighted" : "");
            graphics.blitSprite(ResourceLocation.withDefaultNamespace(name), r.x(), r.y(), r.width(), r.height());
        }, UiTextPalette.Parchment.BODY, UiTextPalette.Parchment.HINT));
        return button;
    }
}
