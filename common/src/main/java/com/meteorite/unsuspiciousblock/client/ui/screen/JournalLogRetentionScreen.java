package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateJournalLogRetentionPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** 当前战利品表的日志保留与一次性时间清理配置界面。 */
public final class JournalLogRetentionScreen extends Screen {
    private static final int FIELD_WIDTH = 96;
    private static final int BUTTON_WIDTH = 112;
    private static final int BUTTON_HEIGHT = 20;

    private final Screen parent;
    private final ResourceLocation tableId;
    private final int initialRetentionLimit;
    private final int currentEntryCount;

    private EditBox retentionLimitBox;
    private EditBox keepRecentBox;
    @Nullable
    private Component validationMessage;

    public JournalLogRetentionScreen(Screen parent, ResourceLocation tableId,
                                     int initialRetentionLimit, int currentEntryCount) {
        super(Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.log_retention.title"));
        this.parent = parent;
        this.tableId = tableId;
        this.initialRetentionLimit = Math.max(1, initialRetentionLimit);
        this.currentEntryCount = Math.max(0, currentEntryCount);
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int fieldX = centerX - FIELD_WIDTH / 2;

        this.retentionLimitBox = numericField(fieldX, 72, this.initialRetentionLimit);
        this.keepRecentBox = numericField(fieldX, 142, Math.min(this.currentEntryCount, this.initialRetentionLimit));

        addRenderableWidget(Button.builder(
                        Component.translatable(
                                "screen.unsuspiciousblock.archaeology_journal.log_retention.apply_limit"),
                        button -> submit(UpdateJournalLogRetentionPayload.Action.SET_LIMIT,
                                this.retentionLimitBox, false))
                .bounds(centerX - BUTTON_WIDTH / 2, 96, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable(
                                "screen.unsuspiciousblock.archaeology_journal.log_retention.prune_now"),
                        button -> submit(UpdateJournalLogRetentionPayload.Action.PRUNE_KEEP_RECENT,
                                this.keepRecentBox, true))
                .bounds(centerX - BUTTON_WIDTH / 2, 166, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.done"),
                        button -> closeToParent())
                .bounds(centerX - 50, 208, 100, BUTTON_HEIGHT)
                .build());
    }

    private EditBox numericField(int x, int y, int value) {
        EditBox field = new EditBox(this.font, x, y, FIELD_WIDTH, 20, Component.empty());
        field.setMaxLength(10);
        field.setFilter(text -> text.chars().allMatch(Character::isDigit));
        field.setValue(String.valueOf(value));
        addRenderableWidget(field);
        return field;
    }

    private void submit(UpdateJournalLogRetentionPayload.Action action, EditBox field, boolean allowZero) {
        int value;
        try {
            value = Integer.parseInt(field.getValue());
        } catch (NumberFormatException exception) {
            this.validationMessage = Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.log_retention.invalid_number");
            return;
        }
        if (value < (allowZero ? 0 : 1)) {
            this.validationMessage = Component.translatable(
                    "screen.unsuspiciousblock.archaeology_journal.log_retention.invalid_number");
            return;
        }
        Services.NETWORK.sendToServer(new UpdateJournalLogRetentionPayload(this.tableId, action, value));
        closeToParent();
    }

    private void closeToParent() {
        Objects.requireNonNull(this.minecraft, "minecraft must not be null while screen is active")
                .setScreen(this.parent);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        guiGraphics.drawCenteredString(this.font, this.title, centerX, 28, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_retention.table",
                        this.tableId.toString()), centerX, 44, 0xB8B8B8);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_retention.limit_label"),
                centerX, 59, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_retention.keep_recent_label"),
                centerX, 129, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_retention.note_protection"),
                centerX, 190, 0xE0C070);
        if (this.validationMessage != null) {
            guiGraphics.drawCenteredString(this.font, this.validationMessage, centerX, 232, 0xFF6060);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            closeToParent();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
