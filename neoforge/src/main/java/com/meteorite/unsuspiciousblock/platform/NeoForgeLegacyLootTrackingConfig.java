package com.meteorite.unsuspiciousblock.platform;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.meteorite.unsuspiciousblock.core.loottable.LegacyLootTrackingConfig;
import com.meteorite.unsuspiciousblock.platform.services.ILegacyLootTrackingConfig;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

/** 按 FML 的 SERVER 文件优先级导入旧 TOML。 */
public final class NeoForgeLegacyLootTrackingConfig implements ILegacyLootTrackingConfig {
    private static final String SERVER_FILE = "unsuspiciousblock-server.toml";
    private static final int LEGACY_LOG_LIMIT = 512;
    private static final long LEGACY_TIMEOUT_TICKS = 6000L;

    @Override
    public LegacyLootTrackingConfig readLegacy(Path worldConfigDirectory) throws IOException {
        LegacyLootTrackingConfig world = readServer(worldConfigDirectory.resolve(SERVER_FILE));
        if (world != null) return world;
        LegacyLootTrackingConfig instance = readServer(FMLPaths.CONFIGDIR.get().resolve(SERVER_FILE));
        if (instance != null) return instance;
        LegacyLootTrackingConfig template = readServer(
                FMLPaths.GAMEDIR.get().resolve(FMLConfig.defaultConfigPath()).resolve(SERVER_FILE));
        if (template != null) return template;
        return NeoForgeSpiritCatConfig.legacyTrackingDefaults();
    }

    private static @Nullable LegacyLootTrackingConfig readServer(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            UnmodifiableConfig config = new TomlParser().parse(reader);
            Object marker = config.get("migrated_from_common_config");
            if (marker != null && !(marker instanceof Boolean)) {
                throw new IllegalArgumentException("migrated_from_common_config");
            }
            List<String> selectors = readList(config, "loot_table.archaeology_path_prefixes");
            List<String> exclusions = readList(config, "excluded_loot_tables");
            Integer logLimit = readNumber(config, "journal.max_log_entries_per_table", Integer.class);
            Long timeout = readNumber(config, "journal.tracking_timeout_ticks", Long.class);
            if (selectors == null || logLimit == null || timeout == null) {
                LegacyLootTrackingConfig fallback = Boolean.TRUE.equals(marker)
                        ? new LegacyLootTrackingConfig(null, List.of(), null, LEGACY_LOG_LIMIT, LEGACY_TIMEOUT_TICKS)
                        : NeoForgeSpiritCatConfig.legacyTrackingDefaults();
                if (selectors == null) selectors = fallback.selectors();
                if (logLimit == null) logLimit = fallback.logLimit();
                if (timeout == null) timeout = fallback.timeoutTicks();
            }
            return new LegacyLootTrackingConfig(selectors, exclusions == null ? List.of() : exclusions,
                    readList(config, "loot_table.signature_excluded_components"), logLimit, timeout);
        } catch (NoSuchFileException exception) {
            return null;
        } catch (IOException | RuntimeException exception) {
            throw new IOException("Invalid legacy loot config " + path, exception);
        }
    }

    private static @Nullable List<String> readList(UnmodifiableConfig config, String key) {
        Object value = config.get(key);
        if (value == null) return null;
        if (!(value instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
            throw new IllegalArgumentException(key);
        }
        return list.stream().map(String.class::cast).toList();
    }

    private static <T extends Number> @Nullable T readNumber(UnmodifiableConfig config, String key, Class<T> type) {
        Object value = config.get(key);
        if (value == null) return null;
        if (type.isInstance(value)) return type.cast(value);
        if (type == Long.class && value instanceof Integer number) return type.cast(Long.valueOf(number));
        throw new IllegalArgumentException(key);
    }
}
