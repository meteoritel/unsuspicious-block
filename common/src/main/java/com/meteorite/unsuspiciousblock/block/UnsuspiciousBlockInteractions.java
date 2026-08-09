package com.meteorite.unsuspiciousblock.block;

import com.meteorite.unsuspiciousblock.item.ArchaeologicalShovelItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BrushItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 不可疑方块的刷子与考古铲交互入口，供两种平台事件共同调用。
 */
public final class UnsuspiciousBlockInteractions {
    private UnsuspiciousBlockInteractions() {
    }

    // 判断物品是否为原版刷子或继承 BrushItem 的自定义刷子
    public static boolean isBrush(ItemStack tool) {
        return tool.getItem() instanceof BrushItem;
    }

    // 命中目标工具与方块时立即破坏，并在服务端消耗一点工具耐久
    public static boolean tryBreak(Level level, BlockPos pos, Player player, ItemStack tool) {
        if (!(level.getBlockState(pos).getBlock() instanceof UnsuspiciousBlock)
                || (!isBrush(tool) && !(tool.getItem() instanceof ArchaeologicalShovelItem))) {
            return false;
        }

        if (level instanceof ServerLevel serverLevel) {
            boolean destroyed = serverLevel.destroyBlock(pos, true, player);
            if (destroyed && !player.isCreative()) {
                ServerPlayer serverPlayer = player instanceof ServerPlayer sp ? sp : null;
                tool.hurtAndBreak(1, serverLevel, serverPlayer, item -> {
                });
            }
        }
        return true;
    }
}
