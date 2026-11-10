package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.meteosuikit.api.control.*;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.meteosuikit.api.style.UiThemeContext;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** 配置页共享的滚动排版与固定操作区；配置类型、校验和保存由平台页面提供。 */
public abstract class ConfigFormScreen extends UiThemedScreen {
    private record Row(Component label, UiWidget editor, int height) { }
    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private final UiScrollView scroll = new UiScrollView();
    private final FormBody body = new FormBody();
    private final UiControlGroup actions = new UiControlGroup();
    private final UiChildren<UiInputTarget> content = new UiChildren<>();
    private final UiInputScope scope = new UiInputScope(content);
    private @Nullable Component validationMessage;

    protected ConfigFormScreen(Screen parent, Component title) {
        super(title, new UiThemeContext<>(UnsuspiciousUiTheme.DARK));
        this.parent = parent;
        content.add(body);
        content.add(actions);
        scope.setEscape(this::onClose);
        scope.focus().setListener(new UiFocusFollow(scroll,
                target -> rows.stream().anyMatch(row -> row.editor() == target),
                target -> {
                    UiRect bounds = target.bounds();
                    return new UiRect(0, bounds.y() - scroll.viewport().y() + scroll.offset() - 13,
                            bounds.width(), bounds.height() + 13);
                }, this::layoutUi));
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

    protected final void addField(Component label, UiWidget editor, int height) {
        rows.add(new Row(label, editor, height));
        body.children.add(editor);
    }

    protected abstract void resetDraft();
    protected abstract boolean saveDraft();
    protected boolean canEdit() { return true; }
    protected Component notice() { return Component.empty(); }
    protected final void showValidation(Component message) { validationMessage = message; }

    @Override protected void init() {
        actions.setTheme(theme());
        actions.beginUpdate();
        actions.obtain("reset").configure(font, Component.translatable("unsuspiciousblock.config.reset"),
                null, List.of(), () -> { resetDraft(); validationMessage = null; });
        actions.obtain("cancel").configure(font, Component.translatable("gui.cancel"), null, List.of(), this::onClose);
        actions.obtain("save").configure(font, Component.translatable("unsuspiciousblock.config.save"),
                null, List.of(), () -> { if (canEdit() && saveDraft()) onClose(); });
        actions.endUpdate();
    }

    @Override protected void prepareUi() {
        boolean editable = canEdit();
        for (Row row : rows) if (row.editor() instanceof UiShellWidget widget) widget.shell().setEnabled(editable);
        List<UiControl> buttons = actions.controls();
        for (int i = 0; i < buttons.size(); i++) buttons.get(i).setEnabled(i == 1 || editable);
    }

    @Override protected void layoutUi() {
        int contentWidth = Math.clamp(width - 28, 0, 420);
        scroll.setViewport((width - contentWidth) / 2, 48, contentWidth, Math.max(0, height - 98));
        int contentHeight = rows.stream().mapToInt(row -> row.height() + 24).sum();
        scroll.setContentHeight(contentHeight);
        scroll.setScrollbarVisible(scroll.maxOffset() > 0);
        int y = 16;
        for (Row row : rows) {
            row.editor().setBounds(scroll.viewport().x() + 2, scroll.toScreenY(y),
                    Math.max(0, scroll.contentWidth() - 8), row.height());
            y += row.height() + 24;
        }
        int buttonWidth = Math.clamp((width - 32) / 3, 0, 100);
        int x = (width - (buttonWidth * 3 + 16)) / 2;
        for (UiControl action : actions.controls()) {
            action.setBounds(x, height - 30, buttonWidth, 20);
            x += buttonWidth + 8;
        }
    }

    @Override protected UiInputScope inputScope() { return scope; }
    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }

    @Override protected void renderThemedUi(GuiGraphics graphics, int x, int y, float delta) {
        graphics.drawCenteredString(font, title, width / 2, 12, UiTextPalette.Dark.TITLE);
        graphics.drawCenteredString(font, notice(), width / 2, 28, UiTextPalette.Dark.LABEL);
        UiPointer pointer = scope.pointer(x, y).resolve(content);
        UiRect viewport = scroll.viewport();
        graphics.enableScissor(viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
        for (Row row : rows) {
            graphics.drawString(font, row.label(), row.editor().bounds().x(), row.editor().bounds().y() - 13,
                    UiTextPalette.Dark.BODY, false);
            row.editor().render(graphics, font, pointer.restrict(viewport.contains(x, y)));
        }
        graphics.disableScissor();
        scroll.renderScrollbar(graphics, theme());
        actions.render(graphics, font, pointer);
        if (validationMessage != null) graphics.drawCenteredString(font, validationMessage,
                width / 2, height - 44, UiTextPalette.Dark.NEGATIVE);
    }

    /** 表单只在视口内命中；捕获仍由所属 scope 直接送达实际控件。 */
    private final class FormBody implements UiInputContainer {
        private final UiChildren<UiInputTarget> children = new UiChildren<>();
        private FormBody() { children.add(scroll); }
        @Override public UiChildren<UiInputTarget> inputChildren() { return children; }
        @Override public @Nullable UiPointerHit hitTest(double x, double y) {
            if (!scroll.contains(x, y)) return null;
            UiPointerHit bar = scroll.hitTest(x, y);
            if (bar != null) return bar.within(this);
            UiPointerHit hit = UiInputContainer.super.hitTest(x, y);
            return hit == null ? UiPointerHit.leaf(this, x, y) : hit;
        }
        @Override public boolean mouseScrolled(UiInputContext context, double x, double y) {
            return UiInputContainer.super.mouseScrolled(context, x, y) || scroll.scrollBy(y);
        }
    }
}
