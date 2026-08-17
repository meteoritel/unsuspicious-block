package com.meteorite.unsuspiciousblock.modmenu;

import com.meteorite.unsuspiciousblock.platform.FabricLootTableConfig;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.platform.services.ISpiritCatConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Objects;

/**
 * Fabric 灵体猫通用配置页面，编辑本机托管服务器使用的全局参数。
 */
public class SpiritCatConfigScreen extends Screen {

    private static final int CONTENT_WIDTH = 360;
    private static final int FIELD_WIDTH = 92;
    private static final int FIELD_HEIGHT = 18;
    private static final int ROW_HEIGHT = 22;
    private static final int BUTTON_WIDTH = 70;
    private static final int BUTTON_GAP = 8;
    private static final int FIRST_ROW_Y = 56;
    private static final int SECOND_SECTION_Y = FIRST_ROW_Y + ROW_HEIGHT * 3 + 4;

    private static final String[] FIELD_KEYS = {
            "messenger_lifetime_ticks",
            "swordsman_lifetime_ticks",
            "merchant_lifetime_ticks",
            "invulnerability_duration_ticks",
            "resistance_duration_ticks",
            "fire_resistance_duration_ticks"
    };

    private static final int[] DEFAULT_VALUES = {
            ISpiritCatConfig.DEFAULT_MESSENGER_LIFETIME_TICKS,
            ISpiritCatConfig.DEFAULT_SWORDSMAN_LIFETIME_TICKS,
            ISpiritCatConfig.DEFAULT_MERCHANT_LIFETIME_TICKS,
            ISpiritCatConfig.DEFAULT_INVULNERABILITY_DURATION_TICKS,
            ISpiritCatConfig.DEFAULT_RESISTANCE_DURATION_TICKS,
            ISpiritCatConfig.DEFAULT_FIRE_RESISTANCE_DURATION_TICKS
    };

    private final Screen parent;
    private final EditBox[] fields = new EditBox[FIELD_KEYS.length];

    public SpiritCatConfigScreen(Screen parent) {
        super(Component.translatable("unsuspiciousblock.config.common.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ISpiritCatConfig config = Services.SPIRIT_CAT_CONFIG;
        int contentWidth = Math.min(CONTENT_WIDTH, this.width - 30);
        int leftX = (this.width - contentWidth) / 2;
        int fieldX = leftX + contentWidth - FIELD_WIDTH;
        int[] values = {
                config.getMessengerLifetimeTicks(),
                config.getSwordsmanLifetimeTicks(),
                config.getMerchantLifetimeTicks(),
                config.getInvulnerabilityDurationTicks(),
                config.getResistanceDurationTicks(),
                config.getFireResistanceDurationTicks()
        };

        for (int index = 0; index < fields.length; index++) {
            int y = rowY(index);
            EditBox field = new EditBox(this.font, fieldX, y, FIELD_WIDTH, FIELD_HEIGHT,
                    Component.translatable("unsuspiciousblock.config.spirit_cat." + FIELD_KEYS[index]));
            field.setValue(String.valueOf(values[index]));
            field.setFilter(value -> value.isEmpty() || value.matches("\\d+"));
            field.setMaxLength(7);
            fields[index] = this.addRenderableWidget(field);
        }

        int totalButtonWidth = BUTTON_WIDTH * 3 + BUTTON_GAP * 2;
        int buttonX = (this.width - totalButtonWidth) / 2;
        int buttonY = Math.min(this.height - 28, rowY(fields.length - 1) + FIELD_HEIGHT + 10);
        this.addRenderableWidget(Button.builder(
                Component.translatable("unsuspiciousblock.config.reset"),
                button -> resetToDefaults())
                .bounds(buttonX, buttonY, BUTTON_WIDTH, 20)
                .build());
        buttonX += BUTTON_WIDTH + BUTTON_GAP;
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.cancel"),
                button -> this.onClose())
                .bounds(buttonX, buttonY, BUTTON_WIDTH, 20)
                .build());
        buttonX += BUTTON_WIDTH + BUTTON_GAP;
        this.addRenderableWidget(Button.builder(
                Component.translatable("unsuspiciousblock.config.save"),
                button -> saveAndClose())
                .bounds(buttonX, buttonY, BUTTON_WIDTH, 20)
                .build());
    }

    // 重置只更新页面内容，点击保存后才写入配置文件
    private void resetToDefaults() {
        for (int index = 0; index < fields.length; index++) {
            fields[index].setValue(String.valueOf(DEFAULT_VALUES[index]));
        }
    }

    // 保存全局配置并同步内存值，使后续生成的效果立即读取新参数
    private void saveAndClose() {
        if (Services.SPIRIT_CAT_CONFIG instanceof FabricLootTableConfig config) {
            config.saveSpiritCatConfig(
                    valueOrDefault(0), valueOrDefault(1), valueOrDefault(2),
                    valueOrDefault(3), valueOrDefault(4), valueOrDefault(5));
        }
        this.onClose();
    }

    private int valueOrDefault(int index) {
        try {
            return Integer.parseInt(fields[index].getValue());
        } catch (NumberFormatException exception) {
            return DEFAULT_VALUES[index];
        }
    }

    private static int rowY(int index) {
        return index < 3
                ? FIRST_ROW_Y + index * ROW_HEIGHT
                : SECOND_SECTION_Y + 12 + (index - 3) * ROW_HEIGHT;
    }

    @Override
    public void onClose() {
        Objects.requireNonNull(this.minecraft, "minecraft must not be null while screen is active").setScreen(parent);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int contentWidth = Math.min(CONTENT_WIDTH, this.width - 30);
        int leftX = (this.width - contentWidth) / 2;
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("unsuspiciousblock.config.common.notice"),
                this.width / 2, 27, 0xA0A0A0);
        drawSection(guiGraphics, leftX, 43, "unsuspiciousblock.config.spirit_cat.lifetimes",
                ISpiritCatConfig.MIN_NPC_LIFETIME_TICKS, ISpiritCatConfig.MAX_NPC_LIFETIME_TICKS);
        drawSection(guiGraphics, leftX, SECOND_SECTION_Y,
                "unsuspiciousblock.config.spirit_cat.nine_lives",
                ISpiritCatConfig.MIN_EFFECT_DURATION_TICKS, ISpiritCatConfig.MAX_EFFECT_DURATION_TICKS);

        for (int index = 0; index < FIELD_KEYS.length; index++) {
            guiGraphics.drawString(this.font,
                    Component.translatable("unsuspiciousblock.config.spirit_cat." + FIELD_KEYS[index]),
                    leftX, rowY(index) + 5, 0xE0E0E0, false);
        }
    }

    private void drawSection(GuiGraphics guiGraphics, int leftX, int y, String key, int min, int max) {
        guiGraphics.drawString(this.font, Component.translatable(key), leftX, y, 0xFFCC66, false);
        guiGraphics.drawString(this.font,
                Component.translatable("unsuspiciousblock.config.range_ticks", min, max),
                leftX + 120, y, 0x707070, false);
    }
}
