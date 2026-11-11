package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.meteosuikit.api.style.UiTextScheme;

import com.meteorite.meteosuikit.api.control.UiFocusFollow;

import com.meteorite.meteosuikit.api.control.*;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.layout.UiFormLayout;
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

/** 配置页共享的滚动排版与固定操作区；配置类型、校验和保存由平台页面提供。 */
public abstract class ConfigFormScreen extends UiThemedScreen {
    private static final int CONTENT_TOP = 3;
    private static final int CONTENT_BOTTOM = 8;
    private static final int CONTENT_LEFT = 2;
    private static final int CONTENT_HORIZONTAL_PADDING = 8;
    private static final int LABEL_HEIGHT = 13;
    private static final int ROW_GAP = 11;
    private static final UiFormLayout.Metrics FORM_METRICS = new UiFormLayout.Metrics(
            0, 0, LABEL_HEIGHT, 0, 0, 0, 0, 0, 0, ROW_GAP);
    private record Row(Component label, UiWidget editor, int height) implements UiFormLayout.Control {
        @Override public int preferredWidth(int availableWidth) { return availableWidth; }
        @Override public int measureHeight(int width) { return height; }
    }
    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private UiFormLayout.Layout formLayout = new UiFormLayout.Layout(List.of(), 0);
    private final UiScrollView scroll = new UiScrollView();
    private final UiChildren<UiInputTarget> bodyChildren = new UiChildren<>();
    private final UiControlGroup actions = new UiControlGroup();
    private final UiChildren<UiInputTarget> content = new UiChildren<>();
    private final UiInputScope scope = new UiInputScope(content);
    private @Nullable Component validationMessage;

    protected ConfigFormScreen(Screen parent, Component title) {
        super(title, new UiThemeContext<>(UnsuspiciousUiTheme.DARK));
        this.parent = parent;
        content.add(new UiScrollContainer(scroll, bodyChildren));
        content.add(actions);
        scope.setEscape(this::onClose);
        scope.focus().setListener(new UiFocusFollow(scroll,
                target -> rows.stream().anyMatch(row -> row.editor() == target),
                this::rowContentBounds, this::layoutUi));
    }

    private UiRect rowContentBounds(UiFocusTarget target) {
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).editor() != target) continue;
            UiFormLayout.RowGeometry row = formLayout.rows().get(index);
            return new UiRect(0, CONTENT_TOP + row.y(), row.control().width(), row.height());
        }
        throw new IllegalArgumentException("Focus target is not a form row");
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
        bodyChildren.add(editor);
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
        List<UiFormLayout.Row> layoutRows = rows.stream().map(row -> new UiFormLayout.Row(
                row.label(), true, false, true, -1, row, List.of())).toList();
        int availableWidth = Math.max(0, scroll.viewport().width() - CONTENT_HORIZONTAL_PADDING);
        formLayout = UiFormLayout.layout(layoutRows, availableWidth, 0,
                FORM_METRICS, TextMeasurer.of(font));
        scroll.setContentHeight(rows.isEmpty() ? 0 : CONTENT_TOP + formLayout.height() + CONTENT_BOTTOM);
        scroll.setScrollbarVisible(scroll.maxOffset() > 0);
        int finalWidth = Math.max(0, scroll.contentWidth() - CONTENT_HORIZONTAL_PADDING);
        if (finalWidth != availableWidth) {
            formLayout = UiFormLayout.layout(layoutRows, finalWidth, 0, FORM_METRICS, TextMeasurer.of(font));
        }
        for (int index = 0; index < rows.size(); index++) {
            UiFormLayout.RowGeometry row = formLayout.rows().get(index);
            UiRect control = row.control();
            rows.get(index).editor().setBounds(scroll.viewport().x() + CONTENT_LEFT + control.x(),
                    scroll.toScreenY(CONTENT_TOP + row.y() + control.y()), control.width(), control.height());
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
        graphics.drawCenteredString(font, title, width / 2, 12, UiTextScheme.DARK.title());
        graphics.drawCenteredString(font, notice(), width / 2, 28, UiTextScheme.DARK.label());
        UiPointer pointer = scope.pointer(x, y).resolve(content);
        UiRect viewport = scroll.viewport();
        graphics.enableScissor(viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            UiFormLayout.RowGeometry geometry = formLayout.rows().get(index);
            graphics.drawString(font, row.label(), row.editor().bounds().x(),
                    scroll.toScreenY(CONTENT_TOP + geometry.y()),
                    UiTextScheme.DARK.body(), false);
            row.editor().render(graphics, font, pointer.restrict(viewport.contains(x, y)));
        }
        graphics.disableScissor();
        scroll.renderScrollbar(graphics, theme());
        actions.render(graphics, font, pointer);
        if (validationMessage != null) graphics.drawCenteredString(font, validationMessage,
                width / 2, height - 44, UiTextScheme.DARK.negative());
    }

}
