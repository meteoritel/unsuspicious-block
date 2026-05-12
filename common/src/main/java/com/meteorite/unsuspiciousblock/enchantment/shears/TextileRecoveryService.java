package com.meteorite.unsuspiciousblock.enchantment.shears;

import com.meteorite.unsuspiciousblock.enchantment.ModEnchantments;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** 织物采集附魔判定服务 */
public final class TextileRecoveryService {
    private static final double EXTRA_STRING_CHANCE = 0.15D;
    private static final int EXTRA_STRING_MIN = 1;
    private static final int EXTRA_STRING_MAX = 2;
    private static final double EXTRA_LEAF_DROP_BASE_CHANCE = 0.05D;
    private static final double EXTRA_LEAF_DROP_FORTUNE_CHANCE = 0.05D;

    private TextileRecoveryService() {
    }

    public static void onSheepSheared(ServerLevel level, Sheep sheep, ItemStack shears) {
        if (!isTextileRecoveryShears(level, shears) || level.getRandom().nextDouble() >= EXTRA_STRING_CHANCE) {
            return;
        }

        int count = EXTRA_STRING_MIN + level.getRandom().nextInt(EXTRA_STRING_MAX - EXTRA_STRING_MIN + 1);
        Block.popResource(level, sheep.blockPosition(), new ItemStack(Items.STRING, count));
    }

    public static void onLeavesDestroyed(Level level, Player player, BlockPos pos,
                                         BlockState state, ItemStack tool) {
        if (!(level instanceof ServerLevel serverLevel)
                || player.isCreative()
                || !serverLevel.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)
                || !state.is(BlockTags.LEAVES)
                || !isTextileRecoveryShears(serverLevel, tool)
                || !shouldDropExtraLeafLoot(serverLevel, tool)) {
            return;
        }

        // 这里显式使用空工具，确保额外掉落按“非剪刀、非丝绸之触”语义解析。
        for (ItemStack drop : Block.getDrops(state, serverLevel, pos, null, player, ItemStack.EMPTY)) {
            if (!drop.isEmpty()) {
                Block.popResource(serverLevel, pos, drop);
            }
        }
    }

    private static boolean shouldDropExtraLeafLoot(ServerLevel level, ItemStack tool) {
        int fortuneLevel = ModEnchantments.getEnchantmentLevel(level.registryAccess(), tool, Enchantments.FORTUNE);
        double chance = Math.min(1.0D,
                EXTRA_LEAF_DROP_BASE_CHANCE + fortuneLevel * EXTRA_LEAF_DROP_FORTUNE_CHANCE);
        return level.getRandom().nextDouble() < chance;
    }

    private static boolean isTextileRecoveryShears(ServerLevel level, ItemStack tool) {
        return tool.is(Items.SHEARS) && ModEnchantments.getEnchantmentLevel(level.registryAccess(), tool, ModEnchantments.TEXTILE_RECOVERY) > 0;
    }

}
