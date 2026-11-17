package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.control.UiFormBody;
import com.meteorite.meteosuikit.api.control.UiStatefulWidget;
import com.meteorite.meteosuikit.api.control.UiTextArea;
import com.meteorite.meteosuikit.api.control.UiTextInput;
import com.meteorite.meteosuikit.api.control.UiThemedScreen;
import com.meteorite.meteosuikit.api.text.TextMeasurer;
import com.meteorite.meteosuikit.api.input.UiChildren;
import com.meteorite.meteosuikit.api.input.UiInputScope;
import com.meteorite.meteosuikit.api.input.UiInputTarget;
import com.meteorite.meteosuikit.api.input.UiPointer;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiThemeContext;
import com.meteorite.meteosuikit.api.style.UiTextScheme;
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
    private final UiControl reset = new UiControl();
    private final UiControl cancel = new UiControl();
    private final UiControl save = new UiControl();
    private final UiChildren<UiControl> actions = new UiChildren<>();
    private final UiChildren<UiInputTarget> content = new UiChildren<>();
    private final UiInputScope scope = new UiInputScope(content);
    private @Nullable Component validationMessage;

    protected ConfigFormScreen(Screen parent, Component title) {
        super(title, new UiThemeContext<>(UnsuspiciousUiTheme.DARK));
        this.parent = parent;
        body = new UiFormBody(theme(), TextMeasurer.of(Minecraft.getInstance().font));
        actions.addAll(List.of(reset, cancel, save));
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
        for (UiControl action : actions) {
            action.setTheme(theme(), UiControlRole.SECONDARY);
            action.setFocusable(true);
        }
        reset.configure(font, Component.translatable("unsuspiciousblock.config.reset"),
                null, List.of(), () -> {
                    resetDraft();
                    validationMessage = null;
                });
        cancel.configure(font, Component.translatable("gui.cancel"), null, List.of(), this::onClose);
        save.configure(font, Component.translatable("unsuspiciousblock.config.save"),
                null, List.of(), () -> {
                    if (canEdit() && saveDraft()) onClose();
                });
    }

    @Override
    protected void prepareUi() {
        boolean editable = canEdit();
        for (UiStatefulWidget editor : editors) editor.setEnabled(editable);
        reset.setEnabled(editable);
        save.setEnabled(editable);
    }

    @Override
    protected void layoutUi() {
        int contentWidth = Math.clamp(width - 28, 0, 420);
        body.setBounds((width - contentWidth) / 2, 48, contentWidth, Math.max(0, height - 98));
        int buttonWidth = Math.clamp((width - 32) / actions.size(), 0, 100);
        int x = (width - (buttonWidth * actions.size() + (actions.size() - 1) * 8)) / 2;
        for (UiControl action : actions) {
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
        for (UiControl action : actions) action.render(graphics, font, pointer);
        if (validationMessage != null) graphics.drawCenteredString(font, validationMessage,
                width / 2, height - 44, UiTextScheme.DARK.negative());
    }

}
