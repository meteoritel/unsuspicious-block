package com.meteorite.unsuspiciousblock;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
import net.neoforged.neoforge.network.PacketDistributor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;

public class LuoyangSpadeItem extends Item {
    private static final Logger LOGGER = LogManager.getLogger();

    // 使用反射将可疑方块中的战利品置空
    private static final String ITEM_FIELD_NAME = "item";
    private static Field itemField = null;

    static {
        try {
            itemField = BrushableBlockEntity.class.getDeclaredField(ITEM_FIELD_NAME);
            itemField.setAccessible(true);
            LOGGER.debug("Successfully located BrushableBlockEntity#{} via reflection.", ITEM_FIELD_NAME);
        } catch (NoSuchFieldException e) {
            // 如果没有找到当前字段，就通过遍历寻找
            for (Field f : BrushableBlockEntity.class.getDeclaredFields()) {
                if (f.getType() == ItemStack.class) {
                    f.setAccessible(true);
                    itemField = f;
                    LOGGER.debug("Located ItemStack field '{}' in BrushableBlockEntity via fallback scan.",
                            f.getName());
                    break;
                }
            }
            if (itemField == null) {
                LOGGER.error("Could not find ItemStack field in BrushableBlockEntity! " +
                        "LuoyangSpadeItem will not be able to extract loot.", e);
            }
        }
    }

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

        // 必须是可疑方块
        if (!(be instanceof BrushableBlockEntity brushable)) {
            player.sendSystemMessage(
                    Component.translatable("item.unsuspiciousblock.luoyang_spade.not_suspicious")
                            .withStyle(style -> style.withColor(0xFF5555))
            );
            return InteractionResult.FAIL;
        }

        long posKey = pos.asLong();

        // 必须已经扫描过
        if (!SuspiciousReaderItem.SCAN_CACHE.containsKey(posKey)) {
            player.sendSystemMessage(
                    Component.translatable("item.unsuspiciousblock.luoyang_spade.not_scanned")
                            .withStyle(style -> style.withColor(0xFFAA00))
            );
            return InteractionResult.FAIL;
        }

        // 保险的一步解包
        brushable.unpackLootTable(player);
        ItemStack lootItem = brushable.getItem();

        if (lootItem.isEmpty()) {
            // 方块本来就空的
            player.sendSystemMessage(
                    Component.translatable("item.unsuspiciousblock.luoyang_spade.already_empty",
                                    pos.getX(), pos.getY(), pos.getZ())
                            .withStyle(style -> style.withColor(0xAAAAAA))
            );

            return InteractionResult.SUCCESS;
        }

        // 取出战利品
        ItemStack extracted = lootItem.copy();
        boolean cleared = clearItemField(brushable);
        if (!cleared) {
            // 反射失败时拒绝操作
            player.sendSystemMessage(
                    Component.translatable("item.unsuspiciousblock.luoyang_spade.reflection_failed")
                            .withStyle(style -> style.withColor(0xFF5555))
            );
            return InteractionResult.FAIL;
        }

        brushable.setChanged();
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);

        // 方块已空，更新缓存
        SuspiciousReaderItem.SCAN_CACHE.put(posKey, ItemStack.EMPTY);

        // 将物品给予玩家，给不下则掉落在方块旁
        boolean given = player.getInventory().add(extracted);
        if (!given) {
            ItemEntity itemEntity = new ItemEntity(
                    level,
                    pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                    extracted
            );
            itemEntity.setDefaultPickUpDelay();
            level.addFreshEntity(itemEntity);
        }

        player.swing(context.getHand());

        PacketDistributor.sendToPlayer((ServerPlayer) player,
                new LootResultPacket(ItemStack.EMPTY, pos));

        LOGGER.debug("LuoyangSpade extracted {} x{} from suspicious block at {}.",
                extracted.getHoverName().getString(), extracted.getCount(), pos);
        return InteractionResult.SUCCESS;
    }

    // 通过反射将可疑方块中的item字段设置为空
    private static boolean clearItemField(BrushableBlockEntity brushable) {
        if (itemField == null) return false;

        try {
            itemField.set(brushable, ItemStack.EMPTY);
            return true;
        } catch (IllegalAccessException e) {
            LOGGER.error("Failed to clear item field via reflection.", e);
            return false;
        }
    }

}
