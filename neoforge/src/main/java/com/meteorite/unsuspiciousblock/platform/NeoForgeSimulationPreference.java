package com.meteorite.unsuspiciousblock.platform;
import net.neoforged.fml.loading.FMLPaths;
/** neoforge 客户端模拟偏好的配置目录适配。 */
public final class NeoForgeSimulationPreference extends FileSimulationPreference {
    public NeoForgeSimulationPreference() { super(FMLPaths.CONFIGDIR.get()); }
}

