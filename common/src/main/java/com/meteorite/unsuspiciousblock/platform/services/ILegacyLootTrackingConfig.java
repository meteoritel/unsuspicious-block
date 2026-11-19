package com.meteorite.unsuspiciousblock.platform.services;

import com.meteorite.unsuspiciousblock.core.loottable.LegacyLootTrackingConfig;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

/** 平台旧格式的只读迁移入口。 */
public interface ILegacyLootTrackingConfig {
    /** 从该世界及其 Loader 回退来源读取旧配置；缺少旧来源时返回空。 */
    @Nullable
    LegacyLootTrackingConfig readLegacy(Path worldConfigDirectory) throws IOException;
}
