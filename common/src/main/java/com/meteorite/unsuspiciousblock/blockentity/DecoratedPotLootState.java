package com.meteorite.unsuspiciousblock.blockentity;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 陶罐专用的待破坏战利品状态。
 * 陶罐只保留战利品表 ID 与解析归属玩家，破坏时直接提交最终物品，不参与普通容器的延迟追踪。
 */
public interface DecoratedPotLootState {
    // 返回右键提前解析后仍需保留到破坏阶段的 loot table ID
    @Nullable
    ResourceLocation unsuspiciousblock$getDecoratedPotLootTableName();

    // 更新待破坏的 loot table ID
    void unsuspiciousblock$setDecoratedPotLootTableName(@Nullable ResourceLocation tableId);

    // 返回解析陶罐战利品时关联的玩家 UUID
    @Nullable
    UUID unsuspiciousblock$getDecoratedPotPlayerUuid();

    // 更新解析陶罐战利品时关联的玩家 UUID
    void unsuspiciousblock$setDecoratedPotPlayerUuid(@Nullable UUID playerUuid);

    // 破坏结算完成后清空最小持久状态
    default void unsuspiciousblock$clearDecoratedPotLootState() {
        this.unsuspiciousblock$setDecoratedPotLootTableName(null);
        this.unsuspiciousblock$setDecoratedPotPlayerUuid(null);
    }
}
