package com.meteorite.unsuspiciousblock.core.cat;

import com.meteorite.unsuspiciousblock.core.inventory.CarriedItems;
import com.meteorite.unsuspiciousblock.core.item.HandOfCatItem;
import com.meteorite.unsuspiciousblock.core.item.ModItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** 猫之手本人携带查询与服务端首次绑定，共用个人携带扫描。 */
public final class HandOfCatBinding {
    private HandOfCatBinding() {}

    /** 客户端也可调用的只读查询，不修改未绑定信物。 */
    public static boolean hasOwned(Player player) {
        return ModItems.HAND_OF_CAT != null && CarriedItems.containsMatching(player,
                stack -> stack.is(ModItems.HAND_OF_CAT) && HandOfCatItem.isBoundTo(stack, player.getUUID()));
    }

    /** 已有本人信物时不再绑定；否则仅修改首个未绑定信物并保留容器写回语义。 */
    public static boolean bindFirstIfNeeded(ServerPlayer player) {
        if (ModItems.HAND_OF_CAT == null || hasOwned(player)) return false;
        return CarriedItems.mutateFirst(player,
                stack -> stack.is(ModItems.HAND_OF_CAT) && !HandOfCatItem.isBound(stack),
                stack -> HandOfCatItem.bindTo(stack, player));
    }
}
