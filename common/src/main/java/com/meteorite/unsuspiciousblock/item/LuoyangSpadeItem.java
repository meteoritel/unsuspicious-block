package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.blockentity.BrushableBlockEntityScanState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import org.jetbrains.annotations.NotNull;

/** 洛阳铲——从已扫描的可疑方块中取出战利品
 * 调试物品，只能通过指令获得
 * */
public class LuoyangSpadeItem extends Item {

    public LuoyangSpadeItem(Properties properties) {
        super(properties);
    }

    @Override
    public @NotNull InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();

        if (level.isClientSide() || player == null) {
            return InteractionResult.SUCCESS;
        }

        BlockPos pos = context.getClickedPos();
        BlockEntity be = level.getBlockEntity(pos);

        if (!(be instanceof BrushableBlockEntity brushable)) {
            player.sendSystemMessage(
                    Component.translatable("item.unsuspiciousblock.luoyang_spade.not_suspicious")
                            .withStyle(style -> style.withColor(0xFF5555))
            );
            return InteractionResult.FAIL;
        }

        if (!(brushable instanceof BrushableBlockEntityScanState scanState)) {
            return InteractionResult.FAIL;
        }

        if (!scanState.unsuspiciousblock$isScanner(player.getUUID())) {
            player.sendSystemMessage(
                    Component.translatable("item.unsuspiciousblock.luoyang_spade.not_scanned")
                            .withStyle(style -> style.withColor(0xFFAA00))
            );
            return InteractionResult.FAIL;
        }

        ItemStack lootItem = scanState.unsuspiciousblock$getItem();

        if (lootItem.isEmpty()) {
            player.sendSystemMessage(
                    Component.translatable("item.unsuspiciousblock.luoyang_spade.already_empty",
                                    pos.getX(), pos.getY(), pos.getZ())
                            .withStyle(style -> style.withColor(0xAAAAAA))
            );
            return InteractionResult.SUCCESS;
        }

        ItemStack extracted = lootItem.copy();
        scanState.unsuspiciousblock$setItem(ItemStack.EMPTY);
        brushable.setChanged();

        ResourceLocation lootTableName = scanState.unsuspiciousblock$getLootTableName();
        ExcavationLogEntry pendingEntry = scanState.unsuspiciousblock$getPendingJournalEntry();
        if (lootTableName != null && player instanceof ServerPlayer sp && pendingEntry == null) {
            long gameTime = level.getGameTime();
            long dayTime = level.getDayTime();
            ArchaeologyLootRuntimeTracker.onLootDiscovered(sp, lootTableName, extracted,
                    LootSourceType.SPADE, gameTime, dayTime);
            pendingEntry = ArchaeologyLootRuntimeTracker.createPendingEntry(
                    sp,
                    lootTableName,
                    LootSourceType.SPADE,
                    BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()),
                    pos,
                    extracted,
                    gameTime,
                    dayTime
            );
            scanState.unsuspiciousblock$setPendingJournalEntry(pendingEntry);
        }

        // inventory.add() 成功时会将 stack.count 置为 0，需在此之前保存副本用于日志记录
        ItemStack extractedForLog = extracted.copy();
        boolean given = player.getInventory().add(extracted);
        if (!given) {
            ItemEntity itemEntity = new ItemEntity(
                    level,
                    pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                    extractedForLog
            );
            itemEntity.setDefaultPickUpDelay();
            level.addFreshEntity(itemEntity);
        }

        // 无论物品是否装入背包（背包满时掉落地面），都应记录获取数据
        if (lootTableName != null && player instanceof ServerPlayer sp) {
            long gameTime = level.getGameTime();
            long dayTime = level.getDayTime();
            ArchaeologyLootRuntimeTracker.applyPendingLoot(sp, lootTableName,
                    scanState.unsuspiciousblock$getPendingJournalEntry(), extractedForLog, gameTime, dayTime);
        }

        scanState.unsuspiciousblock$clearScanned();
        player.swing(context.getHand());
        Constants.LOG.debug("洛阳铲从 {} 处取出了 {} ×{}",
                pos, extracted.getHoverName().getString(), extracted.getCount());
        return InteractionResult.SUCCESS;
    }
}
