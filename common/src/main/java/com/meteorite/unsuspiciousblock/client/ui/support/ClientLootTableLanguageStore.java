package com.meteorite.unsuspiciousblock.client.ui.support;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNames;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.MissingTranslationKeyExporter;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端战利品表语言视图——读取游戏资源来源，并在内存中应用当前服务端的补充翻译。
 */
public final class ClientLootTableLanguageStore {
    private static final Map<String, ResourceLanguage> resourceLanguages = new LinkedHashMap<>();
    private static volatile Map<String, Map<String, String>> serverTranslations = Map.of();
    private static volatile long revision;

    private ClientLootTableLanguageStore() {
    }

    // 注册缺失 key 汇总使用的当前语言、游戏资源和服务端补充配置读取函数
    public static void initialize() {
        MissingTranslationKeyExporter.configureClient(
                () -> Minecraft.getInstance().getLanguageManager().getSelected(),
                languageCode -> resourceLanguage(languageCode).values(),
                languageCode -> {
                    Map<String, String> values = new LinkedHashMap<>();
                    serverTranslations.getOrDefault(languageCode, Map.of()).forEach((id, value) ->
                            values.put(LootTableNames.createTranslationKey(ResourceLocation.parse(id)), value));
                    return values;
                });
    }

    // 服务端快照只驻留当前连接的客户端内存；语言查询会立即读取新快照，无需重载资源
    public static void receiveServerTranslations(Map<String, Map<String, String>> translations) {
        if (!serverTranslations.equals(translations)) {
            serverTranslations = translations;
            revision++;
        }
    }

    // 断开连接时清除上一台服务器的覆盖，避免跨服务器残留
    public static void resetOnDisconnect() {
        receiveServerTranslations(Map.of());
    }

    /** 资源重载时更新当前语言的来源索引。 */
    public static synchronized void prewarmResourceIndex(ResourceManager resourceManager, List<String> languageCodes) {
        resourceLanguages.clear();
        for (String languageCode : languageCodes) {
            resourceLanguages.put(languageCode, loadResourceLanguage(resourceManager, languageCode));
        }
        revision++;
    }

    /** 当前连接的补充名称或本地资源变更后，依赖表名的读模型重建。 */
    public static long revision() {
        return revision;
    }

    /** 使用当前游戏语言解析表名，创建可独立显示的组件。 */
    public static Component displayName(ResourceLocation tableId) {
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        return Component.literal(resolve(tableId, language).value());
    }

    /** 返回名称及当前命中的来源，供编辑和浏览共用。 */
    public static Name resolve(ResourceLocation tableId, String language) {
        String key = LootTableNames.createTranslationKey(tableId);
        ResourceTranslation resource = findResourceTranslation(language, key);
        if (resource != null) return new Name(resource.value(), Source.RESOURCE, resource.sourcePackId());
        String supplemental = serverTranslation(language, tableId);
        if (!supplemental.isEmpty()) return new Name(supplemental, Source.SERVER, "");
        if (!"en_us".equals(language)) {
            ResourceTranslation english = findResourceTranslation("en_us", key);
            if (english != null) return new Name(english.value(), Source.ENGLISH_RESOURCE, english.sourcePackId());
            String englishServer = serverTranslation("en_us", tableId);
            if (!englishServer.isEmpty()) return new Name(englishServer, Source.ENGLISH_SERVER, "");
        }
        return new Name(LootTableNames.fallbackName(tableId), Source.FALLBACK, "");
    }

    /** 表名显示来源。 */
    public enum Source { RESOURCE, SERVER, ENGLISH_RESOURCE, ENGLISH_SERVER, FALLBACK }

    /** 当前语言中解析出的有效表名。 */
    public record Name(String value, Source source, String sourcePackId) {}

    public static ResourceTranslation findResourceTranslation(String languageCode, String translationKey) {
        ResourceLanguage resource = resourceLanguage(languageCode);
        String value = resource.values().get(translationKey);
        if (value == null) return null;
        return new ResourceTranslation(value, resource.sourcePacks().get(translationKey));
    }

    public static String serverTranslation(String languageCode, ResourceLocation tableId) {
        return serverTranslations.getOrDefault(languageCode, Map.of()).getOrDefault(tableId.toString(), "");
    }

    private static synchronized ResourceLanguage resourceLanguage(String languageCode) {
        return resourceLanguages.computeIfAbsent(languageCode, ClientLootTableLanguageStore::loadResourceLanguage);
    }

    // 懒加载路径：使用当前生效的资源管理器按需构建
    private static ResourceLanguage loadResourceLanguage(String languageCode) {
        return loadResourceLanguage(Minecraft.getInstance().getResourceManager(), languageCode);
    }

    // 扫描资源栈中的所有同语言 JSON，并记录最终提供每个战利品表 key 的资源包
    private static ResourceLanguage loadResourceLanguage(ResourceManager resourceManager, String languageCode) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> sourcePacks = new LinkedHashMap<>();
        String path = "lang/" + languageCode + ".json";
        for (String namespace : resourceManager.getNamespaces()) {
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath(namespace, path);
            for (Resource resource : resourceManager.getResourceStack(location)) {
                try (InputStream input = resource.open()) {
                    Language.loadFromJson(input, (key, value) -> {
                        if (LootTableNames.isGeneratedTranslationKey(key)) {
                            values.put(key, value);
                            sourcePacks.put(key, resource.sourcePackId());
                        }
                    });
                } catch (IOException | RuntimeException exception) {
                    Constants.LOG.warn("{}读取 {} 的资源翻译失败 {}", Constants.LOG_TAG, location,
                            resource.sourcePackId(), exception);
                }
            }
        }
        return new ResourceLanguage(Map.copyOf(values), Map.copyOf(sourcePacks));
    }

    /** 游戏资源中的只读翻译及其资源包来源。 */
    public record ResourceTranslation(String value, String sourcePackId) {
    }

    /** 单种语言在当前资源栈中的只读值与来源索引。 */
    private record ResourceLanguage(Map<String, String> values, Map<String, String> sourcePacks) {
    }
}
