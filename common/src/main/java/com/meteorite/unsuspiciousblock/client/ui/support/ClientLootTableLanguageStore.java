package com.meteorite.unsuspiciousblock.client.ui.support;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.loottable.catalog.MissingTranslationKeyExporter;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * 客户端战利品表语言存储--落盘服务端名称快照，并在原版语言加载时应用全局覆盖。
 */
public final class ClientLootTableLanguageStore {
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();

    private ClientLootTableLanguageStore() {
    }

    // 注册缺失 key 导出时使用的客户端当前语言，以及模组自身资源语言文件的读取函数
    public static void initialize() {
        MissingTranslationKeyExporter.configureClient(
                () -> Minecraft.getInstance().getLanguageManager().getSelected(),
                ClientLootTableLanguageStore::loadResourceLanguage);
    }

    // 网络接收器可能不在渲染线程，统一投递后写盘并按需重载资源
    public static void receiveServerTranslations(Map<String, Map<String, String>> translations) {
        if (translations.isEmpty()) return;
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            boolean changed = false;
            for (Map.Entry<String, Map<String, String>> entry : translations.entrySet()) {
                changed |= MissingTranslationKeyExporter.mergeLanguageValues(entry.getKey(), entry.getValue());
            }
            if (changed) client.reloadResourcePacks();
        });
    }

    // 按原版给出的 fallback -> 当前语言顺序覆盖，返回是否改变了已加载语言表
    public static boolean applyOverrides(Map<String, String> target, List<String> languageCodes) {
        boolean changed = false;
        for (String languageCode : languageCodes) {
            for (Map.Entry<String, String> entry
                    : MissingTranslationKeyExporter.loadLanguageValues(languageCode).entrySet()) {
                if (!entry.getValue().equals(target.put(entry.getKey(), entry.getValue()))) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    // 从客户端资源管理器读取模组自身的语言文件（如 en_us.json），供缺失 key 汇总分类使用
    private static Map<String, String> loadResourceLanguage(String languageCode) {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                Constants.MOD_ID, "lang/" + languageCode + ".json");
        try {
            return Minecraft.getInstance().getResourceManager().getResource(location)
                    .map(resource -> {
                        try (Reader reader = resource.openAsReader()) {
                            Map<String, String> loaded = GSON.fromJson(reader, MAP_TYPE);
                            return loaded != null ? loaded : Map.<String, String>of();
                        } catch (IOException e) {
                            return Map.<String, String>of();
                        }
                    })
                    .orElse(Map.of());
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
