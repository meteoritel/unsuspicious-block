package com.meteorite.unsuspiciousblock;

import com.meteorite.unsuspiciousblock.platform.Services;

/** Common 模块统一入口，由各平台模块调用 */
public class UnsuspiciousBlockCommon {
    public static void init() {
        Constants.LOG.info("UnsuspiciousBlock common init on {}", Services.PLATFORM.getPlatformName());
    }
}
