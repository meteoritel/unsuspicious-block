package com.meteorite.unsuspiciousblock.mixin.compat.lootr;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Lootr 兼容 Mixin 条件加载插件。
 * 仅在 Lootr 已安装时启用兼容 Mixin，避免无 Lootr 时出现 NoClassDefFoundError。
 */
public class LootrCompatMixinPlugin implements IMixinConfigPlugin {

    private static boolean lootrPresent = false;

    @Override
    public void onLoad(String mixinPackage) {
        try {
            Class.forName("noobanidus.mods.lootr.common.api.LootrAPI");
            lootrPresent = true;
        } catch (ClassNotFoundException e) {
            lootrPresent = false;
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return lootrPresent;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // 无操作
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // 无操作
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // 无操作
    }
}
