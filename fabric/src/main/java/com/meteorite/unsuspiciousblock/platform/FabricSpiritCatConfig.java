package com.meteorite.unsuspiciousblock.platform;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingConfigFile;
import com.meteorite.unsuspiciousblock.platform.services.ISpiritCatConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/** Fabric 灵体猫全局设置，保存时保留同一旧文件中的世界迁移输入。 */
public final class FabricSpiritCatConfig implements ISpiritCatConfig {
    private static final String MESSENGER = "messenger_lifetime_ticks";
    private static final String SWORDSMAN = "swordsman_lifetime_ticks";
    private static final String MERCHANT = "merchant_lifetime_ticks";
    private static final String INVULNERABILITY = "invulnerability_duration_ticks";
    private static final String RESISTANCE = "resistance_duration_ticks";
    private static final String FIRE_RESISTANCE = "fire_resistance_duration_ticks";

    private volatile Values values;

    public FabricSpiritCatConfig() {
        Path file = globalFile();
        try {
            values = Values.read(LootTrackingConfigFile.readObject(file));
        } catch (NoSuchFileException exception) {
            Values defaults = Values.read(new JsonObject());
            values = defaults;
            saveSpiritCatConfig(defaults.messenger(), defaults.swordsman(), defaults.merchant(),
                    defaults.invulnerability(), defaults.resistance(), defaults.fireResistance());
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            Constants.LOG.warn("{}Cannot read spirit cat config {}", Constants.LOG_TAG, file, exception);
            values = Values.read(new JsonObject());
        }
    }

    static Path globalFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(Constants.MOD_ID).resolve("unsuspiciousblock.json");
    }

    @Override public int getMessengerLifetimeTicks() { return values.messenger(); }
    @Override public int getSwordsmanLifetimeTicks() { return values.swordsman(); }
    @Override public int getMerchantLifetimeTicks() { return values.merchant(); }
    @Override public int getInvulnerabilityDurationTicks() { return values.invulnerability(); }
    @Override public int getResistanceDurationTicks() { return values.resistance(); }
    @Override public int getFireResistanceDurationTicks() { return values.fireResistance(); }

    /** 只更新灵体猫字段，读取或提交失败时保留已生效的值。 */
    public synchronized boolean saveSpiritCatConfig(int messenger, int swordsman, int merchant,
                                                    int invulnerability, int resistance, int fireResistance) {
        Path file = globalFile();
        try {
            JsonObject json = readGlobal(file);
            json.addProperty(MESSENGER, messenger);
            json.addProperty(SWORDSMAN, swordsman);
            json.addProperty(MERCHANT, merchant);
            json.addProperty(INVULNERABILITY, invulnerability);
            json.addProperty(RESISTANCE, resistance);
            json.addProperty(FIRE_RESISTANCE, fireResistance);
            LootTrackingConfigFile.writeObject(file, json);
            values = Values.read(json);
            return true;
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            Constants.LOG.warn("{}Cannot save spirit cat config {}", Constants.LOG_TAG, file, exception);
            return false;
        }
    }

    private static JsonObject readGlobal(Path file) throws IOException {
        try {
            return LootTrackingConfigFile.readObject(file);
        } catch (NoSuchFileException exception) {
            return new JsonObject();
        }
    }

    private record Values(int messenger, int swordsman, int merchant, int invulnerability, int resistance,
                          int fireResistance) {
        private static Values read(JsonObject json) {
            return new Values(number(json, MESSENGER, DEFAULT_MESSENGER_LIFETIME_TICKS,
                            MIN_NPC_LIFETIME_TICKS, MAX_NPC_LIFETIME_TICKS),
                    number(json, SWORDSMAN, DEFAULT_SWORDSMAN_LIFETIME_TICKS,
                            MIN_NPC_LIFETIME_TICKS, MAX_NPC_LIFETIME_TICKS),
                    number(json, MERCHANT, DEFAULT_MERCHANT_LIFETIME_TICKS,
                            MIN_NPC_LIFETIME_TICKS, MAX_NPC_LIFETIME_TICKS),
                    number(json, INVULNERABILITY, DEFAULT_INVULNERABILITY_DURATION_TICKS,
                            MIN_EFFECT_DURATION_TICKS, MAX_EFFECT_DURATION_TICKS),
                    number(json, RESISTANCE, DEFAULT_RESISTANCE_DURATION_TICKS,
                            MIN_EFFECT_DURATION_TICKS, MAX_EFFECT_DURATION_TICKS),
                    number(json, FIRE_RESISTANCE, DEFAULT_FIRE_RESISTANCE_DURATION_TICKS,
                            MIN_EFFECT_DURATION_TICKS, MAX_EFFECT_DURATION_TICKS));
        }

        private static int number(JsonObject json, String key, int fallback, int minimum, int maximum) {
            if (!json.has(key) || !json.get(key).isJsonPrimitive()
                    || !json.getAsJsonPrimitive(key).isNumber()) return fallback;
            try {
                int value = json.getAsJsonPrimitive(key).getAsBigDecimal().intValueExact();
                if (value >= minimum && value <= maximum) return value;
            } catch (ArithmeticException exception) {
                Constants.LOG.warn("{}Invalid spirit cat config {}: {}", Constants.LOG_TAG, key, json.get(key));
            }
            return fallback;
        }
    }
}
