package com.meteorite.unsuspiciousblock.mixin.container;

import com.meteorite.unsuspiciousblock.journal.tracking.CompoundContainerAccess;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 为组合容器暴露左右子容器访问入口。
 */
@Mixin(CompoundContainer.class)
public abstract class CompoundContainerMixin implements CompoundContainerAccess {
    @Shadow
    @Final
    private Container container1;

    @Shadow
    @Final
    private Container container2;

    // 返回组合容器中的第一个子容器
    @Override
    public Container unsuspiciousblock$getFirstContainer() {
        return this.container1;
    }

    // 返回组合容器中的第二个子容器
    @Override
    public Container unsuspiciousblock$getSecondContainer() {
        return this.container2;
    }
}
