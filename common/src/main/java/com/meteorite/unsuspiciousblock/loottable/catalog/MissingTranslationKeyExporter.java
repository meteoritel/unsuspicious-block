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
import java.util.function.Function;
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
    // 客户端资源语言文件读取函数（语言代码 -> 该语言资源文件内容）；专用服务端保持空实现
    private static volatile Function<String, Map<String, String>> resourceLanguageLookup = code -> Map.of();

    // 待落盘的缺失 key（按语言分组）。record 只入内存，由 flushPending 批量写盘，
    // 避免 reload 期间在主线程对每个 key 做一次整读 + 整写文件 I/O
    private static final Map<String, Map<String, String>> pendingRecords = new LinkedHashMap<>();
    // 累积条数达到阈值时立即落盘，防止长时间无 flush 触发导致 pending 无限增长
    private static final int FLUSH_THRESHOLD = 32;

    private MissingTranslationKeyExporter() {
    }

    // 客户端初始化时注册当前语言来源与资源语言文件读取函数；专用服务端不注册，因此不会生成客户端语言文件
    public static void configureClient(Supplier<String> languageSupplier,
                                       Function<String, Map<String, String>> resourceLanguageReader) {
        clientLanguageSupplier = languageSupplier;
        resourceLanguageLookup = resourceLanguageReader;
    }

    /** 语言快照--汇总缺失 key 时一次性读取的当前语言与 en_us 对照数据 */
    public record LanguageSnapshot(String currentLanguageCode,
                                   Map<String, String> currentResource,
                                   Map<String, String> currentOverrides,
                                   Map<String, String> enUsResource) {
    }

    // 一次性捕获语言快照，供缺失 key 汇总分类使用；无客户端语言环境时各 map 为空
    public static LanguageSnapshot captureLanguageSnapshot() {
        synchronized (WRITE_LOCK) {
            Supplier<String> supplier = clientLanguageSupplier;
            String languageCode = supplier != null ? normalizeLanguageCode(supplier.get()) : null;
            if (languageCode == null || !isValidLanguageCode(languageCode)) {
                return new LanguageSnapshot(null, Map.of(), Map.of(), Map.of());
            }
            Map<String, String> currentResource = safeResourceLookup(languageCode);
            Map<String, String> enUsResource = safeResourceLookup("en_us");
            Map<String, String> currentOverrides;
            try {
                currentOverrides = loadExisting(resolveLanguageFilePath(languageCode));
            } catch (IOException e) {
                Constants.LOG.warn("Failed to load custom overrides for language {}.", languageCode, e);
                currentOverrides = new LinkedHashMap<>();
            }
            return new LanguageSnapshot(languageCode, currentResource, currentOverrides, enUsResource);
        }
    }

    private static Map<String, String> safeResourceLookup(String languageCode) {
        try {
            return Map.copyOf(resourceLanguageLookup.apply(languageCode));
        } catch (RuntimeException e) {
            Constants.LOG.warn("Failed to read resource language file {}.", languageCode, e);
            return Map.of();
        }
    }

    // 记录当前客户端语言下的缺失 key；仅累积到内存，等待 flushPending 批量落盘。
    // 服务端环境不落盘（无客户端语言来源），返回 true 表示无需调用方重试
    static boolean record(String translationKey, String fallbackValue) {
        Supplier<String> languageSupplier = clientLanguageSupplier;
        if (languageSupplier == null) return true;
        String languageCode = languageSupplier.get();
        if (!isValidLanguageCode(languageCode)) return false;
        synchronized (WRITE_LOCK) {
            pendingRecords.computeIfAbsent(languageCode, ignored -> new LinkedHashMap<>())
                    .putIfAbsent(translationKey, fallbackValue);
            if (countPendingLocked() >= FLUSH_THRESHOLD) {
                flushLocked();
            }
        }
        return true;
    }

    // 将累积的缺失 key 一次性合并落盘；批处理边界（目录加载完成、调试命令）调用
    public static void flushPending() {
        synchronized (WRITE_LOCK) {
            flushLocked();
        }
    }

    private static void flushLocked() {
        for (Map.Entry<String, Map<String, String>> entry : pendingRecords.entrySet()) {
            String languageCode = entry.getKey();
            Map<String, String> pending = entry.getValue();
            if (pending.isEmpty()) continue;
            try {
                Path file = resolveLanguageFile(languageCode);
                Map<String, String> data = loadExisting(file);
                boolean changed = false;
                for (Map.Entry<String, String> pendingEntry : pending.entrySet()) {
                    if (!data.containsKey(pendingEntry.getKey())) {
                        data.put(pendingEntry.getKey(), pendingEntry.getValue());
                        changed = true;
                    }
                }
                if (changed) save(file, data);
                pending.clear();
            } catch (IOException e) {
                // 写入失败保留 pending，下次 flush 重试
                Constants.LOG.warn("Failed to export missing translation keys for language {}.",
                        languageCode, e);
            }
        }
    }

    private static int countPendingLocked() {
        int count = 0;
        for (Map<String, String> pending : pendingRecords.values()) {
            count += pending.size();
        }
        return count;
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
