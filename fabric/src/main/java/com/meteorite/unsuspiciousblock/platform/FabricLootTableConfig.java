package com.meteorite.unsuspiciousblock.platform;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.platform.services.ILootTableConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Fabric 战利品表配置——使用 GSON / JSON */
public class FabricLootTableConfig implements ILootTableConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_STRING_TYPE = new TypeToken<List<String>>() {}.getType();
    private static final String CONFIG_FILE_NAME = "unsuspiciousblock.json";
    private static final List<String> DEFAULT_PREFIXES = List.of("archaeology/", "archeology/");

    private final List<String> prefixes;

    public FabricLootTableConfig() {
        this.prefixes = new ArrayList<>(loadPrefixes());
    }

    @Override
    public List<String> getArchaeologyPathPrefixes() {
        return this.prefixes;
    }

    private static List<String> loadPrefixes() {
        Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            saveDefaults(configPath);
            return DEFAULT_PREFIXES;
        }

        try (Reader reader = Files.newBufferedReader(configPath)) {
            ConfigData data = GSON.fromJson(reader, ConfigData.class);
            if (data != null && data.archaeology_path_prefixes != null && !data.archaeology_path_prefixes.isEmpty()) {
                return data.archaeology_path_prefixes;
            }
        } catch (IOException e) {
            Constants.LOG.warn("Failed to read loot table config from {}, using defaults.", configPath, e);
        }
        return DEFAULT_PREFIXES;
    }

    private static void saveDefaults(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                GSON.toJson(new ConfigData(DEFAULT_PREFIXES), writer);
            }
            Constants.LOG.info("Created default loot table config at {}", configPath);
        } catch (IOException e) {
            Constants.LOG.warn("Failed to create default loot table config at {}", configPath, e);
        }
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
    }

    private record ConfigData(List<String> archaeology_path_prefixes) {
    }
}
