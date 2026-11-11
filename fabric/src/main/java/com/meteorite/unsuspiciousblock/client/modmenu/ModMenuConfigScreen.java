package com.meteorite.unsuspiciousblock.client.modmenu;

import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.control.UiControlGroup;
import com.meteorite.meteosuikit.api.control.UiThemedScreen;
import com.meteorite.meteosuikit.api.input.UiInputScope;
import com.meteorite.meteosuikit.api.style.UiThemeContext;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;

/** ModMenu 配置首页，按全局与当前世界配置归属提供入口。 */
public final class ModMenuConfigScreen extends UiThemedScreen {
    private final Screen parent;
    private final UiControlGroup controls = new UiControlGroup();
    private final UiInputScope scope = new UiInputScope(controls);

    public ModMenuConfigScreen(Screen parent) {
        super(Component.translatable("unsuspiciousblock.config.title"), new UiThemeContext<>(UnsuspiciousUiTheme.DARK));
        this.parent = parent;
        scope.setEscape(this::onClose);
    }

    @Override protected void init() {
        controls.setTheme(theme());
        controls.beginUpdate();
        controls.obtain("common").configure(font, Component.translatable("unsuspiciousblock.config.common.open"),
                null, List.of(), () -> Minecraft.getInstance().setScreen(new SpiritCatConfigScreen(this)));
        controls.obtain("server").configure(font, Component.translatable("unsuspiciousblock.config.server.open"),
                null, List.of(), () -> Minecraft.getInstance().setScreen(new ServerLootConfigScreen(this)));
        controls.obtain("done").configure(font, Component.translatable("gui.done"), null, List.of(), this::onClose);
        controls.endUpdate();
    }
    @Override protected UiInputScope inputScope() { return scope; }
    @Override protected void layoutUi() {
        int buttonWidth = Math.clamp(width - 40, 0, 280);
        int y = Math.max(42, height / 2 - 38);
        for (UiControl control : controls.controls()) {
            control.setBounds((width - buttonWidth) / 2, y, buttonWidth, 20);
            y += 40;
        }
    }
    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
    @Override protected void renderThemedUi(GuiGraphics graphics, int x, int y, float delta) {
        graphics.drawCenteredString(font, title, width / 2, 18, UiTextScheme.DARK.title());
        var buttons = controls.controls();
        graphics.drawCenteredString(font, Component.translatable("unsuspiciousblock.config.common.scope"),
                width / 2, buttons.get(0).bounds().bottom() + 4, UiTextScheme.DARK.label());
        graphics.drawCenteredString(font, Component.translatable("unsuspiciousblock.config.server.scope"),
                width / 2, buttons.get(1).bounds().bottom() + 4, UiTextScheme.DARK.label());
        controls.render(graphics, font, scope.pointer(x, y).resolve(controls));
    }
}
