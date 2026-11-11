package com.meteorite.unsuspiciousblock.client.config;

import com.meteorite.meteosuikit.api.control.*;
import com.meteorite.meteosuikit.api.input.UiChildren;
import com.meteorite.meteosuikit.api.input.UiInputScope;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiThemeContext;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.common.ModConfigSpec;
import java.util.ArrayList;
import java.util.List;

/** 本模组已注册配置文件的 kit 入口，保留 NeoForge 的加载和可写性边界。 */
public final class NeoForgeConfigScreen extends UiThemedScreen {
    private final Screen parent;
    private final UiChildren<UiWidget> content = new UiChildren<>();
    private final UiInputScope scope = new UiInputScope(content);
    private final UiListView<ModConfig> configs;
    private final UiControl done = new UiControl();

    public NeoForgeConfigScreen(ModContainer mod, Screen parent) {
        super(Component.translatable("unsuspiciousblock.config.title"), new UiThemeContext<>(UnsuspiciousUiTheme.DARK));
        this.parent = parent;
        configs = new UiListView<>(theme(), (graphics, font, config, index, row, selected, hovered, focused) -> {
            graphics.drawString(font, config.getFileName(), row.x(), row.y() + 4, UiTextScheme.DARK.body(), false);
            graphics.drawString(font, status(config), row.x(), row.y() + 17, UiTextScheme.DARK.label(), false);
        });
        List<ModConfig> registered = new ArrayList<>();
        for (ModConfig.Type type : ModConfig.Type.values()) {
            for (ModConfig config : ModConfigs.getConfigSet(type)) {
                if (config.getModId().equals(mod.getModId())) registered.add(config);
            }
        }
        configs.setItems(registered).setRowHeight(34);
        configs.setActivateOnSingleClick(true);
        configs.setOnActivate(config -> {
            if (canEdit(config)) Minecraft.getInstance().setScreen(new NeoForgeConfigEditor(this, config));
        });
        content.add(configs);
        content.add(done);
        scope.setEscape(this::onClose);
    }

    static boolean canEdit(ModConfig config) {
        Minecraft client = Minecraft.getInstance();
        if (!((ModConfigSpec) config.getSpec()).isLoaded()) return false;
        if (config.getType() != ModConfig.Type.SERVER) return true;
        if (client.getCurrentServer() != null && !client.isSingleplayer()) return false;
        var server = client.getSingleplayerServer();
        return server == null || !server.isPublished();
    }

    static Component status(ModConfig config) {
        Minecraft client = Minecraft.getInstance();
        if (!((ModConfigSpec) config.getSpec()).isLoaded()) return Component.translatable("unsuspiciousblock.config.not_loaded");
        var server = client.getSingleplayerServer();
        if (config.getType() == ModConfig.Type.SERVER && server != null && server.isPublished()) return Component.translatable("unsuspiciousblock.config.lan_read_only");
        return Component.translatable(canEdit(config) ? "unsuspiciousblock.config.editable" : "unsuspiciousblock.config.server_managed");
    }

    @Override protected void init() {
        done.setTheme(theme(), UiControlRole.SECONDARY);
        done.setFocusable(true);
        done.configure(font, Component.translatable("gui.done"), null, List.of(), this::onClose);
    }
    @Override protected UiInputScope inputScope() { return scope; }
    @Override protected void layoutUi() {
        int listWidth = Math.clamp(width - 32, 0, 420);
        configs.setBounds((width - listWidth) / 2, 40, listWidth, Math.max(0, height - 80));
        done.setBounds(width / 2 - 50, height - 28, 100, 20);
    }
    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
    @Override protected void renderThemedUi(GuiGraphics graphics, int x, int y, float delta) {
        graphics.drawCenteredString(font, title, width / 2, 18, UiTextScheme.DARK.title());
        var pointer = scope.pointer(x, y).resolve(content);
        for (UiWidget widget : content) widget.render(graphics, font, pointer);
    }
}
