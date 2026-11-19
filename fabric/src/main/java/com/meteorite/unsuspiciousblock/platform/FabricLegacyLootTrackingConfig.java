package com.meteorite.unsuspiciousblock.platform;

import com.google.gson.JsonParseException;
import com.meteorite.unsuspiciousblock.core.loottable.LegacyLootTrackingConfig;
import com.meteorite.unsuspiciousblock.core.loottable.LootTrackingConfigFile;
import com.meteorite.unsuspiciousblock.platform.services.ILegacyLootTrackingConfig;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

/** 导入 Fabric 世界 JSON，缺失时读取旧全局设置。 */
public final class FabricLegacyLootTrackingConfig implements ILegacyLootTrackingConfig {
    @Override
    public @Nullable LegacyLootTrackingConfig readLegacy(Path worldConfigDirectory) throws IOException {
        LegacyLootTrackingConfig world = read(worldConfigDirectory.resolve("unsuspiciousblock.json"));
        if (world != null) return world;
        LegacyLootTrackingConfig global = read(FabricSpiritCatConfig.globalFile());
        return global == null ? null : new LegacyLootTrackingConfig(
                global.selectors(), List.of(), null, global.logLimit(), global.timeoutTicks());
    }

    private static @Nullable LegacyLootTrackingConfig read(Path path) throws IOException {
        try {
            return LootTrackingConfigFile.readFabricLegacy(path);
        } catch (NoSuchFileException exception) {
            return null;
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            throw new IOException("Invalid legacy loot config " + path, exception);
        }
    }
}
