package com.meteorite.unsuspiciousblock.client.mixin;

import com.meteorite.unsuspiciousblock.client.ui.support.ClientLootTableLanguageStore;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** 客户端重载时解析各语言的表名资源来源。 */
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
}
