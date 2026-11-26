package com.meteorite.unsuspiciousblock.core.pan;

import org.jetbrains.annotations.Nullable;

/** 玩家持有的瞬态淘洗会话，随原版玩家实体生命周期释放。 */
public interface PanningSessionHolder {
    @Nullable PanningSession unsuspiciousblock$getPanningSession();

    void unsuspiciousblock$setPanningSession(@Nullable PanningSession session);
}
