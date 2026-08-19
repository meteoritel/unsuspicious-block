package com.meteorite.unsuspiciousblock.client.ui.support;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.loottable.catalog.MissingTranslationKeyExporter;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端战利品表语言视图——读取游戏资源来源，并在内存中应用当前服务端的补充翻译。
 */
public final class ClientLootTableLanguageStore {
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() { }.getType();
    private static final Map<String, ResourceLanguage> resourceLanguages = new LinkedHashMap<>();
    private static volatile Map<String, Map<String, String>> serverTranslations = Map.of();

    private ClientLootTableLanguageStore() {
    }

    // 注册缺失 key 汇总使用的当前语言、游戏资源和服务端补充配置读取函数
    public static void initialize() {
        MissingTranslationKeyExporter.configureClient(
                () -> Minecraft.getInstance().getLanguageManager().getSelected(),
                languageCode -> resourceLanguage(languageCode).values(),
                languageCode -> serverTranslations.getOrDefault(languageCode, Map.of()));
    }

    // 服务端快照只驻留当前连接的客户端内存；语言查询会立即读取新快照，无需重载资源
    public static void receiveServerTranslations(Map<String, Map<String, String>> translations) {
        serverTranslations = copyTranslations(translations);
    }

    // 断开连接时清除上一台服务器的覆盖，避免跨服务器残留
    public static void resetOnDisconnect() {
        serverTranslations = Map.of();
    }

    // 实际资源重载时失效来源索引，下次查询按新的资源栈重新构建
    public static synchronized void clearResourceCache() {
        resourceLanguages.clear();
    }

    // 仅在游戏资源缺少对应语言 key 时返回服务端补充值；null 表示沿用原版查询结果
    public static String dynamicTranslation(String translationKey) {
        if (!LootTableNames.isGeneratedTranslationKey(translationKey)) return null;
        String currentLanguage = Minecraft.getInstance().getLanguageManager().getSelected();
        if (resourceLanguage(currentLanguage).values().containsKey(translationKey)) return null;

        String currentServerValue = serverTranslation(currentLanguage, translationKey);
        if (!currentServerValue.isBlank()) return currentServerValue;
        if ("en_us".equals(currentLanguage)
                || resourceLanguage("en_us").values().containsKey(translationKey)) {
            return null;
        }
        String englishServerValue = serverTranslation("en_us", translationKey);
        return englishServerValue.isBlank() ? null : englishServerValue;
    }

    public static ResourceTranslation findResourceTranslation(String languageCode, String translationKey) {
        String value = resourceLanguage(languageCode).values().get(translationKey);
        if (value == null) return null;
        return new ResourceTranslation(value,
                resourceLanguage(languageCode).sourcePacks().getOrDefault(translationKey, "unknown"));
    }

    public static String serverTranslation(String languageCode, String translationKey) {
        return serverTranslations.getOrDefault(languageCode, Map.of()).getOrDefault(translationKey, "");
    }

    private static synchronized ResourceLanguage resourceLanguage(String languageCode) {
        return resourceLanguages.computeIfAbsent(languageCode, ClientLootTableLanguageStore::loadResourceLanguage);
    }

    // 扫描当前资源栈中的所有同语言 JSON，并记录最终提供每个战利品表 key 的资源包
    private static ResourceLanguage loadResourceLanguage(String languageCode) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> sourcePacks = new LinkedHashMap<>();
        String expectedPath = "lang/" + languageCode + ".json";
        try {
            var stacks = Minecraft.getInstance().getResourceManager().listResourceStacks(
                    "lang", location -> location.getPath().equals(expectedPath));
            for (Map.Entry<ResourceLocation, List<Resource>> stackEntry : stacks.entrySet()) {
                for (Resource resource : stackEntry.getValue()) {
                    try (Reader reader = resource.openAsReader()) {
                        Map<String, String> loaded = GSON.fromJson(reader, MAP_TYPE);
                        if (loaded == null) continue;
                        loaded.forEach((key, value) -> {
                            if (LootTableNames.isGeneratedTranslationKey(key) && value != null) {
                                values.put(key, value);
                                sourcePacks.put(key, resource.sourcePackId());
                            }
                        });
                    } catch (IOException | RuntimeException ignored) {
                        // 原版语言加载会记录损坏资源；管理页面忽略单个不可读来源
                    }
                }
            }
        } catch (RuntimeException ignored) {
            return new ResourceLanguage(Map.of(), Map.of());
        }
        return new ResourceLanguage(Map.copyOf(values), Map.copyOf(sourcePacks));
    }

    private static Map<String, Map<String, String>> copyTranslations(
            Map<String, Map<String, String>> source) {
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        source.forEach((language, values) -> copy.put(language, Map.copyOf(values)));
        return Map.copyOf(copy);
    }

    /** 游戏资源中的只读翻译及其资源包来源。 */
    public record ResourceTranslation(String value, String sourcePackId) {
    }

    /** 单种语言在当前资源栈中的只读值与来源索引。 */
    private record ResourceLanguage(Map<String, String> values, Map<String, String> sourcePacks) {
    }
}
