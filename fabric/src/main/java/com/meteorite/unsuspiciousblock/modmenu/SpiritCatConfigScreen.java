package com.meteorite.unsuspiciousblock.modmenu;

import com.meteorite.meteosuikit.api.control.UiTextInput;
import com.meteorite.unsuspiciousblock.client.ui.screen.ConfigFormScreen;
import com.meteorite.unsuspiciousblock.platform.FabricLootTableConfig;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.platform.services.ISpiritCatConfig;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Fabric 灵体猫全局配置草稿；范围与保存规则由现有配置契约提供。 */
public final class SpiritCatConfigScreen extends ConfigFormScreen {
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

    private final UiTextInput[] fields = new UiTextInput[FIELD_KEYS.length];

    public SpiritCatConfigScreen(Screen parent) {
        super(parent, Component.translatable("unsuspiciousblock.config.common.title"));
        ISpiritCatConfig config = Services.SPIRIT_CAT_CONFIG;
        int[] values = {config.getMessengerLifetimeTicks(), config.getSwordsmanLifetimeTicks(),
                config.getMerchantLifetimeTicks(), config.getInvulnerabilityDurationTicks(),
                config.getResistanceDurationTicks(), config.getFireResistanceDurationTicks()};
        for (int i = 0; i < fields.length; i++) {
            fields[i] = addTextField(Component.translatable("unsuspiciousblock.config.spirit_cat." + FIELD_KEYS[i]),
                    Integer.toString(values[i])).setMaxLength(7)
                    .setFilter(text -> text.chars().allMatch(c -> c >= '0' && c <= '9'));
        }
    }

    @Override protected void resetDraft() {
        for (int i = 0; i < fields.length; i++) fields[i].setValue(Integer.toString(DEFAULT_VALUES[i]));
    }

    @Override protected boolean saveDraft() {
        int[] values = new int[fields.length];
        for (int i = 0; i < fields.length; i++) {
            int minimum = i < 3 ? ISpiritCatConfig.MIN_NPC_LIFETIME_TICKS : ISpiritCatConfig.MIN_EFFECT_DURATION_TICKS;
            int maximum = i < 3 ? ISpiritCatConfig.MAX_NPC_LIFETIME_TICKS : ISpiritCatConfig.MAX_EFFECT_DURATION_TICKS;
            try {
                values[i] = fields[i].value().isEmpty() ? DEFAULT_VALUES[i] : Integer.parseInt(fields[i].value());
                if (values[i] < minimum || values[i] > maximum) throw new NumberFormatException();
            } catch (NumberFormatException exception) {
                showValidation(Component.translatable("unsuspiciousblock.config.invalid_range", minimum, maximum));
                return false;
            }
        }
        ((FabricLootTableConfig) Services.SPIRIT_CAT_CONFIG).saveSpiritCatConfig(
                values[0], values[1], values[2], values[3], values[4], values[5]);
        return true;
    }
    @Override protected Component notice() { return Component.translatable("unsuspiciousblock.config.common.notice"); }
}
