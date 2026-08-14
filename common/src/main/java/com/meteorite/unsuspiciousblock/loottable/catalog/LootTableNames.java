package com.meteorite.unsuspiciousblock.loottable.catalog;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 考古战利品表名称注册表——提供名称映射、本地化 key 规则与 fallback 解析 */
public final class LootTableNames {
    private static final String KEY_PREFIX = "screen.unsuspiciousblock.archaeology_journal.table.";
    private static final Map<ResourceLocation, NameRegistration> REGISTRATIONS = new LinkedHashMap<>();
    // 待汇总的缺失 key 条目（tableId -> 首次检测到的 key 与 fallback 名）。
    // 收集阶段零 I/O，由 logMissingTranslationSummary 统一分类汇总输出
    private static final Map<ResourceLocation, MissingEntry> PENDING_MISSING = new LinkedHashMap<>();

    // 缓存上次解析的规则列表，避免每次匹配重复解析配置字符串
    private static volatile List<String> cachedRawPatterns;
    private static volatile List<LootTablePattern> cachedPatterns = List.of();

    private LootTableNames() {
    }

    // 注册战利品表名称映射；使用规则生成 key，fallback 名称由 path 自动清洗得到
    public static void register(ResourceLocation tableId) {
        validateArchaeologyTableId(tableId);
        registerInternal(tableId, createTranslationKey(tableId), null);
    }

    // 注册战利品表名称映射；使用规则生成 key，并允许显式指定 fallback 展示名
    public static void register(ResourceLocation tableId, String fallbackName) {
        validateArchaeologyTableId(tableId);
        registerInternal(tableId, createTranslationKey(tableId), fallbackName);
    }

    // 判断该表是否命中任一配置规则（命名空间 + 路径前缀/精确）
    public static boolean isArchaeologyLootTable(ResourceLocation tableId) {
        if (Services.LOOT_TABLE_CONFIG.getExcludedLootTables().contains(tableId.toString())) {
            return false;
        }
        for (LootTablePattern pattern : patterns()) {
            if (pattern.matches(tableId)) {
                return true;
            }
        }
        return false;
    }

    // 获取当前生效的匹配规则列表；配置变化时重新解析并缓存
    private static List<LootTablePattern> patterns() {
        List<String> rawPatterns = Services.LOOT_TABLE_CONFIG.getArchaeologyPathPrefixes();
        if (rawPatterns.equals(cachedRawPatterns)) {
            return cachedPatterns;
        }

        List<LootTablePattern> parsed = new ArrayList<>(rawPatterns.size());
        for (String raw : rawPatterns) {
            LootTablePattern pattern = LootTablePattern.parse(raw);
            if (pattern != null) {
                parsed.add(pattern);
            }
        }
        List<LootTablePattern> immutable = List.copyOf(parsed);
        cachedPatterns = immutable;
        cachedRawPatterns = List.copyOf(rawPatterns);
        return immutable;
    }

    // 确保该战利品表已进入名称映射；若外部未显式注册，则自动生成默认 key 与 fallback 规则
    public static void ensureRegistered(ResourceLocation tableId) {
        REGISTRATIONS.computeIfAbsent(tableId, id -> new NameRegistration(createTranslationKey(id), null));
    }

    // 获取该战利品表当前会使用的本地化 key
    public static String translationKey(ResourceLocation tableId) {
        NameRegistration registration = REGISTRATIONS.get(tableId);
        return registration != null ? registration.translationKey() : createTranslationKey(tableId);
    }

    // 获取该战利品表当前会使用的 fallback 展示名
    public static String fallbackName(ResourceLocation tableId) {
        NameRegistration registration = REGISTRATIONS.get(tableId);
        if (registration != null && hasText(registration.fallbackName())) {
            return registration.fallbackName();
        }
        return humanizeTablePath(tableId);
    }

    // 解析最终展示名：优先走规则 key，本地化缺失时退回 fallback 名称
    public static Component resolveDisplayName(ResourceLocation tableId) {
        String translationKey = translationKey(tableId);
        String fallbackName = fallbackName(tableId);
        collectMissingTranslation(tableId, translationKey, fallbackName);
        return Component.translatableWithFallback(translationKey, fallbackName);
    }

    // 强制版本：立即输出缺失汇总并落盘，忽略收集去重；仅供调试命令手动补救使用
    public static Component forceResolveDisplayName(ResourceLocation tableId) {
        String translationKey = translationKey(tableId);
        String fallbackName = fallbackName(tableId);
        PENDING_MISSING.put(tableId, new MissingEntry(translationKey, fallbackName));
        logMissingTranslationSummary();
        MissingTranslationKeyExporter.flushPending();
        return Component.translatableWithFallback(translationKey, fallbackName);
    }

    // 收集缺失 key（同一 tableId 去重），分类与输出推迟到 logMissingTranslationSummary
    private static void collectMissingTranslation(ResourceLocation tableId, String translationKey, String fallbackName) {
        PENDING_MISSING.putIfAbsent(tableId, new MissingEntry(translationKey, fallbackName));
    }

    /**
     * 汇总输出所有已检测到的缺失 key，按语言 json 格式给出条目，便于直接粘贴到语言文件补全：
     * <ul>
     *   <li>当前语言与 en_us 均缺失 -- 界面使用 fallback 名称</li>
     *   <li>当前语言缺失但 en_us 已有翻译 -- 界面显示英文，可按 en_us 值翻译</li>
     * </ul>
     * 批处理边界（目录加载结束、调试命令）调用；输出后清空收集。
     * 无客户端语言环境（专用服务端）时统一按 fallback 类别输出。
     */
    public static void logMissingTranslationSummary() {
        if (PENDING_MISSING.isEmpty()) {
            return;
        }

        MissingTranslationKeyExporter.LanguageSnapshot snapshot =
                MissingTranslationKeyExporter.captureLanguageSnapshot();
        boolean serverContext = snapshot.currentLanguageCode() == null;

        List<String> fallbackLines = new ArrayList<>();
        List<String> englishOnlyLines = new ArrayList<>();
        for (MissingEntry entry : PENDING_MISSING.values()) {
            String translationKey = entry.translationKey();
            // 当前语言是否覆盖该 key：客户端综合资源文件与自定义覆盖文件；服务端退化为合并语言视图
            boolean currentLanguageHas = serverContext
                    ? Language.getInstance().has(translationKey)
                    : snapshot.currentResource().containsKey(translationKey)
                            || snapshot.currentOverrides().containsKey(translationKey);
            if (currentLanguageHas) {
                continue;
            }

            String enUsValue = snapshot.enUsResource().get(translationKey);
            if (!serverContext && enUsValue != null) {
                englishOnlyLines.add(jsonEntry(translationKey, enUsValue));
            } else {
                fallbackLines.add(jsonEntry(translationKey, entry.fallbackName()));
                // 完全缺失的 key 写入自动补全（客户端当前语言；服务端为空操作）
                MissingTranslationKeyExporter.record(translationKey, entry.fallbackName());
            }
        }
        PENDING_MISSING.clear();

        if (fallbackLines.isEmpty() && englishOnlyLines.isEmpty()) {
            return;
        }
        String languageLabel = serverContext ? "server(merged view)" : snapshot.currentLanguageCode();
        StringBuilder message = new StringBuilder()
                .append("[loot-table-names] 本地化缺失汇总（当前语言: ").append(languageLabel)
                .append("，待补全表名共 ").append(fallbackLines.size() + englishOnlyLines.size()).append(" 个）：");
        if (!fallbackLines.isEmpty()) {
            message.append("\n-- 当前语言与 en_us 均缺失，已使用 fallback 名称（")
                    .append(fallbackLines.size()).append(" 个，可直接粘贴到语言 json 补全）:");
            fallbackLines.forEach(line -> message.append('\n').append(line));
        }
        if (!englishOnlyLines.isEmpty()) {
            message.append("\n-- 当前语言缺失但 en_us 已有翻译，界面显示英文（")
                    .append(englishOnlyLines.size()).append(" 个，可按 en_us 值翻译）:");
            englishOnlyLines.forEach(line -> message.append('\n').append(line));
        }
        Constants.LOG.warn("{}", message);
    }

    // 生成可直接粘贴进语言 json 的 "key": "value", 行，value 做 JSON 转义
    private static String jsonEntry(String translationKey, String value) {
        return "  \"" + translationKey + "\": \"" + jsonEscape(value) + "\",";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // 清空名称注册与缺失收集（服务端停止时调用，避免跨世界累积旧条目）
    public static void clear() {
        REGISTRATIONS.clear();
        PENDING_MISSING.clear();
    }

    private static void registerInternal(ResourceLocation tableId, String translationKey, String fallbackName) {
        NameRegistration registration = new NameRegistration(translationKey, normalizeFallbackName(fallbackName));
        NameRegistration previous = REGISTRATIONS.put(tableId, registration);
        if (previous != null && !Objects.equals(previous, registration)) {
            Constants.LOG.warn("Archaeology loot table name registration for {} was overridden: {} -> {}",
                    tableId, previous.translationKey(), registration.translationKey());
        }
    }

    private static void validateArchaeologyTableId(ResourceLocation tableId) {
        if (!isArchaeologyLootTable(tableId)) {
            throw new IllegalArgumentException("Only archaeology loot tables are supported: " + tableId);
        }
    }

    public static String createTranslationKey(ResourceLocation tableId) {
        // 保留完整 path（含 archaeology 前缀），避免不同前缀下的同名表产生 key 冲突
        return KEY_PREFIX + tableId.getNamespace() + "." + normalizePathForKey(tableId.getPath());
    }

    // 限制动态语言覆盖范围，避免自定义名称文件修改其他 GUI 文本
    public static boolean isGeneratedTranslationKey(String translationKey) {
        return translationKey != null && translationKey.startsWith(KEY_PREFIX);
    }

    private static String normalizePathForKey(String path) {
        String normalized = path
                .replace('/', '_')
                .replace('-', '_')
                .replace('.', '_')
                .toLowerCase(Locale.ROOT)
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isEmpty() ? "unknown" : normalized;
    }

    private static String humanizeTablePath(ResourceLocation tableId) {
        String normalized = tableId.getPath()
                .replaceAll("[/_.-]+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return tableId.getPath();
        }

        StringBuilder builder = new StringBuilder();
        for (String part : normalized.split("\\s+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(titleCase(part));
        }
        return builder.toString();
    }

    private static String titleCase(String part) {
        if (part.length() == 1) {
            return part.toUpperCase(Locale.ROOT);
        }
        return Character.toUpperCase(part.charAt(0)) + part.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String normalizeFallbackName(String fallbackName) {
        return hasText(fallbackName) ? fallbackName.trim() : null;
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private record NameRegistration(String translationKey, String fallbackName) {
    }

    /** 单个待汇总的缺失 key 条目 */
    private record MissingEntry(String translationKey, String fallbackName) {
    }
}
