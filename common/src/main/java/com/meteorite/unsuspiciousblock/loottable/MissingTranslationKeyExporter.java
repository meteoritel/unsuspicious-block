package com.meteorite.unsuspiciousblock.loottable;

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
import java.util.LinkedHashMap;
import java.util.Map;

/***
 * 缺失本地化 key 导出器——将运行期检测到的缺失 key 追加写入游戏目录下的
 * <code>usb_miss_key/missing_keys.json</code>，采用标准语言文件格式，
 * value 预填 fallback 显示名，便于直接修改后合并回模组语言文件。
 */
public final class MissingTranslationKeyExporter {

    private static final String EXPORT_DIR_NAME = "usb_miss_key";
    private static final String EXPORT_FILE_NAME = "missing_keys.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();

    // 文件写入串行化，避免并发下文件损坏
    private static final Object WRITE_LOCK = new Object();

    private MissingTranslationKeyExporter() {
    }

    // 记录一个缺失的 key；已存在则保留原值不覆盖，新 key 追加到文件末尾。
    // 返回 true 表示成功写入或 key 已存在；返回 false 表示因 I/O 失败未写入，调用方应允许后续重试
    static boolean record(String translationKey, String fallbackValue) {
        synchronized (WRITE_LOCK) {
            try {
                Path file = resolveExportFile();
                Map<String, String> data = loadExisting(file);
                // 已存在的 key 保留玩家可能已修改的值，避免覆盖；仅在新增时才写盘，避免无谓 I/O
                boolean changed = data.putIfAbsent(translationKey, fallbackValue) == null;
                if (changed) {
                    save(file, data);
                }
                return true;
            } catch (IOException e) {
                Constants.LOG.warn("Failed to export missing translation key '{}' to {}", translationKey, EXPORT_FILE_NAME, e);
                return false;
            }
        }
    }

    private static Path resolveExportFile() throws IOException {
        Path dir = Services.PLATFORM.getGameDir().resolve(EXPORT_DIR_NAME);
        Files.createDirectories(dir);
        return dir.resolve(EXPORT_FILE_NAME);
    }

    // 读取已存在文件内容；损坏或不存在时返回空 LinkedHashMap 以保持插入顺序
    private static Map<String, String> loadExisting(Path file) {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, String> loaded = GSON.fromJson(reader, MAP_TYPE);
            if (loaded != null) {
                // 用 LinkedHashMap 保持顺序，便于阅读与比对
                return new LinkedHashMap<>(loaded);
            }
        } catch (IOException e) {
            Constants.LOG.warn("Failed to read existing missing-key file {}, will overwrite.", file, e);
        }
        return new LinkedHashMap<>();
    }

    private static void save(Path file, Map<String, String> data) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(data, writer);
        }
    }
}
