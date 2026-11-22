package com.meteorite.unsuspiciousblock.platform.plugin.lootr;

import com.meteorite.unsuspiciousblock.Constants;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** 在解析 Lootr 目标类前，按发布物声明的版本启用整套注入。 */
public final class LootrMixinPlugin implements IMixinConfigPlugin {
    private static final String LOOTR_MOD_ID = "lootr";
    private static final String ACTIVATION_RESOURCE = "/unsuspiciousblock.lootr-neoforge.properties";

    @Override
    public List<String> getMixins() {
        var mod = LoadingModList.get().getMods().stream()
                .filter(candidate -> LOOTR_MOD_ID.equals(candidate.getModId()))
                .findFirst().orElse(null);
        if (mod == null) return List.of();

        Properties activation = new Properties();
        try (var resource = LootrMixinPlugin.class.getResourceAsStream(ACTIVATION_RESOURCE)) {
            activation.load(Objects.requireNonNull(resource, ACTIVATION_RESOURCE));
        } catch (IOException failure) {
            throw new UncheckedIOException(ACTIVATION_RESOURCE, failure);
        }
        List<String> versions = List.of(activation.getProperty("versions").split(","));
        String installedVersion = mod.getVersion().toString();
        if (!versions.contains(installedVersion)) {
            Constants.LOG.warn(Constants.LOG_TAG + "Lootr {} integration disabled. Supported versions: {}",
                    installedVersion, versions);
            return List.of();
        }
        return List.of(activation.getProperty("mixins").split(","));
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }

    @Override
    public void acceptTargets(Set<String> ownTargets, Set<String> otherTargets) {}

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
