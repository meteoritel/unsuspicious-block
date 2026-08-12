package com.meteorite.unsuspiciousblock.mixin.client;

import net.minecraft.client.resources.language.ClientLanguage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;

/**
 * ClientLanguage 访问器——读取已加载词条并调用私有构造器创建合并后的语言实例。
 */
@Mixin(ClientLanguage.class)
public interface ClientLanguageAccessor {
    @Accessor("storage")
    Map<String, String> usb$getStorage();

    @Invoker("<init>")
    static ClientLanguage usb$create(Map<String, String> storage, boolean defaultRightToLeft) {
        throw new AssertionError();
    }
}
