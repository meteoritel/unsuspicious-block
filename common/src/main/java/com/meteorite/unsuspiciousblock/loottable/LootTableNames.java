package com.meteorite.unsuspiciousblock.loottable;

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

    static {
        seedVanilla("minecraft:archaeology/desert_pyramid", "screen.unsuspiciousblock.archaeology_journal.table.desert_pyramid");
        seedVanilla("minecraft:archaeology/desert_well", "screen.unsuspiciousblock.archaeology_journal.table.desert_well");
        seedVanilla("minecraft:archaeology/ocean_ruin_cold", "screen.unsuspiciousblock.archaeology_journal.table.ocean_ruin_cold");
        seedVanilla("minecraft:archaeology/ocean_ruin_warm", "screen.unsuspiciousblock.archaeology_journal.table.ocean_ruin_warm");
        seedVanilla("minecraft:archaeology/trail_ruins_common", "screen.unsuspiciousblock.archaeology_journal.table.trail_ruins_common");
        seedVanilla("minecraft:archaeology/trail_ruins_rare", "screen.unsuspiciousblock.archaeology_journal.table.trail_ruins_rare");
    }

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
        validateArchaeologyTableId(tableId);
        REGISTRATIONS.computeIfAbsent(tableId, id -> new NameRegistration(createTranslationKey(id), null));
    }

    // 获取该战利品表当前会使用的本地化 key
    public static String translationKey(ResourceLocation tableId) {
        validateArchaeologyTableId(tableId);
        NameRegistration registration = REGISTRATIONS.get(tableId);
        return registration != null ? registration.translationKey() : createTranslationKey(tableId);
    }

    // 获取该战利品表当前会使用的 fallback 展示名
    public static String fallbackName(ResourceLocation tableId) {
        validateArchaeologyTableId(tableId);
        NameRegistration registration = REGISTRATIONS.get(tableId);
        if (registration != null && hasText(registration.fallbackName())) {
            return registration.fallbackName();
        }
        return humanizeTablePath(tableId);
    }

    // 解析最终展示名：优先走规则 key，本地化缺失时退回 fallback 名称
    public static Component resolveDisplayName(ResourceLocation tableId) {
        validateArchaeologyTableId(tableId);
        String translationKey = translationKey(tableId);
        String fallbackName = fallbackName(tableId);
        warnMissingTranslation(tableId, translationKey, fallbackName);
        return Component.translatableWithFallback(translationKey, fallbackName);
    }

    private static void seedVanilla(String tableId, String translationKey) {
        ResourceLocation id = ResourceLocation.tryParse(tableId);
        if (id == null) {
            throw new IllegalStateException("Invalid archaeology loot table id: " + tableId);
        }
        registerInternal(id, translationKey, null);
    }

    private static void warnMissingTranslation(ResourceLocation tableId, String translationKey, String fallbackName) {
        if (Language.getInstance().has(translationKey) || !WARNED_MISSING_TRANSLATIONS.add(tableId)) {
            return;
        }
        Constants.LOG.warn("Archaeology loot table {} is using fallback display name '{}'; missing localization key: {}",
                tableId, fallbackName, translationKey);
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
        return KEY_PREFIX + tableId.getNamespace() + "." + normalizePathForKey(stripArchaeologyPrefix(tableId));
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
        String normalized = stripArchaeologyPrefix(tableId)
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

    // 按命中的规则剥离 path 前缀；精确规则保留完整 path，未命中任何规则时也保留完整 path
    private static String stripArchaeologyPrefix(ResourceLocation tableId) {
        String path = tableId.getPath();
        for (LootTablePattern pattern : patterns()) {
            if (pattern.matches(tableId)) {
                return pattern.stripFrom(path);
            }
        }
        return path;
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
