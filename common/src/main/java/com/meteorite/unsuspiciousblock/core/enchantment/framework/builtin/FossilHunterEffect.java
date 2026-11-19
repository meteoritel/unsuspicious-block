package com.meteorite.unsuspiciousblock.core.enchantment.framework.builtin;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.enchantment.framework.effect.EffectContext;
import com.meteorite.unsuspiciousblock.core.enchantment.framework.effect.EnchantmentEffect;
import com.meteorite.unsuspiciousblock.core.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.core.journal.tracking.LootCapture;
import com.meteorite.unsuspiciousblock.core.journal.tracking.settlement.LootSettlementTarget;
import com.meteorite.unsuspiciousblock.core.world.NaturalBoneBlockTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
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
 * 自然生成的判定由 {@link NaturalBoneBlockTracker} 负责——使用移除前的区块标记，
 * 玩家放置或其它模组机器放置的骨块均不触发额外奖励。
 */
public final class FossilHunterEffect implements EnchantmentEffect {
    private static final double EXTRA_LOOT_CHANCE = 0.50D;

    public static final ResourceKey<LootTable> OVERWORLD_BONE_BLOCK_LOOT_TABLE =
            lootTableKey("gameplay/fossil_hunter/overworld_bone_block");
    public static final ResourceKey<LootTable> NETHER_BONE_BLOCK_LOOT_TABLE =
            lootTableKey("gameplay/fossil_hunter/nether_bone_block");

    @Override
    public void apply(EffectContext<?> ctx) {
        var triggerCtx = ctx.triggerContext();
        BlockState state = triggerCtx.blockState;
        BlockPos pos = triggerCtx.pos;
        ServerLevel level = triggerCtx.level;
        if (!level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) {
            return;
        }

        // 概率未命中则不额外掉落
        if (triggerCtx.player.getRandom().nextDouble() >= EXTRA_LOOT_CHANCE) {
            return;
        }

        List<ItemStack> extra = rollExtraLoot(ctx, pos, state);
        for (ItemStack drop : extra) {
            if (!drop.isEmpty()) {
                Block.popResource(level, pos, drop);
            }
        }
    }

    // 按当前维度解析骨块掉落表并 roll 一次
    private List<ItemStack> rollExtraLoot(EffectContext<?> ctx, BlockPos pos, BlockState state) {
        var triggerCtx = ctx.triggerContext();
        ServerLevel level = triggerCtx.level;
        ResourceKey<LootTable> lootTableKey = resolveLootTable(level);
        LootParams lootParams = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.BLOCK_STATE, state)
                .withParameter(LootContextParams.TOOL, ctx.enchantedItem())
                .withOptionalParameter(LootContextParams.THIS_ENTITY, triggerCtx.player)
                .withLuck(triggerCtx.player.getLuck())
                .create(LootContextParamSets.BLOCK);
        return LootCapture.capture(new LootCapture.Source(
                triggerCtx.player, lootTableKey.location(), LootSourceType.FOSSIL_HUNTER, level, pos,
                BuiltInRegistries.BLOCK.getKey(state.getBlock())), LootSettlementTarget.IMMEDIATE,
                () -> level.getServer().reloadableRegistries().getLootTable(lootTableKey)
                        .getRandomItems(lootParams, triggerCtx.player.getRandom()));
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
