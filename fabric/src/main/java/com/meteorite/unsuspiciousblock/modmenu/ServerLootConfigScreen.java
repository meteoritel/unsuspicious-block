package com.meteorite.unsuspiciousblock.modmenu;

import com.meteorite.unsuspiciousblock.platform.FabricLootTableConfig;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.platform.services.ILootTableConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 当前世界的战利品服务端配置页面，仅允许本机集成服务器写入。
 */
public class ServerLootConfigScreen extends Screen {

    private static final int CONTENT_WIDTH = 320;
    private static final int PREFIX_BOX_HEIGHT = 90;
    private static final int FIELD_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 70;
    private static final int BUTTON_GAP = 8;
    private static final int PREFIX_LABEL_Y = 40;
    private static final int PREFIX_BOX_Y = 51;
    private static final int FIELD_LABEL_Y = PREFIX_BOX_Y + PREFIX_BOX_HEIGHT + 6;
    private static final int FIELD_Y = FIELD_LABEL_Y + 11;
    private static final int FIELD_RANGE_Y = FIELD_Y + FIELD_HEIGHT + 2;

    private final Screen parent;

    private MultiLineEditBox prefixesBox;
    private EditBox maxLogEntriesBox;
    private EditBox trackingTimeoutBox;
    private boolean serverConfigAvailable;
    private boolean resetExclusionsPending;

    public ServerLootConfigScreen(Screen parent) {
        super(Component.translatable("unsuspiciousblock.config.server.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ILootTableConfig config = Services.LOOT_TABLE_CONFIG;
        this.serverConfigAvailable = config instanceof FabricLootTableConfig fabricConfig
                && fabricConfig.hasActiveServerConfig();

        int boxWidth = Math.min(CONTENT_WIDTH, this.width - 40);
        int leftX = (this.width - boxWidth) / 2;
        int fieldWidth = (boxWidth - 10) / 2;

        Component prefixLabel = Component.translatable(
                "unsuspiciousblock.configgui.loot_table.archaeology_path_prefixes");
        prefixesBox = new MultiLineEditBox(this.font, leftX, PREFIX_BOX_Y, boxWidth, PREFIX_BOX_HEIGHT,
                prefixLabel, prefixLabel);
        prefixesBox.setValue(String.join("\n", config.getArchaeologyPathPrefixes()));
        prefixesBox.active = this.serverConfigAvailable;
        this.addRenderableWidget(prefixesBox);

        maxLogEntriesBox = numericField(leftX, FIELD_Y, fieldWidth, 5,
                "unsuspiciousblock.configgui.journal.max_log_entries_per_table",
                String.valueOf(config.getMaxLogEntriesPerTable()));
        trackingTimeoutBox = numericField(leftX + fieldWidth + 10, FIELD_Y, fieldWidth, 6,
                "unsuspiciousblock.configgui.journal.tracking_timeout_ticks",
                String.valueOf(config.getTrackingTimeoutTicks()));

        int totalButtonWidth = BUTTON_WIDTH * 3 + BUTTON_GAP * 2;
        int buttonX = (this.width - totalButtonWidth) / 2;
        int buttonY = Math.min(this.height - 28, FIELD_RANGE_Y + 16);
        Button resetButton = this.addRenderableWidget(Button.builder(
                Component.translatable("unsuspiciousblock.config.reset"),
                button -> resetToDefaults())
                .bounds(buttonX, buttonY, BUTTON_WIDTH, FIELD_HEIGHT)
                .build());
        resetButton.active = this.serverConfigAvailable;
        buttonX += BUTTON_WIDTH + BUTTON_GAP;
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.cancel"),
                button -> this.onClose())
                .bounds(buttonX, buttonY, BUTTON_WIDTH, FIELD_HEIGHT)
                .build());
        buttonX += BUTTON_WIDTH + BUTTON_GAP;
        Button saveButton = this.addRenderableWidget(Button.builder(
                Component.translatable("unsuspiciousblock.config.save"),
                button -> saveAndClose())
                .bounds(buttonX, buttonY, BUTTON_WIDTH, FIELD_HEIGHT)
                .build());
        saveButton.active = this.serverConfigAvailable;
    }

    private EditBox numericField(int x, int y, int width, int maxLength, String key, String value) {
        EditBox field = new EditBox(this.font, x, y, width, FIELD_HEIGHT, Component.translatable(key));
        field.setValue(value);
        field.setFilter(text -> text.isEmpty() || text.matches("\\d+"));
        field.setMaxLength(maxLength);
        field.active = this.serverConfigAvailable;
        return this.addRenderableWidget(field);
    }

    // 恢复所有默认值；新版配置中的精确排除项会在保存时一并清空
    private void resetToDefaults() {
        prefixesBox.setValue(String.join("\n", ILootTableConfig.DEFAULT_ARCHAEOLOGY_PATH_PREFIXES));
        maxLogEntriesBox.setValue(String.valueOf(ILootTableConfig.DEFAULT_MAX_LOG_ENTRIES_PER_TABLE));
        trackingTimeoutBox.setValue(String.valueOf(ILootTableConfig.DEFAULT_TRACKING_TIMEOUT_TICKS));
        this.resetExclusionsPending = true;
    }

    // 保存当前世界的服务端配置，运行时管理器会处理追踪目录重建
    private void saveAndClose() {
        java.util.List<String> prefixes = new ArrayList<>();
        for (String line : prefixesBox.getValue().split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                prefixes.add(trimmed);
            }
        }

        if (Services.LOOT_TABLE_CONFIG instanceof FabricLootTableConfig fabricConfig) {
            fabricConfig.save(prefixes,
                    this.resetExclusionsPending ? List.of() : fabricConfig.getExcludedLootTables(),
                    parseIntOrDefault(maxLogEntriesBox.getValue()),
                    parseLongOrDefault(trackingTimeoutBox.getValue()));
        }
        this.onClose();
    }

    private static int parseIntOrDefault(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return ILootTableConfig.DEFAULT_MAX_LOG_ENTRIES_PER_TABLE;
        }
    }

    private static long parseLongOrDefault(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return ILootTableConfig.DEFAULT_TRACKING_TIMEOUT_TICKS;
        }
    }

    @Override
    public void onClose() {
        Objects.requireNonNull(this.minecraft, "minecraft must not be null while screen is active").setScreen(parent);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int boxWidth = Math.min(CONTENT_WIDTH, this.width - 40);
        int leftX = (this.width - boxWidth) / 2;
        int fieldWidth = (boxWidth - 10) / 2;
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable(noticeKey()),
                this.width / 2, 26, noticeColor());

        guiGraphics.drawString(this.font,
                Component.translatable("unsuspiciousblock.configgui.loot_table.archaeology_path_prefixes"),
                leftX, PREFIX_LABEL_Y, 0xA0A0A0, false);
        guiGraphics.drawString(this.font,
                Component.translatable("unsuspiciousblock.configgui.journal.max_log_entries_per_table"),
                leftX, FIELD_LABEL_Y, 0xA0A0A0, false);
        guiGraphics.drawString(this.font,
                Component.translatable("unsuspiciousblock.configgui.journal.tracking_timeout_ticks"),
                leftX + fieldWidth + 10, FIELD_LABEL_Y, 0xA0A0A0, false);
        guiGraphics.drawString(this.font,
                Component.literal(ILootTableConfig.MIN_MAX_LOG_ENTRIES_PER_TABLE + "-"
                        + ILootTableConfig.MAX_MAX_LOG_ENTRIES_PER_TABLE),
                leftX, FIELD_RANGE_Y, 0x707070, false);
        guiGraphics.drawString(this.font,
                Component.literal(ILootTableConfig.MIN_TRACKING_TIMEOUT_TICKS + "-"
                        + ILootTableConfig.MAX_TRACKING_TIMEOUT_TICKS),
                leftX + fieldWidth + 10, FIELD_RANGE_Y, 0x707070, false);
    }

    private String noticeKey() {
        if (!this.serverConfigAvailable) {
            return "unsuspiciousblock.config.server_managed";
        }
        return this.resetExclusionsPending
                ? "unsuspiciousblock.config.defaults_pending"
                : "unsuspiciousblock.config.server.integrated";
    }

    private int noticeColor() {
        if (!this.serverConfigAvailable) {
            return 0xFFAA00;
        }
        return this.resetExclusionsPending ? 0x55FF55 : 0xA0A0A0;
    }
}
