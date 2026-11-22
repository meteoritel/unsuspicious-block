package com.meteorite.unsuspiciousblock.core.loottable.catalog;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingConfigFile;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNameEdit.Result;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNameEdit.Status;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 实例级补充表名。磁盘按语言保存完整表 ID，并保留无法归属的旧键。 */
public final class LootTableTranslationStore {
    private static final String LANGUAGE_DIRECTORY = "loot_table_lang";
    private static final String LEGACY_FILE_NAME = "unsuspiciousblock-loot-table-names.json";
    private static final String MIGRATION_DIRECTORY = ".migrations";
    private static final String MIGRATION_MARKER_PREFIX = "legacy-translations-";
    private static final String MIGRATION_MARKER_SUFFIX = ".migrated";
    private static final Gson GSON = new Gson();
    private static final Type LEGACY_TYPE = new TypeToken<Map<String, Map<String, String>>>() { }.getType();

    private static final Map<String, Map<String, String>> translations = new LinkedHashMap<>();
    private static final Set<String> unreadableLanguages = new HashSet<>();
    private static Map<String, String> uniqueLegacyIds = Map.of();
    private static Path activeDirectory;

    private LootTableTranslationStore() {}

    /** 加载实例名称和旧世界数据，归属使用当前注册表中的完整表 ID。 */
    public static synchronized void load(MinecraftServer server) {
        activeDirectory = Services.PLATFORM.getGameDir().resolve("config").resolve(Constants.MOD_ID)
                .resolve(LANGUAGE_DIRECTORY);
        uniqueLegacyIds = findUniqueLegacyIds(server);
        loadLanguageDirectory();
        migrateLegacyWorldFile(server.getWorldPath(LevelResource.ROOT).resolve("serverconfig").resolve(LEGACY_FILE_NAME));
    }

    private static Map<String, String> findUniqueLegacyIds(MinecraftServer server) {
        Map<String, String> names = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (ResourceLocation id : server.reloadableRegistries().getKeys(Registries.LOOT_TABLE)) {
            String key = LootTableNames.createTranslationKey(id);
            if (names.putIfAbsent(key, id.toString()) != null) ambiguous.add(key);
        }
        ambiguous.forEach(names::remove);
        return Map.copyOf(names);
    }

    /** 释放服务器实例快照。 */
    public static synchronized void unload() {
        activeDirectory = null;
        translations.clear();
        unreadableLanguages.clear();
        uniqueLegacyIds = Map.of();
    }

    /** 每种语言以一次原子文件替换提交，逐项核对预期的服务端现值。 */
    public static synchronized List<Result> save(List<LootTableNameEdit> edits) {
        Map<String, List<LootTableNameEdit>> byLanguage = new LinkedHashMap<>();
        edits.forEach(edit -> byLanguage.computeIfAbsent(edit.language(), ignored -> new ArrayList<>()).add(edit));
        List<Result> results = new ArrayList<>();
        byLanguage.forEach((language, changes) -> saveLanguageEdits(language, changes, results));
        return List.copyOf(results);
    }

    private static void saveLanguageEdits(String language, List<LootTableNameEdit> edits, List<Result> results) {
        Map<String, String> before = translations.getOrDefault(language, Map.of());
        Map<String, String> next = canonicalNames(before);
        List<LootTableNameEdit> changed = new ArrayList<>();
        for (LootTableNameEdit edit : edits) {
            String id = edit.tableId().toString();
            String current = next.getOrDefault(id, "");
            boolean resolvingLegacy = !edit.legacyKey().isEmpty();
            if (resolvingLegacy && !edit.value().equals(next.get(edit.legacyKey()))) {
                results.add(result(edit, Status.INVALID, current));
                continue;
            }
            if (edit.value().equals(current) && !resolvingLegacy) {
                results.add(result(edit, Status.UNCHANGED, current));
            } else if (!edit.expected().equals(current)) {
                results.add(result(edit, Status.CONFLICT, current));
            } else {
                changed.add(edit);
                if (resolvingLegacy) next.remove(edit.legacyKey());
                if (edit.value().isEmpty()) next.remove(id);
                else next.put(id, edit.value());
            }
        }
        if (changed.isEmpty()) return;
        boolean saved = !unreadableLanguages.contains(language) && saveLanguage(language, next);
        if (saved) translations.put(language, Map.copyOf(next));
        Map<String, String> finalValues = saved ? next : canonicalNames(before);
        for (LootTableNameEdit edit : changed) {
            results.add(result(edit, saved ? Status.SAVED : Status.WRITE_FAILED,
                    finalValues.getOrDefault(edit.tableId().toString(), "")));
        }
    }

    private static Result result(LootTableNameEdit edit, Status status, String value) {
        return new Result(edit.tableId(), edit.language(), status, value);
    }

    /** 已解析为完整表 ID 的补充名称供客户端展示和编辑。 */
    public static synchronized Map<String, Map<String, String>> snapshot() {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        translations.forEach((language, values) -> {
            Map<String, String> names = canonicalNames(values);
            names.keySet().removeIf(LootTableNames::isGeneratedTranslationKey);
            result.put(language, Map.copyOf(names));
        });
        return Map.copyOf(result);
    }

    /** 列出尚未归属的旧键，留给整合包作者用完整 ID 指定归属。 */
    public static synchronized Map<String, Map<String, String>> unresolvedLegacyNames() {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        translations.forEach((language, values) -> {
            Map<String, String> unresolved = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                if (LootTableNames.isGeneratedTranslationKey(key) && !uniqueLegacyIds.containsKey(key)) {
                    unresolved.put(key, value);
                }
            });
            if (!unresolved.isEmpty()) result.put(language, Map.copyOf(unresolved));
        });
        return Map.copyOf(result);
    }

    private static Map<String, String> canonicalNames(Map<String, String> stored) {
        Map<String, String> names = new LinkedHashMap<>(stored);
        stored.forEach((key, value) -> {
            String id = uniqueLegacyIds.get(key);
            if (id != null) {
                names.remove(key);
                names.putIfAbsent(id, value);
            }
        });
        return names;
    }

    private static void loadLanguageDirectory() {
        Path directory = activeDirectory;
        if (Files.notExists(directory)) {
            translations.clear();
            unreadableLanguages.clear();
            return;
        }
        Set<String> found = new HashSet<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                String fileName = file.getFileName().toString();
                String language = fileName.substring(0, fileName.length() - ".json".length());
                if (!LootTableNameEdit.isLanguage(language)) continue;
                found.add(language);
                try {
                    translations.put(language, readNames(LootTrackingConfigFile.readObject(file)));
                    unreadableLanguages.remove(language);
                } catch (IOException | RuntimeException exception) {
                    unreadableLanguages.add(language);
                    Constants.LOG.warn("{}读取战利品表名称失败 {}", Constants.LOG_TAG, file, exception);
                }
            }
            translations.keySet().retainAll(found);
            unreadableLanguages.retainAll(found);
        } catch (IOException exception) {
            Constants.LOG.warn("{}读取表名目录失败 {}", Constants.LOG_TAG, directory, exception);
        }
    }

    private static Map<String, String> readNames(JsonObject json) {
        Map<String, String> names = new LinkedHashMap<>();
        json.entrySet().forEach(entry -> {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(entry.getKey());
            }
            String value = entry.getValue().getAsString().trim();
            if (value.isEmpty() || !isNameKey(entry.getKey())) return;
            if (!LootTableNameEdit.isName(value)) throw new IllegalArgumentException(entry.getKey());
            names.put(entry.getKey(), value);
        });
        return Map.copyOf(names);
    }

    private static boolean isNameKey(String key) {
        ResourceLocation id = ResourceLocation.tryParse(key);
        return LootTableNames.isGeneratedTranslationKey(key) || id != null
                && !id.getPath().isEmpty() && key.equals(id.toString());
    }

    private static void migrateLegacyWorldFile(Path legacyFile) {
        if (!Files.isRegularFile(legacyFile)) return;
        try {
            String source = Files.readString(legacyFile);
            String sourceId = migrationSourceId(source);
            if (Files.exists(migrationMarker(sourceId, ""))) return;
            Map<String, Map<String, String>> old = GSON.fromJson(source, LEGACY_TYPE);
            if (old == null) return;

            boolean complete = true;
            for (var entry : old.entrySet()) {
                String language = entry.getKey();
                if (!LootTableNameEdit.isLanguage(language) || entry.getValue() == null) continue;
                Path languageMarker = migrationMarker(sourceId, "-" + language);
                if (Files.exists(languageMarker)) continue;
                if (unreadableLanguages.contains(language)) {
                    complete = false;
                    continue;
                }
                Map<String, String> current = translations.getOrDefault(language, Map.of());
                Map<String, String> next = new LinkedHashMap<>(current);
                entry.getValue().forEach((key, value) -> {
                    if (isNameKey(key) && value != null && !value.isBlank()) {
                        String name = value.trim();
                        if (!LootTableNameEdit.isName(name)) throw new IllegalArgumentException(key);
                        next.putIfAbsent(key, name);
                    }
                });
                Map<String, String> canonical = canonicalNames(next);
                if (!canonical.equals(current) && !saveLanguage(language, canonical)) {
                    complete = false;
                    continue;
                }
                translations.put(language, Map.copyOf(canonical));
                complete &= markLegacyMigrationComplete(languageMarker);
            }
            if (complete) markLegacyMigrationComplete(migrationMarker(sourceId, ""));
        } catch (IOException | NoSuchAlgorithmException | RuntimeException exception) {
            Constants.LOG.warn("{}迁移旧战利品表名称失败 {}", Constants.LOG_TAG, legacyFile, exception);
        }
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

    private static boolean saveLanguage(String language, Map<String, String> names) {
        Path file = activeDirectory.resolve(language + ".json");
        JsonObject json = new JsonObject();
        names.keySet().stream().sorted().forEach(key -> json.addProperty(key, names.get(key)));
        try {
            LootTrackingConfigFile.writeObject(file, json);
            return true;
        } catch (IOException exception) {
            Constants.LOG.warn("{}保存战利品表名称失败 {}", Constants.LOG_TAG, file, exception);
            return false;
        }
    }
}
