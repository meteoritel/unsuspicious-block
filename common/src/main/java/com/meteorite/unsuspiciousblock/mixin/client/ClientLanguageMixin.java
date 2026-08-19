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
 */
@Mixin(ClientLanguage.class)
public abstract class ClientLanguageMixin {
    @Inject(method = "loadFrom", at = @At("HEAD"))
    private static void usb$clearLootTableResourceIndex(ResourceManager resourceManager,
                                                        List<String> languageCodes,
                                                        boolean defaultRightToLeft,
                                                        CallbackInfoReturnable<ClientLanguage> cir) {
        ClientLootTableLanguageStore.clearResourceCache();
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
