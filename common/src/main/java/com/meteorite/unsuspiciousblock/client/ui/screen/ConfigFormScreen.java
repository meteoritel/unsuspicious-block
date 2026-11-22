package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.meteosuikit.api.control.UiControl;
import com.meteorite.meteosuikit.api.control.UiFormBody;
import com.meteorite.meteosuikit.api.control.UiWidgetRow;
import com.meteorite.meteosuikit.api.layout.UiLinearLayout;
import com.meteorite.meteosuikit.api.control.UiStatefulWidget;
import com.meteorite.meteosuikit.api.control.UiTextArea;
import com.meteorite.meteosuikit.api.control.UiTextInput;
import com.meteorite.meteosuikit.api.control.UiThemedScreen;
import com.meteorite.meteosuikit.api.control.UiWidget;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.text.TextMeasurer;
import com.meteorite.meteosuikit.api.input.UiChildren;
import com.meteorite.meteosuikit.api.input.UiFocusTarget;
import com.meteorite.meteosuikit.api.input.UiInputScope;
import com.meteorite.meteosuikit.api.input.UiInputTarget;
import com.meteorite.meteosuikit.api.input.UiPointer;
import com.meteorite.meteosuikit.api.render.UiTooltipPresenter;
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
 * 配置页共享的滚动排版与固定操作区；页面提供字段、校验与提交。
 */
public abstract class ConfigFormScreen extends UiThemedScreen {
    private final Screen parent;
    private final List<UiStatefulWidget> editors = new ArrayList<>();
    private final List<UiFocusTarget> compactTargets = new ArrayList<>();
    private final UiFormBody body;
    private final UiControl reset = new UiControl();
    private final UiControl cancel = new UiControl();
    private final UiControl save = new UiControl();
    private final List<UiControl> actions = List.of(reset, cancel, save);
    private final UiWidgetRow footer = new UiWidgetRow(8, UiWidgetRow.Alignment.CENTER);
    private final UiChildren<UiInputTarget> content = new UiChildren<>();
    private final UiInputScope scope = new UiInputScope(content);
    private @Nullable Component validationMessage;

    protected ConfigFormScreen(Screen parent, Component title) {
        super(title, new UiThemeContext<>(UnsuspiciousUiTheme.DARK));
        this.parent = parent;
        body = new UiFormBody(theme(), TextMeasurer.of(Minecraft.getInstance().font));
        for (UiControl action : actions) footer.add(action, UiLinearLayout.Child.remain(0, 100));
        content.add(body);
        content.add(footer);
        scope.setEscape(this::onClose);
        scope.focus().setListener((previous, current) -> revealField(current));
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

    /** 将一行组合控件加入滚动正文，子控件的可用状态由调用方设置。 */
    protected final void addRow(UiWidgetRow row, int height) {
        body.add(Component.empty(), row, height);
    }

    /** 将紧凑控件组加入正文，Tab 滚动跟随组内的实际控件。 */
    protected final void addCompactSection(UiWidget section, int height) {
        body.add(Component.empty(), section, height);
        section.addFocusTargets(compactTargets::add);
    }

    private void revealField(@Nullable UiFocusTarget target) {
        if (target == null) return;
        if (!compactTargets.contains(target)) {
            body.reveal(target);
            return;
        }
        // 鼠标按下后保持控件位置，释放时仍沿同一命中处理。
        if (!target.isFocusVisible()) return;
        int offset = body.offset();
        UiRect viewport = body.bounds();
        UiRect field = target.bounds();
        if (field.y() < viewport.y()) {
            body.setOffset(offset + field.y() - viewport.y());
        } else if (field.bottom() > viewport.bottom()) {
            body.setOffset(offset + field.bottom() - viewport.bottom());
        }
    }

    protected abstract void resetDraft();

    protected Component resetLabel() {
        return Component.translatable("unsuspiciousblock.config.reset");
    }

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
        reset.configure(font, resetLabel(),
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
        int width = viewport().logicalWidth();
        int height = viewport().logicalHeight();
        int contentWidth = Math.clamp(width - 28, 0, 420);
        body.setBounds((width - contentWidth) / 2, 48, contentWidth, Math.max(0, height - 98));
        footer.setBounds(8, height - 30, Math.max(0, width - 16), 20);
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
        int width = viewport().logicalWidth();
        int height = viewport().logicalHeight();
        graphics.drawCenteredString(font, title, width / 2, 12, UiTextScheme.DARK.title());
        graphics.drawCenteredString(font, notice(), width / 2, 28, UiTextScheme.DARK.label());
        UiPointer pointer = scope.pointer(x, y).resolve(content);
        body.render(graphics, font, pointer);
        footer.render(graphics, font, pointer);
        if (pointer.allowsTooltip() && pointer.hit() != null) {
            UiTooltipPresenter.render(graphics, font, pointer.hit(), theme().get().tooltipSkin(), x, y);
        }
        if (validationMessage != null) graphics.drawCenteredString(font, validationMessage,
                width / 2, height - 44, UiTextScheme.DARK.negative());
    }

}
