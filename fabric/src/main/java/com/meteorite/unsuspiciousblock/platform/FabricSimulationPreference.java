package com.meteorite.unsuspiciousblock.platform;
import net.fabricmc.loader.api.FabricLoader;
/** fabric 客户端模拟偏好的配置目录适配。 */
public final class FabricSimulationPreference extends FileSimulationPreference {
    public FabricSimulationPreference() { super(FabricLoader.getInstance().getConfigDir()); }
}

