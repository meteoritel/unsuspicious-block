package com.meteorite.unsuspiciousblock.loottable.catalog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.platform.Services;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/***
 * 缺失本地化 key 导出器——同时作为客户端自定义战利品表语言文件的存储入口。
 * 文件使用标准语言 JSON 格式，位于 <code>config/unsuspiciousblock/lang/&lt;language&gt;.json</code>。
 */
public final class MissingTranslationKeyExporter {

    private static final String CONFIG_DIR_NAME = "config";
    private static final String MOD_DIR_NAME = "unsuspiciousblock";
    private static final String LANG_DIR_NAME = "lang";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();

    // 文件写入串行化，避免并发下文件损坏
    private static final Object WRITE_LOCK = new Object();
    private static volatile Supplier<String> clientLanguageSupplier;

    private MissingTranslationKeyExporter() {
    }

    // 客户端初始化时注册当前语言来源；专用服务端不注册，因此不会生成客户端语言文件
    public static void configureClient(Supplier<String> languageSupplier) {
        clientLanguageSupplier = languageSupplier;
    }

    // 记录当前客户端语言下的缺失 key；服务端环境仅记录日志，不执行文件写入
    static boolean record(String translationKey, String fallbackValue) {
        Supplier<String> languageSupplier = clientLanguageSupplier;
        if (languageSupplier == null) return true;
        String languageCode = languageSupplier.get();
        if (!isValidLanguageCode(languageCode)) return false;
        synchronized (WRITE_LOCK) {
            try {
                Path file = resolveLanguageFile(languageCode);
                Map<String, String> data = loadExisting(file);
                if (!data.containsKey(translationKey)) {
                    data.put(translationKey, fallbackValue);
                    save(file, data);
                }
                return true;
            } catch (IOException e) {
                Constants.LOG.warn("Failed to export missing translation key '{}' for language {}.",
                        translationKey, languageCode, e);
                return false;
            }
        }
    }

    // 合并服务端派发值；空字符串是显式删除标记。返回是否实际改变了文件内容
    public static boolean mergeLanguageValues(String languageCode, Map<String, String> values) {
        String normalizedLanguage = normalizeLanguageCode(languageCode);
        if (!isValidLanguageCode(normalizedLanguage) || values.isEmpty()) return false;
        synchronized (WRITE_LOCK) {
            try {
                Path file = resolveLanguageFile(normalizedLanguage);
                Map<String, String> data = loadExisting(file);
                boolean changed = false;
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    if (!LootTableNames.isGeneratedTranslationKey(entry.getKey()) || entry.getValue() == null) continue;
                    String value = entry.getValue().trim();
                    if (value.isEmpty()) {
                        changed |= data.remove(entry.getKey()) != null;
                    } else if (!value.equals(data.put(entry.getKey(), value))) {
                        changed = true;
                    }
                }
                if (changed) save(file, data);
                return changed;
            } catch (IOException e) {
                Constants.LOG.warn("Failed to merge loot table translations for language {}.",
                        normalizedLanguage, e);
                return false;
            }
        }
    }

    // 读取一个客户端语言文件，供语言加载 Mixin 合并到原版语言表
    public static Map<String, String> loadLanguageValues(String languageCode) {
        String normalizedLanguage = normalizeLanguageCode(languageCode);
        if (!isValidLanguageCode(normalizedLanguage)) return Map.of();
        synchronized (WRITE_LOCK) {
            Path file = resolveLanguageFilePath(normalizedLanguage);
            if (!Files.exists(file)) return Map.of();
            try {
                return Collections.unmodifiableMap(new LinkedHashMap<>(loadExisting(file)));
            } catch (IOException e) {
                Constants.LOG.warn("Failed to load custom loot table translations from {}.", file, e);
                return Map.of();
            }
        }
    }

    private static Path resolveLanguageFile(String languageCode) throws IOException {
        Path file = resolveLanguageFilePath(normalizeLanguageCode(languageCode));
        Path dir = file.getParent();
        Files.createDirectories(dir);
        return file;
    }

    private static Path resolveLanguageFilePath(String languageCode) {
        return Services.PLATFORM.getGameDir()
                .resolve(CONFIG_DIR_NAME)
                .resolve(MOD_DIR_NAME)
                .resolve(LANG_DIR_NAME)
                .resolve(languageCode + ".json");
    }

    // 读取已存在文件内容；损坏或不存在时返回空 LinkedHashMap 以保持插入顺序
    private static Map<String, String> loadExisting(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, String> loaded = GSON.fromJson(reader, MAP_TYPE);
            if (loaded != null) {
                // 用 LinkedHashMap 保持顺序，便于阅读与比对
                LinkedHashMap<String, String> filtered = new LinkedHashMap<>();
                loaded.forEach((key, value) -> {
                    if (LootTableNames.isGeneratedTranslationKey(key) && value != null) {
                        filtered.put(key, value);
                    }
                });
                return filtered;
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid language JSON: " + file, e);
        }
        return new LinkedHashMap<>();
    }

    private static void save(Path file, Map<String, String> data) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(data, writer);
        }
    }

    private static String normalizeLanguageCode(String languageCode) {
        return languageCode == null ? "" : languageCode.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isValidLanguageCode(String languageCode) {
        return languageCode != null && languageCode.matches("[a-z0-9_-]{2,16}");
    }
}
