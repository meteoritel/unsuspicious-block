package com.meteorite.unsuspiciousblock.mixin.inventory;

import com.meteorite.unsuspiciousblock.inventory.PlayerPresenceStateHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.HashSet;
import java.util.Set;

/**
 * 为玩家实体附加背包存在 diff 状态（transient，不持久化）。
 * 参照 {@code PlayerCatFavorStateMixin} 模式，随 Player 对象生命周期自动清理，
 * 无需处理下线/切维度/死亡复活边界。
 */
@Mixin(Player.class)
public abstract class PlayerPresenceStateMixin implements PlayerPresenceStateHolder {

    @Unique
    private final Set<Item> unsuspiciousblock$presentTriggerItems = new HashSet<>();

    @Override
    public Set<Item> unsuspiciousblock$getPresentTriggerItems() {
        return this.unsuspiciousblock$presentTriggerItems;
    }
}
