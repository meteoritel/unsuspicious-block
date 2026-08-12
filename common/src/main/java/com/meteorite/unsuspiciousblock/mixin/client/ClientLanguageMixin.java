package com.meteorite.unsuspiciousblock.mixin.client;

import com.meteorite.unsuspiciousblock.client.ui.support.ClientLootTableLanguageStore;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端语言加载入口——把配置目录中的战利品表名称覆盖合并到原版语言实例。
 */
@Mixin(ClientLanguage.class)
public abstract class ClientLanguageMixin {
    @Inject(method = "loadFrom", at = @At("RETURN"), cancellable = true)
    private static void usb$applyLootTableLanguageOverrides(ResourceManager resourceManager,
                                                            List<String> languageCodes,
                                                            boolean defaultRightToLeft,
                                                            CallbackInfoReturnable<ClientLanguage> cir) {
        ClientLanguage original = cir.getReturnValue();
        Map<String, String> merged = new HashMap<>(
                ((ClientLanguageAccessor) (Object) original).usb$getStorage());
        if (!ClientLootTableLanguageStore.applyOverrides(merged, languageCodes)) return;
        cir.setReturnValue(ClientLanguageAccessor.usb$create(Map.copyOf(merged), defaultRightToLeft));
    }
}
