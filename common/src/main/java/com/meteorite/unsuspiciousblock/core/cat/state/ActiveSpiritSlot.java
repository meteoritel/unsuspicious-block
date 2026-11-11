package com.meteorite.unsuspiciousblock.core.cat.state;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** 单个灵体身份槽，世界存活性与生成成功判定由调用方负责。 */
public final class ActiveSpiritSlot {
    private @Nullable UUID occupant;

    public @Nullable UUID find() {
        return occupant;
    }

    public void occupy(UUID entityUuid) {
        occupant = entityUuid;
    }

    /** 旧实体移除时只释放自己的占用。 */
    public boolean release(UUID entityUuid) {
        if (!entityUuid.equals(occupant)) return false;
        occupant = null;
        return true;
    }
}
