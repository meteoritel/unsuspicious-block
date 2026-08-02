package com.meteorite.unsuspiciousblock.block;

import com.meteorite.unsuspiciousblock.item.ArchaeologicalShovelItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * 不可疑方块的刷子与考古铲交互入口，供两种平台事件共同调用。
 */
public final class UnsuspiciousBlockInteractions {
    private UnsuspiciousBlockInteractions() {
    }

    // 命中目标工具与方块时立即破坏，并在服务端消耗一点工具耐久
    public static boolean tryBreak(Level level, BlockPos pos, Player player, ItemStack tool) {
        if (!(level.getBlockState(pos).getBlock() instanceof UnsuspiciousBlock)
                || (!tool.is(Items.BRUSH) && !(tool.getItem() instanceof ArchaeologicalShovelItem))) {
            return false;
        }

        if (level instanceof ServerLevel serverLevel) {
            boolean destroyed = serverLevel.destroyBlock(pos, false, player);
            if (destroyed && !player.isCreative()) {
                ServerPlayer serverPlayer = player instanceof ServerPlayer sp ? sp : null;
                tool.hurtAndBreak(1, serverLevel, serverPlayer, item -> {
                });
            }
        }
        return true;
    }
}
