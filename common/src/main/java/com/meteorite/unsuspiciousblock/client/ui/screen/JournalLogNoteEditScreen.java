package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.meteosuikit.api.style.UiTextScheme;

import com.meteorite.meteosuikit.api.control.*;
import com.meteorite.meteosuikit.api.input.UiChildren;
import com.meteorite.meteosuikit.api.input.UiInputScope;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiThemeContext;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateJournalLogNotePayload;
import com.meteorite.unsuspiciousblock.client.network.ClientNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** 日志备注草稿由稳定的 kit 编辑器持有，明确保存后才发送更新请求。 */
public final class JournalLogNoteEditScreen extends UiThemedScreen {
    private static final int NOTE_MAX_LENGTH = 500;
    private final Screen parent;
    private final ResourceLocation tableId;
    private final UUID entryId;
    private final UiChildren<UiWidget> content = new UiChildren<>();
    private final UiInputScope scope = new UiInputScope(content);
    private final UiTextArea editor;
    private final UiControl save = new UiControl();
    private final UiControl cancel = new UiControl();
    private boolean initialized;
    private @Nullable Component validationMessage;

    public JournalLogNoteEditScreen(@Nullable Screen parent, ResourceLocation tableId, UUID entryId, String initialNote) {
        super(Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_note_editor_title"),
                new UiThemeContext<>(UnsuspiciousUiTheme.DARK));
        this.parent = parent;
        this.tableId = tableId;
        this.entryId = entryId;
        editor = new UiTextArea(theme(), Minecraft.getInstance().font,
                Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_note_placeholder"));
        editor.setMaxLength(NOTE_MAX_LENGTH).setValue(initialNote == null ? "" : initialNote)
                .setOnOverflow(this::showLengthError);
        editor.setOnCancel(this::onClose);
        content.addAll(List.of(editor, save, cancel));
        scope.setEscape(this::onClose);
    }

    @Override protected void init() {
        save.setTheme(theme(), UiControlRole.PRIMARY);
        save.setFocusable(true);
        save.configure(font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_note_save"),
                null, List.of(), this::saveNote);
        cancel.setTheme(theme(), UiControlRole.SECONDARY);
        cancel.setFocusable(true);
        cancel.configure(font, Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_note_cancel"),
                null, List.of(), this::onClose);
        if (!initialized) {
            initialized = true;
            scope.refreshFocus();
            scope.focus().focusOn(editor);
        }
    }

    @Override protected UiInputScope inputScope() { return scope; }

    @Override protected void layoutUi() {
        int editorWidth = Math.clamp(width - 24, 0, 300);
        int buttonsY = Math.max(48, height - 36);
        editor.setBounds((width - editorWidth) / 2, 44, editorWidth, Math.max(0, buttonsY - 52));
        int buttonWidth = Math.clamp((editorWidth - 8) / 2, 0, 100);
        save.setBounds(width / 2 - buttonWidth - 4, buttonsY, buttonWidth, 20);
        cancel.setBounds(width / 2 + 4, buttonsY, buttonWidth, 20);
    }

    private void showLengthError() {
        validationMessage = Component.translatable("screen.unsuspiciousblock.archaeology_journal.log_note_too_long", NOTE_MAX_LENGTH);
    }

    private void saveNote() {
        // 协议与服务端按 UTF-16 单元限长，kit 编辑器按 Unicode 码点限长。
        if (editor.value().length() > NOTE_MAX_LENGTH) {
            showLengthError();
            return;
        }
        ClientNetwork.sendToServer(new UpdateJournalLogNotePayload(tableId, entryId, editor.value()));
        onClose();
    }

    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }

    @Override protected void renderThemedUi(GuiGraphics graphics, int x, int y, float delta) {
        graphics.drawCenteredString(font, title, width / 2, 20, UiTextScheme.DARK.title());
        var pointer = scope.pointer(x, y).resolve(content);
        for (UiWidget widget : content) widget.render(graphics, font, pointer);
        if (validationMessage != null) graphics.drawCenteredString(font, validationMessage,
                width / 2, height - 13, UiTextScheme.DARK.negative());
    }

    @Override public boolean isPauseScreen() { return false; }
}
