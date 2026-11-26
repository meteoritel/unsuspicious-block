package com.meteorite.unsuspiciousblock.core.pan;

import com.meteorite.unsuspiciousblock.core.entity.ShimmerEntity;
import com.meteorite.unsuspiciousblock.core.item.PanProfile;
import com.meteorite.unsuspiciousblock.core.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.core.journal.tracking.LootCapture;
import com.meteorite.unsuspiciousblock.core.journal.tracking.settlement.LootSettlementTarget;
import com.meteorite.unsuspiciousblock.core.pan.variant.ShimmerVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/***
 * 淘洗产出服务——按目标变体抽取对应的战利品表，并把产出交给玩家与考古笔记。
 * <p>
 * 产出表由变体决定而不是由工具决定：三把盘共用同一张表，产出分化完全走原版幸运机制，
 * 幸运值由玩家自身幸运与工具在目标变体上的加成叠加而成。
 * 产出从液面向上、朝玩家抛出；考古笔记按本次淘洗产出立即结算，与钓鱼一致。
 */
public final class PanningLootService {
    private static final int LOOT_FLIGHT_TICKS = 10;
    private static final double ITEM_AIR_DRAG = 0.98D;
    private static final double ITEM_GRAVITY = 0.04D;
    private static final double FLIGHT_DRAG_SUM = (1.0D - Math.pow(ITEM_AIR_DRAG, LOOT_FLIGHT_TICKS))
            / (1.0D - ITEM_AIR_DRAG);
    // ItemEntity 每刻先受重力、再移动并衰减速度，竖直初速度按此顺序补偿。
    private static final double GRAVITY_COMPENSATION = ITEM_GRAVITY
            * (LOOT_FLIGHT_TICKS / FLIGHT_DRAG_SUM - ITEM_AIR_DRAG) / (1.0D - ITEM_AIR_DRAG);

    private PanningLootService() {
    }

    /** 完成一次已通过会话校验的淘洗，以成功投放的掉落结算笔记。 */
    public static void harvest(ServerLevel level, ServerPlayer player, PanningSession session, PanProfile profile) {
        ShimmerEntity target = session.target();
        ShimmerVariant variant = target.getVariant();
        LootTable table = level.getServer().reloadableRegistries().lookup().lookupOrThrow(Registries.LOOT_TABLE)
                .get(variant.lootTable()).map(Holder::value).orElse(null);
        if (table == null) {
            player.displayClientMessage(Component.translatable("message.unsuspiciousblock.panning.missing_loot_table",
                    variant.lootTable().location().toString()), true);
            return;
        }
        BlockPos surfacePos = target.getAnchorPos();
        ItemStack panStack = session.tool();
        BlockState surface = level.getBlockState(surfacePos);
        LootParams lootParams = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(surfacePos))
                .withParameter(LootContextParams.BLOCK_STATE, surface)
                .withParameter(LootContextParams.TOOL, panStack)
                .withOptionalParameter(LootContextParams.THIS_ENTITY, player)
                .withLuck(player.getLuck() + (float) profile.luckFor(variant.id()))
                .create(LootContextParamSets.BLOCK);

        try (var capture = LootCapture.begin(new LootCapture.Source(
                player, variant.lootTable().location(), LootSourceType.PANNING, level, surfacePos,
                BuiltInRegistries.BLOCK.getKey(surface.getBlock())))) {
            List<ItemStack> generated = capture.roll(() -> table.getRandomItems(lootParams, player.getRandom()));
            if (!target.consumePanUse()) {
                return;
            }
            capture.complete(spawnDrops(level, player, surfacePos, generated), LootSettlementTarget.IMMEDIATE);
        }

        ShimmerSpawnService.tryRegenerateAfterHarvest(level, target, profile.regenerationChance().getAsDouble());
        panStack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(session.hand()));
    }

    private static List<ItemStack> spawnDrops(ServerLevel level, ServerPlayer player, BlockPos surfacePos,
                                              List<ItemStack> generated) {

        Vec3 spawnPosition = Vec3.atBottomCenterOf(surfacePos.above());
        Vec3 toPlayer = player.position().subtract(spawnPosition);
        Vec3 launchVelocity = toPlayer.scale(1.0D / FLIGHT_DRAG_SUM)
                .add(0.0D, GRAVITY_COMPENSATION, 0.0D);

        List<ItemStack> delivered = new ArrayList<>(generated.size());
        for (ItemStack drop : generated) {
            if (drop.isEmpty()) {
                continue;
            }
            ItemEntity itemEntity = new ItemEntity(level,
                    spawnPosition.x, spawnPosition.y, spawnPosition.z, drop.copy());
            itemEntity.setDeltaMovement(launchVelocity);
            // 近距产出会很快进入拾取范围，默认延迟会让物品穿过玩家。
            itemEntity.setNoPickUpDelay();
            if (level.addFreshEntity(itemEntity)) {
                delivered.add(itemEntity.getItem());
            }
        }
        return delivered;
    }
}
