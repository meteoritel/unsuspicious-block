package com.meteorite.unsuspiciousblock.platform.services;

/**
 * 猫国灵体服务端配置，由各平台配置系统提供六种可调整时长。
 */
public interface ISpiritCatConfig {
    int MIN_NPC_LIFETIME_TICKS = 20;
    int MAX_NPC_LIFETIME_TICKS = 1_728_000;
    int MIN_EFFECT_DURATION_TICKS = 1;
    int MAX_EFFECT_DURATION_TICKS = 72_000;

    int DEFAULT_MESSENGER_LIFETIME_TICKS = 600;
    int DEFAULT_SWORDSMAN_LIFETIME_TICKS = 600;
    int DEFAULT_MERCHANT_LIFETIME_TICKS = 48_000;
    int DEFAULT_INVULNERABILITY_DURATION_TICKS = 40;
    int DEFAULT_RESISTANCE_DURATION_TICKS = 600;
    int DEFAULT_FIRE_RESISTANCE_DURATION_TICKS = 600;

    int getMessengerLifetimeTicks();

    int getSwordsmanLifetimeTicks();

    int getMerchantLifetimeTicks();

    int getInvulnerabilityDurationTicks();

    int getResistanceDurationTicks();

    int getFireResistanceDurationTicks();
}
