package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.meteosuikit.api.control.*;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiThemeContext;
import com.meteorite.unsuspiciousblock.client.ui.support.UiTextPalette;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateJournalLogRetentionPayload;
import com.meteorite.unsuspiciousblock.client.network.ClientNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 日志保留与一次性清理设置；数值草稿在明确提交时按对应业务范围校验。 */
public final class JournalLogRetentionScreen extends UiThemedScreen {
    private static final String PREFIX = "screen.unsuspiciousblock.archaeology_journal.log_retention.";
    private final Screen parent;
    private final ResourceLocation tableId;
    private final UiChildren<UiInputTarget> content = new UiChildren<>();
    private final UiInputScope scope = new UiInputScope(content);
    private final UiScrollView scroll = new UiScrollView();
    private final UiTextInput retention;
    private final UiTextInput recent;
    private final UiControl apply = new UiControl();
    private final UiControl prune = new UiControl();
    private final UiControl done = new UiControl();
    private @Nullable Component validationMessage;

    public JournalLogRetentionScreen(Screen parent, ResourceLocation tableId, int initialRetentionLimit, int currentEntryCount) {
        super(Component.translatable(PREFIX + "title"), new UiThemeContext<>(UnsuspiciousUiTheme.DARK));
        this.parent = parent;
        this.tableId = tableId;
        int limit = Math.max(1, initialRetentionLimit);
        retention = numericField(limit);
        recent = numericField(Math.clamp(currentEntryCount, 0, limit));
        RetentionBody body = new RetentionBody();
        body.children.addAll(List.of(scroll, retention, apply, recent, prune));
        content.addAll(List.of(body, done));
        scope.setEscape(this::onClose);
        scope.focus().setListener(new UiFocusFollow(scroll,
                target -> target == retention || target == apply || target == recent || target == prune,
                target -> new UiRect(0, target.bounds().y() - scroll.viewport().y() + scroll.offset() - 13,
                        target.bounds().width(), target.bounds().height() + 13), this::layoutUi));
    }

    private UiTextInput numericField(int value) {
        return new UiTextInput(theme(), Minecraft.getInstance().font, Component.empty())
                .setMaxLength(10).setFilter(text -> text.chars().allMatch(c -> c >= '0' && c <= '9'))
                .setValue(Integer.toString(value)).setOnCancel(this::onClose);
    }

    @Override protected void init() {
        configure(apply, Component.translatable(PREFIX + "apply_limit"),
                () -> submit(UpdateJournalLogRetentionPayload.Action.SET_LIMIT, retention, 1));
        configure(prune, Component.translatable(PREFIX + "prune_now"),
                () -> submit(UpdateJournalLogRetentionPayload.Action.PRUNE_KEEP_RECENT, recent, 0));
        configure(done, Component.translatable("gui.done"), this::onClose);
    }

    private void configure(UiControl control, Component label, UiAction action) {
        control.setTheme(theme(), UiControlRole.SECONDARY);
        control.setFocusable(true);
        control.configure(font, label, null, List.of(), action);
    }

    @Override protected UiInputScope inputScope() { return scope; }

    @Override protected void layoutUi() {
        int bodyWidth = Math.clamp(width - 24, 0, 300);
        scroll.setViewport((width - bodyWidth) / 2, 50, bodyWidth, Math.max(0, height - 100));
        scroll.setContentHeight(154);
        scroll.setScrollbarVisible(scroll.maxOffset() > 0);
        int fieldWidth = Math.min(bodyWidth, 112);
        int fieldX = (width - fieldWidth) / 2;
        int top = scroll.toScreenY(0);
        retention.setBounds(fieldX, top + 16, fieldWidth, 20);
        apply.setBounds(fieldX, top + 40, fieldWidth, 20);
        recent.setBounds(fieldX, top + 86, fieldWidth, 20);
        prune.setBounds(fieldX, top + 110, fieldWidth, 20);
        done.setBounds(fieldX, height - 30, fieldWidth, 20);
    }

    private void submit(UpdateJournalLogRetentionPayload.Action action, UiTextInput field, int minimum) {
        int value;
        try {
            value = Integer.parseInt(field.value());
        } catch (NumberFormatException exception) {
            validationMessage = Component.translatable(PREFIX + "invalid_number");
            return;
        }
        if (value < minimum) {
            validationMessage = Component.translatable(PREFIX + "invalid_number");
            return;
        }
        ClientNetwork.sendToServer(new UpdateJournalLogRetentionPayload(tableId, action, value));
        onClose();
    }

    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }

    @Override protected void renderThemedUi(GuiGraphics graphics, int x, int y, float delta) {
        graphics.drawCenteredString(font, title, width / 2, 18, UiTextPalette.Dark.TITLE);
        graphics.drawCenteredString(font, Component.translatable(PREFIX + "table", tableId.toString()),
                width / 2, 33, UiTextPalette.Dark.LABEL);
        var pointer = scope.pointer(x, y).resolve(content);
        UiRect viewport = scroll.viewport();
        graphics.enableScissor(viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
        graphics.drawCenteredString(font, Component.translatable(PREFIX + "limit_label"),
                width / 2, retention.bounds().y() - 13, UiTextPalette.Dark.BODY);
        graphics.drawCenteredString(font, Component.translatable(PREFIX + "keep_recent_label"),
                width / 2, recent.bounds().y() - 13, UiTextPalette.Dark.BODY);
        graphics.drawCenteredString(font, Component.translatable(PREFIX + "note_protection"),
                width / 2, prune.bounds().bottom() + 6, UiTextPalette.Dark.ACCENT);
        var bodyPointer = pointer.restrict(viewport.contains(x, y));
        for (UiWidget widget : List.of(retention, apply, recent, prune)) widget.render(graphics, font, bodyPointer);
        graphics.disableScissor();
        scroll.renderScrollbar(graphics, theme());
        done.render(graphics, font, pointer);
        if (validationMessage != null) graphics.drawCenteredString(font, validationMessage,
                width / 2, height - 13, UiTextPalette.Dark.NEGATIVE);
    }

    @Override public boolean isPauseScreen() { return false; }

    /** 视口裁剪普通命中，持续交互由作用域送达实际控件。 */
    private final class RetentionBody implements UiInputContainer {
        private final UiChildren<UiInputTarget> children = new UiChildren<>();
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
