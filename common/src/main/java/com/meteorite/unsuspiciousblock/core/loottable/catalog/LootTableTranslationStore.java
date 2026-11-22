package com.meteorite.unsuspiciousblock.core.loottable.catalog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 服务端战利品表名称存储——以标准语言 JSON 保存整合包级补充翻译，并向客户端提供权威快照。
 */
public final class LootTableTranslationStore {
    private static final String CONFIG_DIRECTORY = "config";
    private static final String MOD_DIRECTORY = "unsuspiciousblock";
    private static final String LANGUAGE_DIRECTORY = "loot_table_lang";
    private static final String LEGACY_DIRECTORY = "serverconfig";
    private static final String LEGACY_FILE_NAME = "unsuspiciousblock-loot-table-names.json";
    private static final String MIGRATION_DIRECTORY = ".migrations";
    private static final String MIGRATION_MARKER_PREFIX = "legacy-translations-";
    private static final String MIGRATION_MARKER_SUFFIX = ".migrated";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LANGUAGE_TYPE = new TypeToken<Map<String, String>>() { }.getType();
    private static final Type LEGACY_TYPE = new TypeToken<Map<String, Map<String, String>>>() { }.getType();

    private static final Map<String, Map<String, String>> translations = new LinkedHashMap<>();
    private static Path activeDirectory;

    private LootTableTranslationStore() {
    }

    // 服务端启动或 reload 时读取整合包级语言目录，并兼容迁移旧世界文件
    public static synchronized void load(MinecraftServer server) {
        activeDirectory = Services.PLATFORM.getGameDir()
                .resolve(CONFIG_DIRECTORY).resolve(MOD_DIRECTORY).resolve(LANGUAGE_DIRECTORY);
        translations.clear();
        loadLanguageDirectory();
        migrateLegacyWorldFile(server.getWorldPath(LevelResource.ROOT)
                .resolve(LEGACY_DIRECTORY).resolve(LEGACY_FILE_NAME));
    }

    public static synchronized void unload() {
        activeDirectory = null;
        translations.clear();
    }

    /** 保存已提交的语言变更；至少一种语言成功落盘时返回 true。 */
    public static synchronized boolean putAll(Map<String, Map<String, String>> updates) {
        if (activeDirectory == null || updates.isEmpty()) return false;
        Map<String, Map<String, String>> changedLanguages = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> languageEntry : updates.entrySet()) {
            String languageCode = languageEntry.getKey();
            if (!isValidLanguageCode(languageCode)) continue;
            Map<String, String> language = new LinkedHashMap<>(translations.getOrDefault(languageCode, Map.of()));
            boolean changed = false;
            for (Map.Entry<String, String> valueEntry : languageEntry.getValue().entrySet()) {
                if (!LootTableNames.isGeneratedTranslationKey(valueEntry.getKey())) continue;
                String normalized = valueEntry.getValue().trim();
                if (normalized.isEmpty()) {
                    changed |= language.remove(valueEntry.getKey()) != null;
                } else if (!Objects.equals(language.put(valueEntry.getKey(), normalized), normalized)) {
                    changed = true;
                }
            }
            if (changed) changedLanguages.put(languageCode, language);
        }
        if (changedLanguages.isEmpty()) return false;
        boolean anyLanguageSaved = false;
        for (Map.Entry<String, Map<String, String>> entry : changedLanguages.entrySet()) {
            if (saveLanguage(entry.getKey(), entry.getValue())) {
                translations.put(entry.getKey(), entry.getValue());
                anyLanguageSaved = true;
            }
        }
        return anyLanguageSaved;
    }

    public static synchronized Map<String, Map<String, String>> snapshot() {
        LinkedHashMap<String, Map<String, String>> result = new LinkedHashMap<>();
        translations.forEach((language, values) -> result.put(language, Map.copyOf(values)));
        return Map.copyOf(result);
    }

    private static void loadLanguageDirectory() {
        if (activeDirectory == null || !Files.isDirectory(activeDirectory)) return;
        try (var files = Files.list(activeDirectory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(LootTableTranslationStore::loadLanguageFile);
        } catch (IOException exception) {
            Constants.LOG.warn("Failed to list loot table language directory {}.", activeDirectory, exception);
        }
    }

    private static void loadLanguageFile(Path file) {
        String fileName = file.getFileName().toString();
        String languageCode = fileName.substring(0, fileName.length() - ".json".length());
        if (!isValidLanguageCode(languageCode)) {
            Constants.LOG.warn("Ignoring loot table language file with invalid language code: {}.", file);
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, String> loaded = GSON.fromJson(reader, LANGUAGE_TYPE);
            if (loaded == null) return;
            Map<String, String> language = new LinkedHashMap<>();
            loaded.forEach((key, value) -> {
                if (LootTableNames.isGeneratedTranslationKey(key) && value != null && !value.isBlank()) {
                    language.put(key, value.trim());
                }
            });
            translations.put(languageCode, language);
        } catch (IOException | RuntimeException exception) {
            Constants.LOG.warn("Failed to load loot table language file {}.", file, exception);
        }
    }

    private static void migrateLegacyWorldFile(Path legacyFile) {
        if (!Files.isRegularFile(legacyFile)) return;
        try {
            String legacyContent = Files.readString(legacyFile);
            String sourceId = migrationSourceId(legacyContent);
            if (Files.exists(migrationMarker(sourceId, ""))) return;
            Map<String, Map<String, String>> legacy;
            try (Reader reader = new StringReader(legacyContent)) {
                legacy = GSON.fromJson(reader, LEGACY_TYPE);
            }
            if (legacy == null) return;
            boolean complete = true;
            boolean changed = false;
            for (Map.Entry<String, Map<String, String>> languageEntry : legacy.entrySet()) {
                String languageCode = languageEntry.getKey();
                Map<String, String> values = languageEntry.getValue();
                if (!isValidLanguageCode(languageCode) || values == null) continue;
                Path languageMarker = migrationMarker(sourceId, "-" + languageCode);
                if (Files.exists(languageMarker)) continue;
                Map<String, String> current = translations.getOrDefault(languageCode, Map.of());
                Map<String, String> missing = new LinkedHashMap<>();
                values.forEach((key, value) -> {
                    if (LootTableNames.isGeneratedTranslationKey(key) && value != null
                            && !value.isBlank() && !current.containsKey(key)) {
                        missing.put(key, value.trim());
                    }
                });
                Map<String, Map<String, String>> languageUpdate = Map.of(languageCode, missing);
                changed |= putAll(languageUpdate);
                if (!containsAll(languageUpdate) || !markLegacyMigrationComplete(languageMarker)) {
                    complete = false;
                }
            }
            if (complete && markLegacyMigrationComplete(migrationMarker(sourceId, "")) && changed) {
                Constants.LOG.info("Migrated legacy loot table translations from {} to {}.",
                        legacyFile, activeDirectory);
            }
        } catch (IOException | NoSuchAlgorithmException | RuntimeException exception) {
            Constants.LOG.warn("Failed to migrate legacy loot table translations from {}.", legacyFile, exception);
        }
    }

    private static boolean containsAll(Map<String, Map<String, String>> expected) {
        for (Map.Entry<String, Map<String, String>> languageEntry : expected.entrySet()) {
            Map<String, String> current = translations.getOrDefault(languageEntry.getKey(), Map.of());
            for (Map.Entry<String, String> value : languageEntry.getValue().entrySet()) {
                if (!Objects.equals(current.get(value.getKey()), value.getValue())) return false;
            }
        }
        return true;
    }

    private static boolean markLegacyMigrationComplete(Path marker) {
        try {
            Files.createDirectories(marker.getParent());
            Files.createFile(marker);
            return true;
        } catch (FileAlreadyExistsException exception) {
            return true;
        } catch (IOException exception) {
            Constants.LOG.warn("Failed to mark legacy loot table translations as migrated: {}.", marker, exception);
            return false;
        }
    }

    private static String migrationSourceId(String legacyContent) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(legacyContent.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static Path migrationMarker(String sourceId, String languageSuffix) {
        return activeDirectory.resolve(MIGRATION_DIRECTORY)
                .resolve(MIGRATION_MARKER_PREFIX + sourceId + languageSuffix + MIGRATION_MARKER_SUFFIX);
    }

    private static boolean saveLanguage(String languageCode, Map<String, String> language) {
        Path temporary = null;
        try {
            Files.createDirectories(activeDirectory);
            Path file = activeDirectory.resolve(languageCode + ".json");
            temporary = Files.createTempFile(activeDirectory, languageCode, ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                GSON.toJson(language, LANGUAGE_TYPE, writer);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            Constants.LOG.warn("Failed to save loot table language {} to {}.",
                    languageCode, activeDirectory, exception);
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    Constants.LOG.warn("Failed to clean temporary loot table language file {}.", temporary,
                            exception);
                }
            }
        }
    }

    private static boolean isValidLanguageCode(String languageCode) {
        return languageCode != null && languageCode.matches("[a-z0-9_-]{2,16}");
    }
}
