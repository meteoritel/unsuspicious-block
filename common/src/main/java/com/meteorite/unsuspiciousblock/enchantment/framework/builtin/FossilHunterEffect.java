package com.meteorite.unsuspiciousblock.enchantment.framework.builtin;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.enchantment.framework.effect.EffectContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.effect.EnchantmentEffect;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.LootSession;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContext;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContextHolder;
import com.meteorite.unsuspiciousblock.journal.tracking.event.LootTrackingEvents;
import com.meteorite.unsuspiciousblock.journal.tracking.settlement.LootSettlementStrategies;
import com.meteorite.unsuspiciousblock.world.NaturalBoneBlockTracker;
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
 * 自然生成的判定由 {@link NaturalBoneBlockTracker} 负责——仅 chunk 首次生成时记录的位置才视为自然生成，
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
        // 仅骨块触发
        if (state == null || pos == null || !state.is(Blocks.BONE_BLOCK)) {
            return;
        }

        ServerLevel level = triggerCtx.level;
        // 先消费自然生成标记——无论是否发放奖励，被破坏的自然骨块都不应保留标记
        boolean wasNatural = NaturalBoneBlockTracker.consumeNatural(level, pos);
        // 创造模式或关闭方块掉落时不触发额外奖励
        if (triggerCtx.player.isCreative()
                || !level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS)) {
            return;
        }
        // 非自然生成则不额外掉落
        if (!wasNatural) {
            return;
        }

        // 概率未命中则不额外掉落
        if (triggerCtx.player.getRandom().nextDouble() >= EXTRA_LOOT_CHANCE) {
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
        LootTrackingContext trackingContext = LootTrackingContext.root(
                triggerCtx.player, lootTableKey.location(), LootSourceType.FOSSIL_HUNTER,
                level.getGameTime(), level.getDayTime(), pos,
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        LootSession session = new LootSession(trackingContext);
        List<ItemStack> generated;
        try (LootTrackingContextHolder.Scope ignored = LootTrackingContextHolder.open(session, trackingContext)) {
            generated = level.getServer().reloadableRegistries().getLootTable(lootTableKey)
                    .getRandomItems(lootParams, triggerCtx.player.getRandom());
        }
        LootTrackingEvents.submit(session, generated, LootSettlementStrategies.immediate());
        return generated;
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
