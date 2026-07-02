package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.unsuspiciousblock.network.payload.c2s.UpdateJournalLogNotePayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 日志备注编辑界面——多行文本输入，保存时发送 C2S 包更新对应日志条目的备注内容。
 * 取消或保存后返回父 Screen。
 */
public class JournalLogNoteEditScreen extends Screen {

    private static final int NOTE_MAX_LENGTH = 500;
    private static final int EDIT_BOX_WIDTH = 240;
    private static final int EDIT_BOX_HEIGHT = 120;
    private static final int BUTTON_WIDTH = 80;
    private static final int BUTTON_HEIGHT = 20;
    private static final int VERTICAL_GAP = 8;

    private final Screen parent;
    private final ResourceLocation tableId;
    private final UUID entryId;
    private final String initialNote;

    private MultiLineEditBox editBox;

    public JournalLogNoteEditScreen(@Nullable Screen parent, ResourceLocation tableId, UUID entryId, String initialNote) {
        super(Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_note_editor_title"));
        this.parent = parent;
        this.tableId = tableId;
        this.entryId = entryId;
        this.initialNote = initialNote != null ? initialNote : "";
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int titleY = 24;
        int editY = titleY + VERTICAL_GAP + 12;
        int editX = centerX - EDIT_BOX_WIDTH / 2;

        // 多行输入框
        this.editBox = new MultiLineEditBox(
                this.font,
                editX, editY,
                EDIT_BOX_WIDTH, EDIT_BOX_HEIGHT,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_note_placeholder"),
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_note_editor_title"));
        this.editBox.setCharacterLimit(NOTE_MAX_LENGTH);
        this.editBox.setValue(this.initialNote);
        this.editBox.setFocused(true);
        addWidget(this.editBox);

        // 按钮 Y 位置
        int btnY = editY + EDIT_BOX_HEIGHT + VERTICAL_GAP;
        int saveX = centerX - BUTTON_WIDTH - 4;
        int cancelX = centerX + 4;

        // 保存按钮
        addRenderableWidget(Button.builder(
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_note_save"),
                b -> onSave())
                .bounds(saveX, btnY, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
        // 取消按钮
        addRenderableWidget(Button.builder(
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_note_cancel"),
                b -> onCancel())
                .bounds(cancelX, btnY, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    // 保存：发送 C2S 更新包并返回父界面
    private void onSave() {
        String value = this.editBox.getValue();
        Services.NETWORK.sendToServer(new UpdateJournalLogNotePayload(this.tableId, this.entryId, value));
        this.minecraft.setScreen(this.parent);
    }

    // 取消：直接返回父界面
    private void onCancel() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // 标题
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 24, 0xFFFFFF);
        // 编辑框
        this.editBox.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC 返回父界面（与原版 Screen 行为一致）
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            this.minecraft.setScreen(this.parent);
            return true;
        }
        if (this.editBox.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.editBox.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.editBox.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.editBox.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.editBox.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
