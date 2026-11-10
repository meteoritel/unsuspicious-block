package com.meteorite.unsuspiciousblock.client.ui.support;

import java.util.function.LongSupplier;

/** 限制同一连接上的全量恢复请求频率。 */
final class ResyncPolicy {
    static final long COOLDOWN_MS = 2000L;

    private final LongSupplier clock;
    private long lastRequestMs;
    private boolean requested;

    ResyncPolicy(LongSupplier clock) {
        this.clock = clock;
    }

    boolean tryRequest() {
        long now = clock.getAsLong();
        if (requested && now - lastRequestMs < COOLDOWN_MS) {
            return false;
        }
        lastRequestMs = now;
        requested = true;
        return true;
    }
}
