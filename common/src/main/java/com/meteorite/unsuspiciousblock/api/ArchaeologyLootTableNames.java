package com.meteorite.unsuspiciousblock.api;

import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 考古战利品表名称注册表——提供名称映射、本地化 key 规则与 fallback 解析 */
public final class ArchaeologyLootTableNames {
    private static final String KEY_PREFIX = "screen.unsuspiciousblock.archaeology_journal.table.";
    private static final Map<ResourceLocation, NameRegistration> REGISTRATIONS = new LinkedHashMap<>();

    static {
        seedVanilla("minecraft:archaeology/desert_pyramid", "screen.unsuspiciousblock.archaeology_journal.table.desert_pyramid");
        seedVanilla("minecraft:archaeology/desert_well", "screen.unsuspiciousblock.archaeology_journal.table.desert_well");
        seedVanilla("minecraft:archaeology/ocean_ruin_cold", "screen.unsuspiciousblock.archaeology_journal.table.ocean_ruin_cold");
        seedVanilla("minecraft:archaeology/ocean_ruin_warm", "screen.unsuspiciousblock.archaeology_journal.table.ocean_ruin_warm");
        seedVanilla("minecraft:archaeology/trail_ruins_common", "screen.unsuspiciousblock.archaeology_journal.table.trail_ruins_common");
        seedVanilla("minecraft:archaeology/trail_ruins_rare", "screen.unsuspiciousblock.archaeology_journal.table.trail_ruins_rare");
    }

    private ArchaeologyLootTableNames() {
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

    // 获取该战利品表当前会使用的本地化 key
    public static String translationKey(ResourceLocation tableId) {
        validateArchaeologyTableId(tableId);
        NameRegistration registration = REGISTRATIONS.get(tableId);
        return registration != null ? registration.translationKey() : createTranslationKey(tableId);
    }

    // 解析最终展示名：已注册时优先走 translatableWithFallback，否则退回清洗后的文件名
    public static Component resolveDisplayName(ResourceLocation tableId) {
        validateArchaeologyTableId(tableId);
        NameRegistration registration = REGISTRATIONS.get(tableId);
        if (registration == null) {
            return Component.literal(humanizeTablePath(tableId));
        }

        String fallbackName = hasText(registration.fallbackName())
                ? registration.fallbackName()
                : humanizeTablePath(tableId);
        return Component.translatableWithFallback(registration.translationKey(), fallbackName);
    }

    private static void seedVanilla(String tableId, String translationKey) {
        ResourceLocation id = ResourceLocation.tryParse(tableId);
        if (id == null) {
            throw new IllegalStateException("Invalid archaeology loot table id: " + tableId);
        }
        registerInternal(id, translationKey, null);
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
        if (!tableId.getPath().startsWith("archaeology/")) {
            throw new IllegalArgumentException("Only archaeology loot tables are supported: " + tableId);
        }
    }

    private static String createTranslationKey(ResourceLocation tableId) {
        return KEY_PREFIX + tableId.getNamespace() + "." + normalizePathForKey(tableId.getPath());
    }

    private static String normalizePathForKey(String path) {
        String normalized = stripArchaeologyPrefix(path)
                .replace('/', '_')
                .replace('-', '_')
                .replace('.', '_')
                .toLowerCase(Locale.ROOT)
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isEmpty() ? "unknown" : normalized;
    }

    private static String humanizeTablePath(ResourceLocation tableId) {
        String normalized = stripArchaeologyPrefix(tableId.getPath())
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

    private static String stripArchaeologyPrefix(String path) {
        if (path.startsWith("archaeology/")) {
            return path.substring("archaeology/".length());
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
