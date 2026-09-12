package com.meteorite.unsuspiciousblock.pan;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.LootSession;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContext;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContextHolder;
import com.meteorite.unsuspiciousblock.journal.tracking.event.LootTrackingEvents;
import com.meteorite.unsuspiciousblock.journal.tracking.settlement.LootSettlementStrategies;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 淘洗产出服务——从淘洗战利品表抽取一次结果，并把产出交给玩家与考古笔记。
 * <p>
 * 产出直接进入玩家所有权（背包满则掉落在水面），因此考古笔记采用立即结算策略，
 * 与钓鱼、化石猎人等“直接到手”的来源保持一致。
 */
public final class PanningLootService {
    // 淘盘专属战利品表——按河流水域产出原版物品
    public static final ResourceKey<LootTable> RIVER_PANNING_LOOT_TABLE = ResourceKey.create(
            Registries.LOOT_TABLE,
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "gameplay/panning/river"));

    private PanningLootService() {
    }

    // 抽取一次淘洗产出并结算给玩家
    public static void grantPanningLoot(ServerLevel level, ServerPlayer player, BlockPos waterPos, ItemStack panStack) {
        LootParams lootParams = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(waterPos))
                .withParameter(LootContextParams.BLOCK_STATE, level.getBlockState(waterPos))
                .withParameter(LootContextParams.TOOL, panStack)
                .withOptionalParameter(LootContextParams.THIS_ENTITY, player)
                .withLuck(player.getLuck())
                .create(LootContextParamSets.BLOCK);

        LootTrackingContext trackingContext = LootTrackingContext.root(
                player, RIVER_PANNING_LOOT_TABLE.location(), LootSourceType.PANNING,
                level.getGameTime(), level.getDayTime(), waterPos,
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(waterPos).getBlock()));
        LootSession session = new LootSession(trackingContext);

        List<ItemStack> generated;
        try (LootTrackingContextHolder.Scope ignored = LootTrackingContextHolder.open(session, trackingContext)) {
            generated = level.getServer().reloadableRegistries().getLootTable(RIVER_PANNING_LOOT_TABLE)
                    .getRandomItems(lootParams, player.getRandom());
        }
        LootTrackingEvents.submit(session, generated, LootSettlementStrategies.immediate());

        for (ItemStack drop : generated) {
            if (drop.isEmpty()) {
                continue;
            }
            // inventory.add() 成功时会将 stack.count 置为 0，需在此之前保存副本用于掉落
            ItemStack dropCopy = drop.copy();
            if (!player.getInventory().add(drop)) {
                ItemEntity itemEntity = new ItemEntity(level,
                        waterPos.getX() + 0.5D, waterPos.getY() + 1.0D, waterPos.getZ() + 0.5D, dropCopy);
                itemEntity.setDefaultPickUpDelay();
                level.addFreshEntity(itemEntity);
            }
        }
    }
}
