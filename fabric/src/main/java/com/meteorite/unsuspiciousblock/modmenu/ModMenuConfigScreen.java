package com.meteorite.unsuspiciousblock.modmenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Objects;

/**
 * Mod Menu 配置首页，明确区分全局通用配置与当前世界的服务端配置。
 */
public class ModMenuConfigScreen extends Screen {

    private static final int CONTENT_WIDTH = 280;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 34;

    private final Screen parent;

    public ModMenuConfigScreen(Screen parent) {
        super(Component.translatable("unsuspiciousblock.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int buttonWidth = Math.min(CONTENT_WIDTH, this.width - 40);
        int leftX = (this.width - buttonWidth) / 2;
        int firstButtonY = Math.max(54, this.height / 2 - 38);

        this.addRenderableWidget(Button.builder(
                Component.translatable("unsuspiciousblock.config.common.open"),
                button -> Objects.requireNonNull(this.minecraft).setScreen(new SpiritCatConfigScreen(this)))
                .bounds(leftX, firstButtonY, buttonWidth, BUTTON_HEIGHT)
                .build());
        this.addRenderableWidget(Button.builder(
                Component.translatable("unsuspiciousblock.config.server.open"),
                button -> Objects.requireNonNull(this.minecraft).setScreen(new ServerLootConfigScreen(this)))
                .bounds(leftX, firstButtonY + BUTTON_GAP, buttonWidth, BUTTON_HEIGHT)
                .build());
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.done"),
                button -> this.onClose())
                .bounds((this.width - 100) / 2, this.height - 28, 100, BUTTON_HEIGHT)
                .build());
    }

    @Override
    public void onClose() {
        Objects.requireNonNull(this.minecraft, "minecraft must not be null while screen is active").setScreen(parent);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int firstButtonY = Math.max(54, this.height / 2 - 38);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("unsuspiciousblock.config.common.scope"),
                this.width / 2, firstButtonY + BUTTON_HEIGHT + 4, 0xA0A0A0);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("unsuspiciousblock.config.server.scope"),
                this.width / 2, firstButtonY + BUTTON_GAP + BUTTON_HEIGHT + 4, 0xA0A0A0);
    }
}
