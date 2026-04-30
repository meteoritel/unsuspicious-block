package com.meteorite.unsuspiciousblock.platform.services;

/** 平台抽象接口——提供平台名、Mod 加载检测、开发环境判断 */
public interface IPlatformHelper {

    String getPlatformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }
}
