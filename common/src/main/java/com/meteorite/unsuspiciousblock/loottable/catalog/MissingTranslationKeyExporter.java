package com.meteorite.unsuspiciousblock.loottable.catalog;

import com.meteorite.unsuspiciousblock.Constants;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 缺失本地化诊断入口——提供游戏资源与当前服务端补充配置的只读快照。
 */
public final class MissingTranslationKeyExporter {
    private static volatile Supplier<String> clientLanguageSupplier;
    private static volatile Function<String, Map<String, String>> resourceLanguageLookup = code -> Map.of();
    private static volatile Function<String, Map<String, String>> serverLanguageLookup = code -> Map.of();

    private MissingTranslationKeyExporter() {
    }

    // 客户端初始化时注册当前语言、游戏资源和服务端配置读取函数
    public static void configureClient(Supplier<String> languageSupplier,
                                       Function<String, Map<String, String>> resourceLanguageReader,
                                       Function<String, Map<String, String>> serverLanguageReader) {
        clientLanguageSupplier = languageSupplier;
        resourceLanguageLookup = resourceLanguageReader;
        serverLanguageLookup = serverLanguageReader;
    }

    /** 缺失 key 汇总使用的一致语言快照。 */
    public record LanguageSnapshot(String currentLanguageCode,
                                   Map<String, String> currentResource,
                                   Map<String, String> currentOverrides,
                                   Map<String, String> enUsResource) {
    }

    public static LanguageSnapshot captureLanguageSnapshot() {
        Supplier<String> supplier = clientLanguageSupplier;
        String languageCode = supplier == null ? null : supplier.get();
        if (languageCode == null || !languageCode.matches("[a-z0-9_-]{2,16}")) {
            return new LanguageSnapshot(null, Map.of(), Map.of(), Map.of());
        }
        return new LanguageSnapshot(languageCode,
                safeLookup(resourceLanguageLookup, languageCode),
                safeLookup(serverLanguageLookup, languageCode),
                safeLookup(resourceLanguageLookup, "en_us"));
    }

    private static Map<String, String> safeLookup(Function<String, Map<String, String>> lookup,
                                                   String languageCode) {
        try {
            return Map.copyOf(lookup.apply(languageCode));
        } catch (RuntimeException exception) {
            Constants.LOG.warn("Failed to read loot table language view {}.", languageCode, exception);
            return Map.of();
        }
    }
}
