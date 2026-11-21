package com.meteorite.unsuspiciousblock.platform.plugin.create;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** 在准备 Create 的目标类之前，按 NeoForge 发现的模组版本选择风扇注入。 */
public final class CreateFanMixinPlugin implements IMixinConfigPlugin {
    private static final String CREATE_MOD_ID = "create";
    private static final String SUPPORTED_CREATE_VERSION = "6.0.10";

    @Override
    public List<String> getMixins() {
        boolean isSupported = LoadingModList.get().getMods().stream().anyMatch(mod ->
                CREATE_MOD_ID.equals(mod.getModId()) && SUPPORTED_CREATE_VERSION.equals(mod.getVersion().toString()));
        return isSupported ? List.of("BlastingTypeMixin") : List.of();
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> ownTargets, Set<String> otherTargets) {
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
