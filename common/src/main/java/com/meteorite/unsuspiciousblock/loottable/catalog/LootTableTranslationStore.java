package com.meteorite.unsuspiciousblock.loottable.catalog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 服务端战利品表名称存储——按世界保存多语言名称并向客户端提供不可变快照。
 */
public final class LootTableTranslationStore {
    private static final String DIRECTORY = "serverconfig";
    private static final String FILE_NAME = "unsuspiciousblock-loot-table-names.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<Map<String, Map<String, String>>>() { }.getType();

    private static final Map<String, Map<String, String>> translations = new LinkedHashMap<>();
    private static Path activeFile;

    private LootTableTranslationStore() {
    }

    // 服务端启动或 reload 时重新读取当前世界名称文件
    public static synchronized void load(MinecraftServer server) {
        activeFile = server.getWorldPath(LevelResource.ROOT).resolve(DIRECTORY).resolve(FILE_NAME);
        translations.clear();
        if (!Files.exists(activeFile)) return;
        try (Reader reader = Files.newBufferedReader(activeFile)) {
            Map<String, Map<String, String>> loaded = GSON.fromJson(reader, DATA_TYPE);
            if (loaded != null) {
                loaded.forEach((language, values) -> translations.put(language, new LinkedHashMap<>(values)));
            }
        } catch (IOException exception) {
            Constants.LOG.warn("Failed to load loot table translations from {}.", activeFile, exception);
        }
    }

    public static synchronized void unload() {
        activeFile = null;
        translations.clear();
    }

    // 写入单个名称；空名称作为删除标记保留，以便向客户端同步清理旧值
    public static synchronized boolean put(String languageCode, String translationKey, String value) {
        if (activeFile == null || !languageCode.matches("[a-z0-9_-]{2,16}")) return false;
        String normalized = value.trim();
        Map<String, String> language = translations.computeIfAbsent(languageCode,
                ignored -> new LinkedHashMap<>());
        if (Objects.equals(language.get(translationKey), normalized)) return false;
        language.put(translationKey, normalized);
        return save();
    }

    public static synchronized Map<String, Map<String, String>> snapshot() {
        LinkedHashMap<String, Map<String, String>> result = new LinkedHashMap<>();
        translations.forEach((language, values) -> result.put(language, Map.copyOf(values)));
        return Map.copyOf(result);
    }

    // 判断指定语言是否已有非空名称，供服务端校验多语言编辑顺序
    public static synchronized boolean hasNonBlank(String languageCode, String translationKey) {
        Map<String, String> language = translations.get(languageCode);
        return language != null && language.getOrDefault(translationKey, "").trim().length() > 0;
    }

    private static boolean save() {
        try {
            Files.createDirectories(activeFile.getParent());
            try (Writer writer = Files.newBufferedWriter(activeFile)) {
                GSON.toJson(translations, DATA_TYPE, writer);
            }
            return true;
        } catch (IOException exception) {
            Constants.LOG.warn("Failed to save loot table translations to {}.", activeFile, exception);
            return false;
        }
    }
}
