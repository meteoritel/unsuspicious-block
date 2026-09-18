package com.meteorite.unsuspiciousblock.pan;

import com.meteorite.unsuspiciousblock.item.PanItem;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.LootSession;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContext;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContextHolder;
import com.meteorite.unsuspiciousblock.journal.tracking.event.LootTrackingEvents;
import com.meteorite.unsuspiciousblock.journal.tracking.settlement.LootSettlementStrategies;
import com.meteorite.unsuspiciousblock.pan.variant.ShimmerVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/***
 * 淘洗产出服务——按目标变体抽取对应的战利品表，并把产出交给玩家与考古笔记。
 * <p>
 * 产出表由变体决定而不是由工具决定：三把盘共用同一张表，产出分化完全走原版幸运机制，
 * 幸运值由玩家自身幸运与工具在目标变体上的加成叠加而成。
 * 产出从液面向上、朝玩家抛出；考古笔记按本次淘洗产出立即结算，与钓鱼一致。
 */
public final class PanningLootService {
    private PanningLootService() {
    }

    // 抽取一次淘洗产出并结算给玩家
    public static void grantPanningLoot(ServerLevel level, ServerPlayer player, BlockPos surfacePos,
                                        ShimmerVariant variant, ItemStack panStack) {
        LootParams lootParams = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(surfacePos))
                .withParameter(LootContextParams.BLOCK_STATE, level.getBlockState(surfacePos))
                .withParameter(LootContextParams.TOOL, panStack)
                .withOptionalParameter(LootContextParams.THIS_ENTITY, player)
                .withLuck(player.getLuck() + (float) toolLuck(panStack, variant))
                .create(LootContextParamSets.BLOCK);

        LootTrackingContext trackingContext = LootTrackingContext.root(
                player, variant.lootTable().location(), LootSourceType.PANNING,
                level.getGameTime(), level.getDayTime(), surfacePos,
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(surfacePos).getBlock()));
        LootSession session = new LootSession(trackingContext);

        List<ItemStack> generated;
        try (LootTrackingContextHolder.Scope ignored = LootTrackingContextHolder.open(session, trackingContext)) {
            generated = level.getServer().reloadableRegistries().getLootTable(variant.lootTable())
                    .getRandomItems(lootParams, player.getRandom());
        }
        LootTrackingEvents.submit(session, generated, LootSettlementStrategies.immediate());

        for (ItemStack drop : generated) {
            if (drop.isEmpty()) {
                continue;
            }
            double x = surfacePos.getX() + 0.5D;
            double z = surfacePos.getZ() + 0.5D;
            Vec3 towardPlayer = new Vec3(player.getX() - x, 0.0D, player.getZ() - z).normalize();
            ItemEntity itemEntity = new ItemEntity(level, x, surfacePos.getY() + 1.0D, z, drop.copy());
            // 水平速度固定，避免玩家距离较远时抛射过快；玩家恰在正上方时只向上抛。
            itemEntity.setDeltaMovement(towardPlayer.scale(0.3D).add(0.0D, 0.3D, 0.0D));
            itemEntity.setDefaultPickUpDelay();
            level.addFreshEntity(itemEntity);
        }
    }

    // 工具在目标变体上的幸运加成；非淘盘物品不提供加成
    private static double toolLuck(ItemStack panStack, ShimmerVariant variant) {
        return panStack.getItem() instanceof PanItem pan ? pan.profile().luckFor(variant.id()) : 0.0D;
    }
}
