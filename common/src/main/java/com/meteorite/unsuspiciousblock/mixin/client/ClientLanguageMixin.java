package com.meteorite.unsuspiciousblock.mixin.client;

import com.meteorite.unsuspiciousblock.client.ui.support.ClientLootTableLanguageStore;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 客户端语言查询入口——动态补充当前服务端名称，避免连接和断开时重载全部资源。
 * <p>
 * 挂在 {@code getOrDefault} / {@code has} 上的守卫必须保持廉价：进入
 * {@link ClientLootTableLanguageStore#dynamicTranslation} 后第一件事是
 * {@code startsWith(生成 key 前缀)} 的短路判断，不做 map 查询与正则。
 * 语言索引的构建（遍历资源栈 + GSON 解析）则移到了资源重载路径预热，
 * 避免首次命中生成 key 时在调用线程（可能是渲染线程）同步扫描资源。
 */
@Mixin(ClientLanguage.class)
public abstract class ClientLanguageMixin {
    @Inject(method = "loadFrom", at = @At("HEAD"))
    private static void usb$prewarmLootTableResourceIndex(ResourceManager resourceManager,
                                                          List<String> languageCodes,
                                                          boolean defaultRightToLeft,
                                                          CallbackInfoReturnable<ClientLanguage> cir) {
        // 资源重载路径：用即将生效的资源栈预热本次语言栈与 en_us 的索引
        ClientLootTableLanguageStore.prewarmResourceIndex(resourceManager, languageCodes);
    }

    @Inject(method = "getOrDefault(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            at = @At("RETURN"), cancellable = true)
    private void usb$resolveServerLootTableName(String translationKey, String fallback,
                                                CallbackInfoReturnable<String> cir) {
        String dynamic = ClientLootTableLanguageStore.dynamicTranslation(translationKey);
        if (dynamic != null) cir.setReturnValue(dynamic);
    }

    @Inject(method = "has(Ljava/lang/String;)Z", at = @At("RETURN"), cancellable = true)
    private void usb$recognizeServerLootTableName(String translationKey,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && ClientLootTableLanguageStore.dynamicTranslation(translationKey) != null) {
            cir.setReturnValue(true);
        }
    }
}
