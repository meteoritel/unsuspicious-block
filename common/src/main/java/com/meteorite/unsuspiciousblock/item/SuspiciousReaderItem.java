package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.blockentity.BrushableBlockEntityScanState;
import com.meteorite.unsuspiciousblock.journal.TriggerType;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyLootRuntimeTracker;
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

    public SuspiciousReaderItem(Properties properties) {
        super(properties);
    }

    @Override
    public @NotNull InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();

        if (level.isClientSide() || player == null) {
            return InteractionResult.SUCCESS;
        }

        BlockEntity be = level.getBlockEntity(context.getClickedPos());

        if (!(be instanceof BrushableBlockEntity brushable)) {
            player.sendSystemMessage(
                    Component.translatable("item.unsuspiciousblock.suspicious_reader.not_suspicious")
                            .withStyle(style -> style.withColor(0xFF5555))
            );
            return InteractionResult.FAIL;
        }

        brushable.unpackLootTable(player);
        ItemStack lootItem = brushable.getItem();

        if (!(brushable instanceof BrushableBlockEntityScanState scanState)) {
            return InteractionResult.FAIL;
        }

        scanState.unsuspiciousblock$markScanned(player.getUUID());

        ResourceLocation lootTableName = scanState.unsuspiciousblock$getLootTableName();
        if (lootTableName != null && player instanceof ServerPlayer sp) {
            long gameTime = level.getGameTime();
            long dayTime = level.getDayTime();
            ArchaeologyLootRuntimeTracker.onLootDiscovered(sp, lootTableName, lootItem,
                    TriggerType.READER, gameTime, dayTime);
            scanState.unsuspiciousblock$setPendingJournalEntry(ArchaeologyLootRuntimeTracker.createPendingEntry(
                    sp,
                    lootTableName,
                    TriggerType.READER,
                    BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()),
                    context.getClickedPos(),
                    lootItem,
                    gameTime,
                    dayTime
            ));
        }

        BlockPos pos = context.getClickedPos();

        if (lootItem.isEmpty()) {
            player.sendSystemMessage(
                    Component.translatable(
                            "item.unsuspiciousblock.suspicious_reader.result_empty",
                            pos.getX(), pos.getY(), pos.getZ()
                    ).withStyle(style -> style.withColor(0xAAAAAA))
            );
            Constants.LOG.debug("可疑方块 {} 内没有任何物品。", pos);
        } else {
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

        player.swing(context.getHand());
        return InteractionResult.SUCCESS;
    }
}
