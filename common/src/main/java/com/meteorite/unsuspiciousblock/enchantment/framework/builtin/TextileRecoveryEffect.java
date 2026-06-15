package com.meteorite.unsuspiciousblock.enchantment.framework.builtin;

import com.meteorite.unsuspiciousblock.enchantment.framework.effect.EffectContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.effect.EnchantmentEffect;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 织物采集附魔效果：剪羊毛额外掉线 + 剪刀剪树叶额外掉落树叶破坏后的掉落物。
 * <p>
 * 同时注册到 {@link TriggerType#ENTITY_SHEAR} 与 {@link TriggerType#TOOL_MINE_BLOCK}，
 * 根据上下文中是否存在绵羊实体或树叶方块自动分流。
 */
public final class TextileRecoveryEffect implements EnchantmentEffect {
    private static final double EXTRA_STRING_CHANCE = 0.15D;
    private static final int EXTRA_STRING_MIN = 1;
    private static final int EXTRA_STRING_MAX = 2;
    private static final double EXTRA_LEAF_DROP_CHANCE = 0.05D;

    @Override
    public void apply(EffectContext<?> ctx) {
        var triggerCtx = ctx.triggerContext();
        // 剪羊毛：targetEntity 为 Sheep
        if (triggerCtx.targetEntity instanceof Sheep sheep) {
            handleSheepShear(triggerCtx, sheep);
            return;
        }

        // 剪树叶：blockState 为树叶
        BlockState state = triggerCtx.blockState;
        if (state != null && state.is(BlockTags.LEAVES)) {
            handleLeavesDestroy(triggerCtx, state);
        }
    }

    // 剪羊毛额外掉线
    private void handleSheepShear(TriggerContext triggerCtx, Sheep sheep) {
        if (triggerCtx.player.getRandom().nextDouble() >= EXTRA_STRING_CHANCE) {
            return;
        }
        int count = EXTRA_STRING_MIN + triggerCtx.player.getRandom().nextInt(EXTRA_STRING_MAX - EXTRA_STRING_MIN + 1);
        Block.popResource(triggerCtx.level, sheep.blockPosition(), new ItemStack(Items.STRING, count));
    }

    // 剪树叶额外掉落树叶破坏物品
    private void handleLeavesDestroy(TriggerContext triggerCtx, BlockState state) {
        ServerLevel level = triggerCtx.level;
        if (triggerCtx.player.isCreative()
                || !level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)
                || level.getRandom().nextDouble() >= EXTRA_LEAF_DROP_CHANCE) {
            return;
        }

        BlockPos pos = triggerCtx.pos;
        if (pos == null || state == null) {
            return;
        }

        for (ItemStack drop : Block.getDrops(state, level, pos, null, triggerCtx.player, ItemStack.EMPTY)) {
            if (!drop.isEmpty()) {
                Block.popResource(level, pos, drop);
            }
        }
    }
}
