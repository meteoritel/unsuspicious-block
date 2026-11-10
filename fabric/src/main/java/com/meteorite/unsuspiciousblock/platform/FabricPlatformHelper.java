package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.core.loottable.injection.DeclaredLootInjection;
import com.meteorite.unsuspiciousblock.platform.loot.FabricLootInjectionDescriptions;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import com.meteorite.unsuspiciousblock.platform.services.IPlatformHelper;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/** Fabric 平台实现 */
public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public List<DeclaredLootInjection> describeLootInjections(
            ResourceLocation table) {
        return FabricLootInjectionDescriptions.describe(table);
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public String getModDisplayName(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(container -> container.getMetadata().getName())
                .filter(name -> !name.isBlank())
                .orElse(IPlatformHelper.super.getModDisplayName(modId));
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public Path getGameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    @Nullable
    public String getModVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse(null);
    }
}
