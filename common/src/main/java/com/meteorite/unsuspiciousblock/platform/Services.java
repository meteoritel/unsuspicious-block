package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.platform.services.ILootTableConfig;
import com.meteorite.unsuspiciousblock.platform.services.INetworkHelper;
import com.meteorite.unsuspiciousblock.platform.services.IPlatformHelper;

import java.util.ServiceLoader;

/** 平台服务加载器——通过 ServiceLoader 在运行时发现平台实现 */
public class Services {

    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);
    public static final INetworkHelper NETWORK = load(INetworkHelper.class);
    public static final ILootTableConfig LOOT_TABLE_CONFIG = load(ILootTableConfig.class);

    public static <T> T load(Class<T> clazz) {
        final T loadedService = ServiceLoader.load(clazz)
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
        return loadedService;
    }
}
