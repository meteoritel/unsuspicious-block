package com.meteorite.unsuspiciousblock.mixin.container;

import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.journal.tracking.ContainerLootTracking;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;


/**
 * 为普通随机容器保存运行时追踪状态。
 */
@Mixin(RandomizableContainerBlockEntity.class)
public abstract class RandomizableContainerBlockEntityMixin implements TrackedContainerLootState {
    @Unique
    private final ContainerLootTracking unsuspiciousblock$tracking =
            new ContainerLootTracking(() -> ((BlockEntity) (Object) this).setChanged());

    @Override
    public ContainerLootTracking unsuspiciousblock$tracking() {
        return this.unsuspiciousblock$tracking;
    }

}
