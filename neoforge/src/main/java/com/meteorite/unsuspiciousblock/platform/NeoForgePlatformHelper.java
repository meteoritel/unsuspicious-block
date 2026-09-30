package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.platform.services.IPlatformHelper;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/** NeoForge 平台实现 */
public class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public java.util.List<com.meteorite.unsuspiciousblock.loottable.injection.DeclaredLootInjection> describeLootInjections(
            net.minecraft.resources.ResourceLocation table) {
        return com.meteorite.unsuspiciousblock.loot.LootInjectionDescriptions.observed(table);
    }

    @Override
    public java.util.List<String> lootInjectionHashInputs(net.minecraft.server.packs.resources.ResourceManager resources)
            throws java.io.IOException {
        var input = new java.util.ArrayList<String>();
        var entries = resources.listResources("loot_modifiers", location -> location.getPath().endsWith(".json"));
        for (var location : entries.keySet().stream().sorted(java.util.Comparator.comparing(Object::toString)).toList()) {
            for (var resource : resources.getResourceStack(location)) {
                try (var reader = resource.openAsReader()) {
                    input.add(location + "|" + resource.sourcePackId() + "|"
                            + com.google.gson.JsonParser.parseReader(reader).toString());
                }
            }
        }
        return java.util.List.copyOf(input);
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public String getModDisplayName(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getDisplayName())
                .filter(name -> !name.isBlank())
                .orElse(IPlatformHelper.super.getModDisplayName(modId));
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    @Override
    public Path getGameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    @Nullable
    public String getModVersion(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse(null);
    }
}
