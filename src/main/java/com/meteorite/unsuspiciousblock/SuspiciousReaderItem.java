package com.meteorite.unsuspiciousblock;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

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

        // 与可疑方块交互
        if (!(be instanceof BrushableBlockEntity brushable)) {
            // 发送消息声明这不是可疑方块
            player.sendSystemMessage(
                    Component.translatable("item.unsuspiciousblock.suspicious_reader.not_suspicious")
                            .withStyle(style -> style.withColor(0xFF5555)) // red
            );
            return InteractionResult.FAIL;
        }

        // 解析战利品表，并读取战利品
        brushable.unpackLootTable(player);
        ItemStack lootItem = brushable.getItem();

        // 发送消息到玩家聊天栏
        ServerPlayer serverPlayer = (ServerPlayer) player;

        if (lootItem.isEmpty()) {
            PacketDistributor.sendToPlayer(serverPlayer,
                    new LootResultPacket(ItemStack.EMPTY, context.getClickedPos()));
            UnsuspiciousBlock.LOGGER.debug("[UnsuspiciousBlock] Suspicious block at {} contains nothing.",
                    context.getClickedPos());
        } else {
            PacketDistributor.sendToPlayer(serverPlayer,
                    new LootResultPacket(lootItem, context.getClickedPos()));
            UnsuspiciousBlock.LOGGER.debug("[UnsuspiciousBlock] Suspicious block at {} contains: {} x{}",
                    context.getClickedPos(),
                    lootItem.getHoverName().getString(),
                    lootItem.getCount());
        }

        // Swing arm to give feedback without modifying the block
        player.swing(context.getHand());
        return InteractionResult.SUCCESS;
    }
}
