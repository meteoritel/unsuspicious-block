package com.meteorite.unsuspiciousblock.core.blockentity;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** 扫描身份的共同流程；宿主负责清除附属状态和同步。 */
public interface ScanStateHost {
    ScanIdentity unsuspiciousblock$scanIdentity();

    void unsuspiciousblock$markBlockEntityChanged();

    default void unsuspiciousblock$markScanned(UUID scannerUuid) {
        if (unsuspiciousblock$scanIdentity().mark(scannerUuid)) {
            unsuspiciousblock$markBlockEntityChanged();
        }
    }

    default void unsuspiciousblock$clearScanned() {
        if (unsuspiciousblock$scanIdentity().clear()) {
            unsuspiciousblock$onScanCleared();
            unsuspiciousblock$markBlockEntityChanged();
        }
    }

    /** 仅在身份实际清除后调用，封存宿主没有附属待定状态。 */
    default void unsuspiciousblock$onScanCleared() {
    }

    default boolean unsuspiciousblock$isScanned() {
        return unsuspiciousblock$scanIdentity().scanned();
    }

    default @Nullable UUID unsuspiciousblock$getScannerUuid() {
        return unsuspiciousblock$scanIdentity().scanner();
    }

    default boolean unsuspiciousblock$isScanner(@Nullable UUID uuid) {
        return unsuspiciousblock$isScanned() && uuid != null
                && uuid.equals(unsuspiciousblock$getScannerUuid());
    }
}
