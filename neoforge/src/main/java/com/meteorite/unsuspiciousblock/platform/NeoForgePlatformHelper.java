package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.core.loottable.injection.DeclaredLootInjection;
import com.google.gson.JsonParser;
import com.meteorite.unsuspiciousblock.platform.loot.LootInjectionDescriptions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import com.meteorite.unsuspiciousblock.platform.services.IPlatformHelper;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/** NeoForge 平台实现 */
public class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public List<DeclaredLootInjection> describeLootInjections(
            ResourceLocation table) {
        return LootInjectionDescriptions.observed(table);
    }

    @Override
    public List<String> lootInjectionHashInputs(ResourceManager resources)
            throws IOException {
        var input = new ArrayList<String>();
        var entries = resources.listResources("loot_modifiers", location -> location.getPath().endsWith(".json"));
        for (var location : entries.keySet().stream().sorted(Comparator.comparing(Object::toString)).toList()) {
            for (var resource : resources.getResourceStack(location)) {
                try (var reader = resource.openAsReader()) {
                    input.add(location + "|" + resource.sourcePackId() + "|"
                            + JsonParser.parseReader(reader).toString());
                }
            }
        }
        return List.copyOf(input);
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
    public Path getGameDir() {
        return FMLPaths.GAMEDIR.get();
    }

}
