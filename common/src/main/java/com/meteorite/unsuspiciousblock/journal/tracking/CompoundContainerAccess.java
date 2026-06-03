package com.meteorite.unsuspiciousblock.journal.tracking;

import net.minecraft.world.Container;

/**
 * 复合容器访问接口——通过 mixin 附加到大型箱子（DoubleBlockEntity）等复合容器。
 * 用于获取容器的前半部分和后半部分，以支持容器内容追踪。
 */
public interface CompoundContainerAccess {
    // 获取复合容器的前半部分（大箱子左/下侧）
    Container unsuspiciousblock$getFirstContainer();

    // 获取复合容器的后半部分（大箱子右/上侧）
    Container unsuspiciousblock$getSecondContainer();
}
