package com.meteorite.unsuspiciousblock.enchantment.framework.builtin;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.enchantment.framework.effect.EffectContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.effect.EnchantmentEffect;
import com.meteorite.unsuspiciousblock.world.PlacedBoneBlockTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 化石猎手附魔效果：玩家破坏自然生成的骨块时，有概率额外 roll 一份对应维度的骨块掉落表。
 * <p>
 * 玩家放置的骨块（经 {@link PlacedBoneBlockTracker} 标记）不触发额外奖励，仅消费放置标记。
 */
public final class FossilHunterEffect implements EnchantmentEffect {
    private static final double EXTRA_LOOT_CHANCE = 0.50D;

    public static final ResourceKey<LootTable> OVERWORLD_BONE_BLOCK_LOOT_TABLE =
            lootTableKey("gameplay/fossil_hunter/overworld_bone_block");
    public static final ResourceKey<LootTable> NETHER_BONE_BLOCK_LOOT_TABLE =
            lootTableKey("gameplay/fossil_hunter/nether_bone_block");

    @Override
    public void apply(EffectContext ctx) {
        BlockState state = ctx.triggerContext().blockState;
        BlockPos pos = ctx.pos();
        // 仅骨块触发
        if (state == null || pos == null || !state.is(Blocks.BONE_BLOCK)) {
            return;
        }

        ServerLevel level = ctx.level();
        // 玩家放置的骨块只清除标记，不触发额外奖励；创造模式或关闭方块掉落时也不触发
        if (PlacedBoneBlockTracker.consumePlaced(level, pos)
                || ctx.player().isCreative()
                || !level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS)) {
            return;
        }

        // 概率未命中则不额外掉落
        if (ctx.player().getRandom().nextDouble() >= EXTRA_LOOT_CHANCE) {
            return;
        }

        List<ItemStack> extra = rollExtraLoot(ctx, pos, state);
        for (ItemStack drop : extra) {
            if (!drop.isEmpty()) {
                net.minecraft.world.level.block.Block.popResource(level, pos, drop);
            }
        }
    }

    // 按当前维度解析骨块掉落表并 roll 一次
    private List<ItemStack> rollExtraLoot(EffectContext ctx, BlockPos pos, BlockState state) {
        ServerLevel level = ctx.level();
        LootParams lootParams = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.BLOCK_STATE, state)
                .withParameter(LootContextParams.TOOL, ctx.enchantedItem())
                .withOptionalParameter(LootContextParams.THIS_ENTITY, ctx.player())
                .withLuck(ctx.player().getLuck())
                .create(LootContextParamSets.BLOCK);
        return level.getServer().reloadableRegistries().getLootTable(resolveLootTable(level))
                .getRandomItems(lootParams, ctx.player().getRandom());
    }

    // 主世界用主世界表，其它维度用下界表
    private ResourceKey<LootTable> resolveLootTable(ServerLevel level) {
        return Level.NETHER.equals(level.dimension())
                ? NETHER_BONE_BLOCK_LOOT_TABLE
                : OVERWORLD_BONE_BLOCK_LOOT_TABLE;
    }

    private static ResourceKey<LootTable> lootTableKey(String path) {
        return ResourceKey.create(Registries.LOOT_TABLE,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, path));
    }
}
