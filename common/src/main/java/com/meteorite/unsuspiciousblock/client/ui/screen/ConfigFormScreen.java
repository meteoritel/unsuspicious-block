package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.meteosuikit.api.style.UiTextScheme;


import com.meteorite.meteosuikit.api.control.*;
import com.meteorite.meteosuikit.api.text.TextMeasurer;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.meteosuikit.api.style.UiThemeContext;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置页共享的滚动排版与固定操作区；配置类型、校验和保存由平台页面提供。
 */
public abstract class ConfigFormScreen extends UiThemedScreen {
    private final Screen parent;
    private final List<UiStatefulWidget> editors = new ArrayList<>();
    private final UiFormBody body;
    private final UiControlGroup actions = new UiControlGroup();
    private final UiChildren<UiInputTarget> content = new UiChildren<>();
    private final UiInputScope scope = new UiInputScope(content);
    private @Nullable Component validationMessage;

    protected ConfigFormScreen(Screen parent, Component title) {
        super(title, new UiThemeContext<>(UnsuspiciousUiTheme.DARK));
        this.parent = parent;
        body = new UiFormBody(theme(), TextMeasurer.of(Minecraft.getInstance().font));
        content.add(body);
        content.add(actions);
        scope.setEscape(this::onClose);
        scope.focus().setListener((previous, current) -> body.reveal(current));
    }

    protected final UiTextInput addTextField(Component label, String value) {
        UiTextInput editor = new UiTextInput(theme(), Minecraft.getInstance().font, label).setValue(value);
        editor.setOnCancel(this::onClose);
        addField(label, editor, 20);
        return editor;
    }

    protected final UiTextArea addMultilineField(Component label, String value) {
        UiTextArea editor = new UiTextArea(theme(), Minecraft.getInstance().font, label).setValue(value);
        editor.setAccessibleName(label);
        editor.setOnCancel(this::onClose);
        addField(label, editor, 90);
        return editor;
    }

    protected final void addField(Component label, UiStatefulWidget editor, int height) {
        editors.add(editor);
        body.add(label, editor, height);
    }

    protected abstract void resetDraft();

    protected abstract boolean saveDraft();

    protected boolean canEdit() {
        return true;
    }

    protected Component notice() {
        return Component.empty();
    }

    protected final void showValidation(Component message) {
        validationMessage = message;
    }

    @Override
    protected void init() {
        actions.setTheme(theme());
        actions.beginUpdate();
        actions.obtain("reset").configure(font, Component.translatable("unsuspiciousblock.config.reset"),
                null, List.of(), () -> {
                    resetDraft();
                    validationMessage = null;
                });
        actions.obtain("cancel").configure(font, Component.translatable("gui.cancel"), null, List.of(), this::onClose);
        actions.obtain("save").configure(font, Component.translatable("unsuspiciousblock.config.save"),
                null, List.of(), () -> {
                    if (canEdit() && saveDraft()) onClose();
                });
        actions.endUpdate();
    }

    @Override
    protected void prepareUi() {
        boolean editable = canEdit();
        for (UiStatefulWidget editor : editors) editor.setEnabled(editable);
        List<UiControl> buttons = actions.controls();
        for (int i = 0; i < buttons.size(); i++) buttons.get(i).setEnabled(i == 1 || editable);
    }

    @Override
    protected void layoutUi() {
        int contentWidth = Math.clamp(width - 28, 0, 420);
        body.setBounds((width - contentWidth) / 2, 48, contentWidth, Math.max(0, height - 98));
        int buttonWidth = Math.clamp((width - 32) / 3, 0, 100);
        int x = (width - (buttonWidth * 3 + 16)) / 2;
        for (UiControl action : actions.controls()) {
            action.setBounds(x, height - 30, buttonWidth, 20);
            x += buttonWidth + 8;
        }
    }

    @Override
    protected UiInputScope inputScope() {
        return scope;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    protected void renderThemedUi(GuiGraphics graphics, int x, int y, float delta) {
        graphics.drawCenteredString(font, title, width / 2, 12, UiTextScheme.DARK.title());
        graphics.drawCenteredString(font, notice(), width / 2, 28, UiTextScheme.DARK.label());
        UiPointer pointer = scope.pointer(x, y).resolve(content);
        body.render(graphics, font, pointer);
        actions.render(graphics, font, pointer);
        if (validationMessage != null) graphics.drawCenteredString(font, validationMessage,
                width / 2, height - 44, UiTextScheme.DARK.negative());
    }

}
