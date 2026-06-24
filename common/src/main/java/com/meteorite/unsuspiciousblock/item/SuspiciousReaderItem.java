package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.achievement.AchievementManager;
import com.meteorite.unsuspiciousblock.achievement.ModAchievements;
import com.meteorite.unsuspiciousblock.blockentity.BrushableBlockEntityScanState;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncReaderScanResultPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 可疑解析仪——右键可疑方块查看其内部战利品，支持范围扫描与能量系统 */
public class SuspiciousReaderItem extends Item {

    public static final int MAX_ENERGY = 512;
    public static final int ENERGY_PER_COIN = 256;
    public static final int MAX_SCAN_LEVEL = 3;

    // debug 开关：开启时创造模式也不跳过能量消耗，方便测试
    public static boolean DEBUG_FORCE_ENERGY_COST = false;

    private static final String TAG_ENERGY = "unsuspiciousblock_reader_energy";
    private static final String TAG_SCAN_LEVEL = "unsuspiciousblock_reader_scan_level";

    public SuspiciousReaderItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isEnchantable(@NotNull ItemStack stack) {
        return true;
    }

    // ========== Tooltip ==========

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                @NotNull List<Component> tooltipLines, @NotNull TooltipFlag flag) {
        int energy = getEnergyOrDefault(stack);
        int scanLevel = getScanLevel(stack);

        // 能量条
        String modeKey = switch (scanLevel) {
            case 1 -> "item.unsuspiciousblock.suspicious_reader.scan_mode_1";
            case 2 -> "item.unsuspiciousblock.suspicious_reader.scan_mode_2";
            case 3 -> "item.unsuspiciousblock.suspicious_reader.scan_mode_3";
            default -> "item.unsuspiciousblock.suspicious_reader.scan_mode";
        };
        tooltipLines.add(Component.translatable("item.unsuspiciousblock.suspicious_reader.tooltip_scan_level",
                Component.translatable(modeKey)).withStyle(ChatFormatting.GRAY));
        tooltipLines.add(Component.translatable("item.unsuspiciousblock.suspicious_reader.tooltip_energy",
                energy, MAX_ENERGY).withStyle(ChatFormatting.GRAY));

        // 按键切换提示：按键绑定名以青色高亮，便于识别
        Component keybind = Component.keybind("key.unsuspiciousblock.scan_level_cycle")
                .withStyle(ChatFormatting.AQUA);
        tooltipLines.add(Component.translatable("item.unsuspiciousblock.suspicious_reader.tooltip_switch_mode", keybind)
                .withStyle(ChatFormatting.DARK_GRAY));

        super.appendHoverText(stack, context, tooltipLines, flag);
    }

    // ========== 能量与等级 CustomData 操作 ==========

    public static int getEnergy(ItemStack stack) {
        if (stack.getItem() != ModItems.SUSPICIOUS_READER) return 0;
        CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        return Math.min(MAX_ENERGY, customData.copyTag().getInt(TAG_ENERGY));
    }

    public static int getEnergyOrDefault(ItemStack stack) {
        int energy = getEnergy(stack);
        return energy == 0 ? MAX_ENERGY : energy;
    }

    public static void setEnergy(ItemStack stack, int energy) {
        if (stack.getItem() != ModItems.SUSPICIOUS_READER) return;
        int clamped = Math.max(0, Math.min(MAX_ENERGY, energy));
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt(TAG_ENERGY, clamped));
    }

    public static int getScanLevel(ItemStack stack) {
        if (stack.getItem() != ModItems.SUSPICIOUS_READER) return 0;
        CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        return Math.min(MAX_SCAN_LEVEL, customData.copyTag().getInt(TAG_SCAN_LEVEL));
    }

    public static void setScanLevel(ItemStack stack, int level) {
        if (stack.getItem() != ModItems.SUSPICIOUS_READER) return;
        int clamped = Math.max(0, Math.min(MAX_SCAN_LEVEL, level));
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt(TAG_SCAN_LEVEL, clamped));
    }

    // ========== 耐久条显示能量 ==========

    @Override
    public boolean isBarVisible(@NotNull ItemStack stack) {
        return getEnergyOrDefault(stack) < MAX_ENERGY;
    }

    @Override
    public int getBarWidth(@NotNull ItemStack stack) {
        return Math.round(13F * getEnergyOrDefault(stack) / MAX_ENERGY);
    }

    @Override
    public int getBarColor(@NotNull ItemStack stack) {
        float ratio = getEnergyOrDefault(stack) / (float) MAX_ENERGY;
        if (ratio > 0.66F) return 0x00FF00;
        if (ratio > 0.33F) return 0xFFFF00;
        return 0xFF0000;
    }

    // ========== 硬币查找与消耗 ==========

    // 从玩家背包中查找 ANCIENT_COIN，返回其槽位索引（未找到返回 -1）
    private int findCoinSlot(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).getItem() == ModItems.ANCIENT_COIN) {
                return i;
            }
        }
        return -1;
    }

    // 当能量不足时自动消耗硬币充能
    // 返回充能后的可用能量，若硬币不足返回 -1
    private int tryRecharge(ItemStack stack, Player player, int needed) {
        int coinsUsed = 0;
        int energy = getEnergyOrDefault(stack);
        while (energy < needed) {
            int coinSlot = findCoinSlot(player);
            if (coinSlot < 0) {
                // 硬币不足，回滚已消耗的硬币
                return -1;
            }
            player.getInventory().getItem(coinSlot).shrink(1);
            energy = Math.min(MAX_ENERGY, energy + ENERGY_PER_COIN);
            coinsUsed++;
        }
        setEnergy(stack, energy);
        if (coinsUsed > 0) {
            player.sendSystemMessage(
                    Component.translatable("item.unsuspiciousblock.suspicious_reader.coin_consumed", coinsUsed)
                            .withStyle(style -> style.withColor(0xFFD700))
            );
        }
        return energy;
    }

    // ========== 范围扫描几何计算 ==========

    // 根据点击面与扫描等级计算范围扫描立方体的中心位置
    // 与 useOn 中的几何逻辑保持一致，供客户端高亮复用
    public static BlockPos getRangeScanCenter(BlockPos clickedPos, Direction clickedFace, int scanLevel) {
        return clickedPos.relative(clickedFace.getOpposite(), scanLevel);
    }

    // 收集扫描范围内的所有可疑方块
    private List<BlockPos> findSuspiciousBlocks(Level level, BlockPos cubeCenter, int halfExtent) {
        List<BlockPos> result = new ArrayList<>();
        for (int dx = -halfExtent; dx <= halfExtent; dx++) {
            for (int dy = -halfExtent; dy <= halfExtent; dy++) {
                for (int dz = -halfExtent; dz <= halfExtent; dz++) {
                    BlockPos pos = cubeCenter.offset(dx, dy, dz);
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof BrushableBlockEntity && be instanceof BrushableBlockEntityScanState) {
                        result.add(pos);
                    }
                }
            }
        }
        return result;
    }

    // ========== 核心交互逻辑 ==========

    @Override
    public @NotNull InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }

        ItemStack stack = context.getItemInHand();
        int scanLevel = getScanLevel(stack);
        BlockPos clickedPos = context.getClickedPos();
        boolean isCreative = player.isCreative() && !DEBUG_FORCE_ENERGY_COST;

        // 范围模式（1-3级）：允许点击任意方块
        if (scanLevel > 0) {
            // 计算正方体范围
            Direction faceDir = context.getClickedFace();
            BlockPos cubeCenter = getRangeScanCenter(clickedPos, faceDir, scanLevel);
            List<BlockPos> targets = findSuspiciousBlocks(level, cubeCenter, scanLevel);

            // 基础消耗 = 等级数（范围模式启动即计费，无论是否扫到可疑方块）
            int baseCost = scanLevel;

            if (targets.isEmpty()) {
                // 范围内没有可疑方块：仍需消耗等级数能量（范围模式启动即计费）
                if (!isCreative) {
                    int energy = getEnergyOrDefault(stack);
                    if (energy < baseCost) {
                        energy = tryRecharge(stack, player, baseCost);
                        if (energy < baseCost) {
                            player.sendSystemMessage(
                                    Component.translatable("item.unsuspiciousblock.suspicious_reader.no_coins")
                                            .withStyle(style -> style.withColor(0xFF5555))
                            );
                            return InteractionResult.FAIL;
                        }
                    }
                    setEnergy(stack, Math.max(0, energy - baseCost));
                }
                player.sendSystemMessage(
                        Component.translatable("item.unsuspiciousblock.suspicious_reader.no_suspicious_in_range")
                                .withStyle(style -> style.withColor(0xFF5555))
                );
                return InteractionResult.FAIL;
            }

            // 计算未扫描的可疑方块数（仅未扫描的才消耗额外能量）
            int unscannedCount = 0;
            for (BlockPos pos : targets) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof BrushableBlockEntityScanState scanState
                        && !scanState.unsuspiciousblock$isScanner(serverPlayer.getUUID())) {
                    unscannedCount++;
                }
            }
            int totalCost = baseCost + unscannedCount;

            // 检查/补充能量
            if (!isCreative) {
                int energy = getEnergyOrDefault(stack);
                if (energy < totalCost) {
                    energy = tryRecharge(stack, player, totalCost);
                    if (energy < totalCost) {
                        player.sendSystemMessage(
                                Component.translatable("item.unsuspiciousblock.suspicious_reader.no_coins")
                                        .withStyle(style -> style.withColor(0xFF5555))
                        );
                        return InteractionResult.FAIL;
                    }
                }
            }

            // 执行扫描
            int actualScanned = 0;
            int newScanned = 0;
            for (BlockPos pos : targets) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof BrushableBlockEntity targetBrushable
                        && be instanceof BrushableBlockEntityScanState targetScanState) {
                    // 已被同一玩家扫描过的方块仍显示结果，但不消耗额外能量
                    boolean alreadyScanned = targetScanState.unsuspiciousblock$isScanner(serverPlayer.getUUID());
                    ScanResult result = scanBrushable(serverPlayer, level, pos, be, targetBrushable, targetScanState);
                    sendPrimaryResultMessage(player, pos, result.lootItem(), alreadyScanned);
                    actualScanned++;
                    if (!alreadyScanned) {
                        newScanned++;
                    }
                }
            }

            // 范围扫描结果提示
            if (!targets.isEmpty()) {
                player.sendSystemMessage(
                        Component.translatable("item.unsuspiciousblock.suspicious_reader.area_result", actualScanned)
                                .withStyle(style -> style.withColor(0x55FF55))
                );
            }

            // 向客户端同步扫描到的可疑方块位置，用于红色描边透视显示
            Services.NETWORK.sendToPlayer(serverPlayer, new SyncReaderScanResultPayload(targets));

            // 实际消耗 = 等级数 + 解析出新可疑方块数（仅1级及以上）
            if (!isCreative) {
                int actualCost = baseCost + newScanned;
                int energy = getEnergyOrDefault(stack);
                setEnergy(stack, Math.max(0, energy - actualCost));
            }

            player.swing(context.getHand());
            return InteractionResult.SUCCESS;
        }

        // ===== 单方块模式（0级）：必须点击可疑方块 =====

        BlockEntity blockEntity = level.getBlockEntity(clickedPos);
        if (!(blockEntity instanceof BrushableBlockEntity brushable)
                || !(blockEntity instanceof BrushableBlockEntityScanState scanState)) {
            player.sendSystemMessage(
                    Component.translatable("item.unsuspiciousblock.suspicious_reader.not_suspicious")
                            .withStyle(style -> style.withColor(0xFF5555))
            );
            return InteractionResult.FAIL;
        }

        // 单方块模式免费（能量消耗为 0）
        boolean alreadyScanned = scanState.unsuspiciousblock$isScanner(serverPlayer.getUUID());
        ScanResult result = scanBrushable(serverPlayer, level, clickedPos, blockEntity, brushable, scanState);
        sendPrimaryResultMessage(player, clickedPos, result.lootItem(), alreadyScanned);

        player.swing(context.getHand());
        return InteractionResult.SUCCESS;
    }

    private ScanResult scanBrushable(ServerPlayer player, Level level, BlockPos pos, BlockEntity blockEntity,
                                      BrushableBlockEntity brushable, BrushableBlockEntityScanState scanState) {
        brushable.unpackLootTable(player);
        ItemStack lootItem = brushable.getItem().copy();
        scanState.unsuspiciousblock$markScanned(player.getUUID());

        // 首次扫描到非空可疑方块时授予「Unsuspicious Minds」成就
        if (!lootItem.isEmpty()) {
            AchievementManager.grantIfNotAlready(player, ModAchievements.UNSUSPICIOUS_MINDS);
        }

        ResourceLocation lootTableName = scanState.unsuspiciousblock$getLootTableName();
        if (lootTableName != null) {
            long gameTime = level.getGameTime();
            long dayTime = level.getDayTime();
            ArchaeologyLootRuntimeTracker.onLootDiscovered(player, lootTableName, lootItem,
                    LootSourceType.ARCHAEOLOGY, gameTime, dayTime);
            scanState.unsuspiciousblock$setPendingJournalEntry(ArchaeologyLootRuntimeTracker.createPendingEntry(
                    player,
                    lootTableName,
                    LootSourceType.ARCHAEOLOGY,
                    BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock()),
                    pos,
                    lootItem,
                    gameTime,
                    dayTime
            ));
        }

        return new ScanResult(lootItem);
    }

    private void sendPrimaryResultMessage(Player player, BlockPos pos, ItemStack lootItem, boolean alreadyScanned) {
        // 已扫描过的方块显示灰色提示
        if (alreadyScanned) {
            player.sendSystemMessage(
                    Component.translatable(
                            "item.unsuspiciousblock.suspicious_reader.already_scanned",
                            pos.getX(), pos.getY(), pos.getZ()
                    ).withStyle(ChatFormatting.DARK_GRAY)
            );
        }

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
}