package com.meteorite.unsuspiciousblock.client.ui.screen;

import com.meteorite.meteosuikit.api.style.UiTextScheme;

import com.meteorite.meteosuikit.api.layout.UiFormLayout;
import com.meteorite.meteosuikit.api.text.TextMeasurer;

import com.meteorite.meteosuikit.api.input.UiAction;

import com.meteorite.meteosuikit.api.control.*;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.input.*;
import com.meteorite.meteosuikit.api.style.UiControlRole;
import com.meteorite.meteosuikit.api.style.UiThemeContext;
import com.meteorite.unsuspiciousblock.client.ui.theme.UnsuspiciousUiTheme;
import com.meteorite.unsuspiciousblock.core.network.payload.c2s.UpdateJournalLogRetentionPayload;
import com.meteorite.unsuspiciousblock.client.network.ClientNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import com.meteorite.unsuspiciousblock.client.ui.support.JournalClientConnection;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.JournalLogEditResultPayload;

/**
 * 日志保留与一次性清理设置；数值草稿在明确提交时按对应业务范围校验。
 */
public final class JournalLogRetentionScreen extends UiThemedScreen {
    private static final String PREFIX = "screen.unsuspiciousblock.archaeology_journal.log_retention.";
    private final Screen parent;
    private @Nullable UUID pending;
    private final ResourceLocation tableId;
    private final UiChildren<UiInputTarget> content = new UiChildren<>();
    private final UiInputScope scope = new UiInputScope(content);
    private final UiFormBody body;
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
        Component limitLabel = Component.translatable(PREFIX + "limit_label");
        Component recentLabel = Component.translatable(PREFIX + "keep_recent_label");
        retention = numericField(limitLabel, limit);
        recent = numericField(recentLabel, Math.clamp(currentEntryCount, 0, limit));
        body = new UiFormBody(theme(), TextMeasurer.of(Minecraft.getInstance().font));
        body.add(limitLabel, retention, 20);
        body.add(Component.empty(), apply, 20);
        body.add(recentLabel, recent, 20);
        body.add(Component.empty(), prune, 20);
        ProtectionNotice notice = new ProtectionNotice();
        body.add(Component.empty(), notice, notice, List.of());
        content.addAll(List.of(body, done));
        scope.setEscape(this::onClose);
        scope.focus().setListener((previous, current) -> body.reveal(current));
    }

    private static final class ProtectionNotice implements UiWidget, UiFormLayout.Control {
        private final Component message = Component.translatable(PREFIX + "note_protection");
        private UiRect bounds = new UiRect(0, 0, 0, 0);
        private List<Component> lines = List.of();

        @Override
        public int preferredWidth(int width) {
            return width;
        }

        @Override
        public int measureHeight(int width) {
            TextMeasurer text = TextMeasurer.of(Minecraft.getInstance().font);
            lines = text.wrap(message, width);
            return lines.size() * (text.lineHeight() + 2);
        }

        @Override
        public UiRect bounds() {
            return bounds;
        }

        @Override
        public void setBounds(int x, int y, int width, int height) {
            bounds = new UiRect(x, y, width, height);
        }

        @Override
        public void render(GuiGraphics graphics, Font font, UiPointer pointer) {
            int y = bounds.y();
            for (Component line : lines) {
                graphics.drawString(font, line, bounds.x(), y, UiTextScheme.DARK.accent(), false);
                y += font.lineHeight + 2;
            }
        }
    }

    private UiTextInput numericField(Component label, int value) {
        return new UiTextInput(theme(), Minecraft.getInstance().font, label)
                .setMaxLength(10).setFilter(text -> text.chars().allMatch(c -> c >= '0' && c <= '9'))
                .setValue(Integer.toString(value)).setOnCancel(this::onClose);
    }

    @Override
    protected void init() {
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

    @Override
    protected UiInputScope inputScope() {
        return scope;
    }

    @Override
    protected void layoutUi() {
        int bodyWidth = Math.clamp(width - 24, 0, 300);
        body.setBounds((width - bodyWidth) / 2, 50, bodyWidth, Math.max(0, height - 100));
        int fieldWidth = Math.min(bodyWidth, 112);
        done.setBounds((width - fieldWidth) / 2, height - 30, fieldWidth, 20);
    }

    private void submit(UpdateJournalLogRetentionPayload.Action action, UiTextInput field, int minimum) {
        int value;
        try {
            value = Integer.parseInt(field.value());
        } catch (NumberFormatException exception) {
            validationMessage = Component.translatable(PREFIX + "invalid_number");
            return;
        }
        int limit = JournalClientConnection.log().totals().limit();
        if (value < minimum || value > limit) {
            validationMessage = Component.translatable(PREFIX + "invalid_range", limit);
            return;
        }
        UUID id = UUID.randomUUID();
        var operation = action == UpdateJournalLogRetentionPayload.Action.SET_LIMIT
                ? JournalLogEditResultPayload.Operation.RETENTION : JournalLogEditResultPayload.Operation.PRUNE;
        if (!JournalClientConnection.expectEdit(id, operation, tableId, this::finish)) return;
        pending = id;
        apply.setEnabled(false);
        prune.setEnabled(false);
        ClientNetwork.sendToServer(new UpdateJournalLogRetentionPayload(id, tableId, action, value));
    }

    private void finish(JournalLogEditResultPayload result) {
        pending = null;
        apply.setEnabled(true);
        prune.setEnabled(true);
        if (result.result() == JournalLogEditResultPayload.Result.APPLIED) {
            validationMessage = result.operation() == JournalLogEditResultPayload.Operation.RETENTION
                    ? Component.translatable(PREFIX + "limit_saved", result.retention(), result.removedCount())
                    : Component.translatable(PREFIX + "pruned", result.removedCount());
        } else {
            validationMessage = JournalClientConnection.editMessage(result);
        }
    }

    @Override
    public void removed() {
        JournalClientConnection.abandonEdit(pending);
        pending = null;
        super.removed();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    protected void renderThemedUi(GuiGraphics graphics, int x, int y, float delta) {
        graphics.drawCenteredString(font, title, width / 2, 18, UiTextScheme.DARK.title());
        graphics.drawCenteredString(font, Component.translatable(PREFIX + "table", tableId.toString()),
                width / 2, 33, UiTextScheme.DARK.label());
        var pointer = scope.pointer(x, y).resolve(content);
        body.render(graphics, font, pointer);
        done.render(graphics, font, pointer);
        if (validationMessage != null) graphics.drawCenteredString(font, validationMessage,
                width / 2, height - 13, UiTextScheme.DARK.negative());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

}
