package com.meteorite.unsuspiciousblock.client.ui.theme;

import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.UiAction;
import com.meteorite.meteosuikit.api.render.UiIcon;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiControlSkin;
import com.meteorite.meteosuikit.api.style.UiControlStyle;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.client.ui.layout.JournalLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/** 笔记按钮的图集与皮肤配方；输入、焦点和按压均由返回的 kit 控件持有。 */
public final class JournalControls {
    private JournalControls() {
    }

    /** 使用统一图集图标配置已有控件。 */
    public static void configureIcon(UiControl button, UiAtlas.Icon icon, List<Component> tooltip, UiAction action) {
        configureIcon(button, UiAtlas.icon(icon), tooltip, action);
    }

    /** 使用统一图集图标配置已有控件（单行提示）。 */
    public static void configureIcon(UiControl button, UiAtlas.Icon icon, Component tooltip, UiAction action) {
        configureIcon(button, icon, List.of(tooltip), action);
    }

    /** 使用显式 {@link UiIcon} 配置已有控件，沿用羊皮纸主题文字色与状态皮肤。 */
    public static void configureIcon(UiControl button, UiIcon icon, List<Component> tooltip, UiAction action) {
        button.configure(Minecraft.getInstance().font, Component.empty(), icon, tooltip, action);
        button.setAccessibleName(tooltip.isEmpty() ? Component.empty() : tooltip.getFirst());
    }

    /** 构造正方形图集图标按钮（带多行提示）。 */
    public static UiControl iconButton(int x, int y, int size, UiAtlas.Icon icon, List<Component> tooltip, Runnable action) {
        return iconButton(x, y, size, UiAtlas.icon(icon), tooltip, action);
    }

    /** 构造正方形图集图标按钮（单行提示）。 */
    public static UiControl iconButton(int x, int y, int size, UiAtlas.Icon icon, Component tooltip, Runnable action) {
        return iconButton(x, y, size, icon, List.of(tooltip), action);
    }

    /** 构造自定义 {@link UiIcon} 按钮（例如 16×16 首页外链像素图标）。 */
    public static UiControl iconButton(int x, int y, int size, UiIcon icon, Component tooltip, Runnable action) {
        return iconButton(x, y, size, icon, List.of(tooltip), action);
    }

    /** 构造自定义 {@link UiIcon} 按钮（带多行提示）。 */
    public static UiControl iconButton(int x, int y, int size, UiIcon icon, List<Component> tooltip, Runnable action) {
        UiControl button = paperButton(new UiRect(x, y, size, size));
        configureIcon(button, icon, tooltip, action::run);
        return button;
    }

    private static UiControl paperButton(UiRect rect) {
        UiControl button = new UiControl();
        button.setTheme(() -> UnsuspiciousUiTheme.PARCHMENT, UiControlRole.SECONDARY);
        button.setFocusable(true);
        button.setBounds(rect.x(), rect.y(), rect.width(), rect.height());
        return button;
    }

    /** 构造书本左侧阶梯标签按钮（单行提示），底板与图标均取自统一图集。 */
    public static UiControl sideTab(int x, int y, int width, int height, UiAtlas.SideTabKind kind,
                                    UiAtlas.Icon icon, Component tooltip, Runnable action) {
        return sideTab(x, y, width, height, kind, icon, List.of(tooltip), action);
    }

    /** 构造书本左侧阶梯标签按钮，悬停/焦点使用贴合剪影的亮态底板。 */
    public static UiControl sideTab(int x, int y, int width, int height, UiAtlas.SideTabKind kind,
                                    UiAtlas.Icon icon, List<Component> tooltip, Runnable action) {
        UiControl button = paperButton(new UiRect(x, y, width, height));
        button.configure(Minecraft.getInstance().font, Component.empty(), null, tooltip, action::run);
        button.setAccessibleName(tooltip.isEmpty() ? Component.empty() : tooltip.getFirst());
        UiIcon glyph = UiAtlas.icon(icon);
        button.setSkin(new UiControlSkin(UiControlStyle.PARCHMENT, (graphics, frame) -> {
            UiRect r = frame.bounds();
            boolean highlighted = frame.focused()
                    || frame.state() == UiControlStyle.State.HOVER
                    || frame.state() == UiControlStyle.State.PRESSED;
            UiAtlas.sideTabPlate(kind, highlighted).render(graphics, r.x(), r.y());
            glyph.render(graphics, r.x() + 7, r.y() + (r.height() - glyph.height()) / 2);
        }, UiTextScheme.PARCHMENT.body(), UiTextScheme.PARCHMENT.hint()));
        return button;
    }

    /** 构造右页书签 Tab 控件。 */
    public static UiControl bookmark(int x, int y, Component label, Runnable action) {
        UiControl button = paperButton(new UiRect(x, y, 38, 22));
        button.configure(Minecraft.getInstance().font, Component.empty(), null, List.of(label), action::run);
        button.setAccessibleName(label);
        button.setSkin(new UiControlSkin(UiControlStyle.PARCHMENT, (graphics, frame) -> {
            UiRect r = frame.bounds();
            boolean expanded = button.isSelected() || frame.focused() || frame.state() == UiControlStyle.State.HOVER;
            int width = expanded ? 38 : 14;
            int v = button.isSelected() ? 44 : expanded ? 22 : 0;
            graphics.blit(JournalLayout.BOOKMARK_TEXTURE, r.x(), r.y(), 40 - width, v, width, 22, 40, 66);
            if (expanded) {
                graphics.drawString(Minecraft.getInstance().font, label,
                        r.x() + Math.max(3, (width - Minecraft.getInstance().font.width(label)) / 2), r.y() + 7,
                        UiTextScheme.PARCHMENT.body(), false);
            }
        }, UiTextScheme.PARCHMENT.body(), UiTextScheme.PARCHMENT.hint()));
        return button;
    }

    /** 为业务区域补充键盘焦点框，不改动内容本体。 */
    public static void configureHotspot(UiControl control, Component label, Runnable action) {
        configureHotspot(control, label, List.of(), action);
    }

    /** 为业务区域补充键盘焦点框与 Tooltip，不改动内容本体。 */
    public static void configureHotspot(UiControl control, Component label, List<Component> tooltip, Runnable action) {
        control.configure(Minecraft.getInstance().font, Component.empty(), null, tooltip, action::run);
        control.setAccessibleName(label);
        control.setSkin(new UiControlSkin(UiControlStyle.PARCHMENT, (graphics, frame) -> {
            if (frame.focused()) {
                UiRect r = frame.bounds();
                int color = UiTextScheme.PARCHMENT.accent();
                graphics.fill(r.x(), r.y(), r.right(), r.y() + 1, color);
                graphics.fill(r.x(), r.bottom() - 1, r.right(), r.bottom(), color);
                graphics.fill(r.x(), r.y(), r.x() + 1, r.bottom(), color);
                graphics.fill(r.right() - 1, r.y(), r.right(), r.bottom(), color);
            }
        }, UiTextScheme.PARCHMENT.body(), UiTextScheme.PARCHMENT.hint()));
    }

    /** 构造右页底部 23×13 羊皮纸翻页箭头按钮。 */
    public static UiControl page(int x, int y, boolean forward, Runnable action) {
        UiControl button = paperButton(new UiRect(x, y, JournalLayout.PAGE_BUTTON_WIDTH, JournalLayout.PAGE_BUTTON_HEIGHT));
        button.configure(Minecraft.getInstance().font, Component.empty(), null, List.of(), action::run);
        button.setAccessibleName(Component.translatable(forward
                ? "screen.unsuspiciousblock.navigation.next_page"
                : "screen.unsuspiciousblock.navigation.previous_page"));
        button.setSkin(new UiControlSkin(UiControlStyle.PARCHMENT, (graphics, frame) -> {
            UiRect r = frame.bounds();
            boolean disabled = frame.state() == UiControlStyle.State.DISABLED;
            boolean highlighted = !disabled && (frame.focused()
                    || frame.state() == UiControlStyle.State.HOVER
                    || frame.state() == UiControlStyle.State.PRESSED);
            UiAtlas.pageArrow(forward, disabled, highlighted).render(graphics, r.x(), r.y());
        }, UiTextScheme.PARCHMENT.body(), UiTextScheme.PARCHMENT.hint()));
        return button;
    }
}
