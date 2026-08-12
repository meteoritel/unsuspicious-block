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
import java.util.Objects;

/** ModMenu 配置界面--可视化编辑战利品表追踪配置的三项参数 */
public class ModMenuConfigScreen extends Screen {

    private static final int CONTENT_WIDTH = 320;
    private static final int PREFIX_BOX_HEIGHT = 90;
    private static final int FIELD_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 70;
    private static final int BUTTON_GAP = 8;

    // 固定布局坐标，init 与 render 共用确保一致
    private static final int TITLE_Y = 15;
    private static final int PREFIX_LABEL_Y = 30;
    private static final int PREFIX_BOX_Y = 40;
    private static final int FIELD_LABEL_Y = PREFIX_BOX_Y + PREFIX_BOX_HEIGHT + 6;
    private static final int FIELD_Y = FIELD_LABEL_Y + 11;
    private static final int FIELD_RANGE_Y = FIELD_Y + FIELD_HEIGHT + 2;
    private static final int BUTTON_Y = FIELD_RANGE_Y + 14;
    private static final int NOTICE_Y = BUTTON_Y + FIELD_HEIGHT + 8;

    private final Screen parent;

    private MultiLineEditBox prefixesBox;
    private EditBox maxLogEntriesBox;
    private EditBox trackingTimeoutBox;
    private boolean serverConfigAvailable;

    public ModMenuConfigScreen(Screen parent) {
        super(Component.translatable("unsuspiciousblock.config.title"));
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

        // 路径前缀多行编辑框，每行一个前缀
        Component prefixLabel = Component.translatable("unsuspiciousblock.configgui.loot_table.archaeology_path_prefixes");
        prefixesBox = new MultiLineEditBox(this.font, leftX, PREFIX_BOX_Y, boxWidth, PREFIX_BOX_HEIGHT,
                prefixLabel, prefixLabel);
        prefixesBox.setValue(String.join("\n", config.getArchaeologyPathPrefixes()));
        this.addRenderableWidget(prefixesBox);
        prefixesBox.active = this.serverConfigAvailable;

        // 单表日志上限，仅允许非负整数
        maxLogEntriesBox = new EditBox(this.font, leftX, FIELD_Y, fieldWidth, FIELD_HEIGHT,
                Component.translatable("unsuspiciousblock.configgui.journal.max_log_entries_per_table"));
        maxLogEntriesBox.setValue(String.valueOf(config.getMaxLogEntriesPerTable()));
        maxLogEntriesBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        maxLogEntriesBox.setMaxLength(5);
        this.addRenderableWidget(maxLogEntriesBox);
        maxLogEntriesBox.active = this.serverConfigAvailable;

        // 追踪超时，仅允许非负整数
        trackingTimeoutBox = new EditBox(this.font, leftX + fieldWidth + 10, FIELD_Y, fieldWidth, FIELD_HEIGHT,
                Component.translatable("unsuspiciousblock.configgui.journal.tracking_timeout_ticks"));
        trackingTimeoutBox.setValue(String.valueOf(config.getTrackingTimeoutTicks()));
        trackingTimeoutBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        trackingTimeoutBox.setMaxLength(6);
        this.addRenderableWidget(trackingTimeoutBox);
        trackingTimeoutBox.active = this.serverConfigAvailable;

        // 底部按钮行：重置 / 取消 / 保存
        int totalButtonWidth = BUTTON_WIDTH * 3 + BUTTON_GAP * 2;
        int buttonX = (this.width - totalButtonWidth) / 2;

        Button resetButton = this.addRenderableWidget(Button.builder(
                Component.translatable("unsuspiciousblock.config.reset"),
                b -> resetToDefaults()).bounds(buttonX, BUTTON_Y, BUTTON_WIDTH, FIELD_HEIGHT).build());
        resetButton.active = this.serverConfigAvailable;
        buttonX += BUTTON_WIDTH + BUTTON_GAP;

        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.cancel"),
                b -> this.onClose()).bounds(buttonX, BUTTON_Y, BUTTON_WIDTH, FIELD_HEIGHT).build());
        buttonX += BUTTON_WIDTH + BUTTON_GAP;

        Button saveButton = this.addRenderableWidget(Button.builder(
                Component.translatable("unsuspiciousblock.config.save"),
                b -> saveAndClose()).bounds(buttonX, BUTTON_Y, BUTTON_WIDTH, FIELD_HEIGHT).build());
        saveButton.active = this.serverConfigAvailable;
    }

    // 重置为默认值，仅更新输入框内容，玩家仍需点击保存才会写盘
    private void resetToDefaults() {
        prefixesBox.setValue(String.join("\n", ILootTableConfig.DEFAULT_ARCHAEOLOGY_PATH_PREFIXES));
        maxLogEntriesBox.setValue(String.valueOf(ILootTableConfig.DEFAULT_MAX_LOG_ENTRIES_PER_TABLE));
        trackingTimeoutBox.setValue(String.valueOf(ILootTableConfig.DEFAULT_TRACKING_TIMEOUT_TICKS));
    }

    // 解析输入并保存到磁盘，同时更新内存配置（运行时逻辑实时读取，热更新立即生效）
    private void saveAndClose() {
        java.util.List<String> prefixes = new ArrayList<>();
        for (String line : prefixesBox.getValue().split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                prefixes.add(trimmed);
            }
        }

        int maxLog = parseIntOrDefault(maxLogEntriesBox.getValue()
        );
        long timeout = parseLongOrDefault(trackingTimeoutBox.getValue()
        );

        if (Services.LOOT_TABLE_CONFIG instanceof FabricLootTableConfig fabricConfig) {
            fabricConfig.save(prefixes, maxLog, timeout);
        }
        this.onClose();
    }

    private static int parseIntOrDefault(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return ILootTableConfig.DEFAULT_MAX_LOG_ENTRIES_PER_TABLE;
        }
    }

    private static long parseLongOrDefault(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
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

        // 标题
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFF);

        // 前缀编辑框标签
        guiGraphics.drawString(this.font,
                Component.translatable("unsuspiciousblock.configgui.loot_table.archaeology_path_prefixes"),
                leftX, PREFIX_LABEL_Y, 0xA0A0A0, false);

        // 数字输入框标签
        guiGraphics.drawString(this.font,
                Component.translatable("unsuspiciousblock.configgui.journal.max_log_entries_per_table"),
                leftX, FIELD_LABEL_Y, 0xA0A0A0, false);
        guiGraphics.drawString(this.font,
                Component.translatable("unsuspiciousblock.configgui.journal.tracking_timeout_ticks"),
                leftX + fieldWidth + 10, FIELD_LABEL_Y, 0xA0A0A0, false);

        // 取值范围提示
        guiGraphics.drawString(this.font,
                Component.literal(ILootTableConfig.MIN_MAX_LOG_ENTRIES_PER_TABLE + "-" + ILootTableConfig.MAX_MAX_LOG_ENTRIES_PER_TABLE),
                leftX, FIELD_RANGE_Y, 0x707070, false);
        guiGraphics.drawString(this.font,
                Component.literal(ILootTableConfig.MIN_TRACKING_TIMEOUT_TICKS + "-" + ILootTableConfig.MAX_TRACKING_TIMEOUT_TICKS),
                leftX + fieldWidth + 10, FIELD_RANGE_Y, 0x707070, false);

        if (!this.serverConfigAvailable) {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("unsuspiciousblock.config.server_managed"),
                    this.width / 2, NOTICE_Y, 0xFFAA00);
        }
    }
}
