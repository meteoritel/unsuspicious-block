package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.blockentity.BrushableBlockEntityScanState;
import com.meteorite.unsuspiciousblock.enchantment.scanner.PenetratingScanService;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyLootRuntimeTracker;
import com.meteorite.unsuspiciousblock.journal.TriggerType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import org.jetbrains.annotations.NotNull;

/** 可疑解析仪——右键可疑方块查看其内部战利品 */
public class SuspiciousReaderItem extends Item {
    private static final int ENCHANTABILITY = 12;

    public SuspiciousReaderItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return true;
    }

    @Override
    public int getEnchantmentValue() {
        return ENCHANTABILITY;
    }

    @Override
    public @NotNull InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }

        BlockPos clickedPos = context.getClickedPos();
        BlockEntity blockEntity = level.getBlockEntity(clickedPos);
        if (!(blockEntity instanceof BrushableBlockEntity brushable)
                || !(blockEntity instanceof BrushableBlockEntityScanState scanState)) {
            player.sendSystemMessage(
                    Component.translatable("item.unsuspiciousblock.suspicious_reader.not_suspicious")
                            .withStyle(style -> style.withColor(0xFF5555))
            );
            return InteractionResult.FAIL;
        }

        ScanResult clickedResult = scanBrushable(serverPlayer, level, clickedPos, blockEntity, brushable, scanState);
        sendPrimaryResultMessage(player, clickedPos, clickedResult.lootItem());

        NearbyScanSummary nearbySummary = scanNearbyTargets(serverPlayer, level, context.getItemInHand(), clickedPos);
        if (nearbySummary.scannedCount() > 0) {
            player.sendSystemMessage(
                    Component.translatable("item.unsuspiciousblock.suspicious_reader.penetrating_summary",
                                    nearbySummary.scannedCount(), nearbySummary.nonEmptyCount())
                            .withStyle(style -> style.withColor(0x55FFFF))
            );
        }

        player.swing(context.getHand());
        return InteractionResult.SUCCESS;
    }

    private ScanResult scanBrushable(ServerPlayer player, Level level, BlockPos pos, BlockEntity blockEntity,
                                     BrushableBlockEntity brushable, BrushableBlockEntityScanState scanState) {
        brushable.unpackLootTable(player);
        ItemStack lootItem = brushable.getItem().copy();
        scanState.unsuspiciousblock$markScanned(player.getUUID());

        ResourceLocation lootTableName = scanState.unsuspiciousblock$getLootTableName();
        if (lootTableName != null) {
            long gameTime = level.getGameTime();
            long dayTime = level.getDayTime();
            ArchaeologyLootRuntimeTracker.onLootDiscovered(player, lootTableName, lootItem,
                    TriggerType.READER, gameTime, dayTime);
            scanState.unsuspiciousblock$setPendingJournalEntry(ArchaeologyLootRuntimeTracker.createPendingEntry(
                    player,
                    lootTableName,
                    TriggerType.READER,
                    BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock()),
                    pos,
                    lootItem,
                    gameTime,
                    dayTime
            ));
        }

        return new ScanResult(lootItem);
    }

    private NearbyScanSummary scanNearbyTargets(ServerPlayer player, Level level, ItemStack readerStack,
                                                BlockPos centerPos) {
        int radius = PenetratingScanService.getScanRadius(level.registryAccess(), readerStack);
        if (radius <= 0) {
            return NearbyScanSummary.EMPTY;
        }

        int scannedCount = 0;
        int nonEmptyCount = 0;
        for (BlockPos targetPos : PenetratingScanService.collectNearbyTargets(centerPos, radius)) {
            BlockEntity blockEntity = level.getBlockEntity(targetPos);
            if (!(blockEntity instanceof BrushableBlockEntity brushable)
                    || !(blockEntity instanceof BrushableBlockEntityScanState scanState)) {
                continue;
            }

            ScanResult result = scanBrushable(player, level, targetPos, blockEntity, brushable, scanState);
            scannedCount++;
            if (!result.lootItem().isEmpty()) {
                nonEmptyCount++;
            }
        }
        return new NearbyScanSummary(scannedCount, nonEmptyCount);
    }

    private void sendPrimaryResultMessage(Player player, BlockPos pos, ItemStack lootItem) {
        if (lootItem.isEmpty()) {
            player.sendSystemMessage(
                    Component.translatable(
                            "item.unsuspiciousblock.suspicious_reader.result_empty",
                            pos.getX(), pos.getY(), pos.getZ()
                    ).withStyle(style -> style.withColor(0xAAAAAA))
            );
            Constants.LOG.debug("可疑方块 {} 内没有任何物品。", pos);
            return;
        }

        Component header = Component.translatable(
                "item.unsuspiciousblock.suspicious_reader.result_header"
        ).withStyle(style -> style.withColor(0xFFD700).withBold(true));

        Component coords = Component.literal(
                String.format("(%d, %d, %d)", pos.getX(), pos.getY(), pos.getZ())
        ).withStyle(style -> style.withColor(0x55FFFF));

        Component itemName = lootItem.getHoverName().copy()
                .withStyle(style -> style.withColor(0xFFE040));

        Component count = Component.literal(" ×" + lootItem.getCount())
                .withStyle(style -> style.withColor(0xFFFFFF));

        Component full = Component.empty()
                .append(header)
                .append(Component.literal(" "))
                .append(coords)
                .append(Component.literal(" → ").withStyle(style -> style.withColor(0xAAAAAA)))
                .append(itemName)
                .append(count);

        player.sendSystemMessage(full);
        Constants.LOG.debug("可疑方块 {} 包含: {} ×{}",
                pos, lootItem.getHoverName().getString(), lootItem.getCount());
    }

    private record ScanResult(ItemStack lootItem) {
    }

    private record NearbyScanSummary(int scannedCount, int nonEmptyCount) {
        private static final NearbyScanSummary EMPTY = new NearbyScanSummary(0, 0);
    }
}
