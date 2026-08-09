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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 考古战利品表名称注册表——提供名称映射、本地化 key 规则与 fallback 解析 */
public final class LootTableNames {
    private static final String KEY_PREFIX = "screen.unsuspiciousblock.archaeology_journal.table.";
    private static final Map<ResourceLocation, NameRegistration> REGISTRATIONS = new LinkedHashMap<>();
    private static final Set<ResourceLocation> WARNED_MISSING_TRANSLATIONS = ConcurrentHashMap.newKeySet();

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
        if (rawPatterns == cachedRawPatterns) {
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
        cachedRawPatterns = rawPatterns;
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
        warnMissingTranslation(tableId, translationKey, fallbackName);
        return Component.translatableWithFallback(translationKey, fallbackName);
    }

    // 强制重新检索缺失 key 并写盘，忽略 WARNED_MISSING_TRANSLATIONS 缓存；仅供调试命令手动补救使用
    public static Component forceResolveDisplayName(ResourceLocation tableId) {
        String translationKey = translationKey(tableId);
        String fallbackName = fallbackName(tableId);
        forceMissingTranslation(tableId, translationKey, fallbackName);
        return Component.translatableWithFallback(translationKey, fallbackName);
    }

    private static void warnMissingTranslation(ResourceLocation tableId, String translationKey, String fallbackName) {
        if (Language.getInstance().has(translationKey)) {
            return;
        }
        // 仅在 record 成功后才标记为已警告，避免瞬时 I/O 失败导致该 tableId 永久放弃重试
        if (!WARNED_MISSING_TRANSLATIONS.add(tableId)) {
            return;
        }
        Constants.LOG.warn("[auto] Archaeology loot table {} is using fallback display name '{}'; missing localization key: {}",
                tableId, fallbackName, translationKey);
        // 将缺失 key 追加写入游戏目录下的 usb_miss_key/missing_keys.json，便于补全本地化
        if (!MissingTranslationKeyExporter.record(translationKey, fallbackName)) {
            // 写入失败，撤销标记以便下次调用可重试
            WARNED_MISSING_TRANSLATIONS.remove(tableId);
        }
    }

    // 强制版本：绕过 WARNED_MISSING_TRANSLATIONS 缓存，每次调用都尝试写盘
    private static void forceMissingTranslation(ResourceLocation tableId, String translationKey, String fallbackName) {
        if (Language.getInstance().has(translationKey)) {
            return;
        }
        Constants.LOG.warn("[force] Archaeology loot table {} is using fallback display name '{}'; missing localization key: {}",
                tableId, fallbackName, translationKey);
        MissingTranslationKeyExporter.record(translationKey, fallbackName);
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

    private static String createTranslationKey(ResourceLocation tableId) {
        // 保留完整 path（含 archaeology 前缀），避免不同前缀下的同名表产生 key 冲突
        return KEY_PREFIX + tableId.getNamespace() + "." + normalizePathForKey(tableId.getPath());
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
}
