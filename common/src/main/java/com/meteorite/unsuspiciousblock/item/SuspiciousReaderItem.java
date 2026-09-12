package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.achievement.AchievementManager;
import com.meteorite.unsuspiciousblock.achievement.ModAchievements;
import com.meteorite.unsuspiciousblock.blockentity.BrushableBlockEntityScanState;
import com.meteorite.unsuspiciousblock.block.SealedContents;
import com.meteorite.unsuspiciousblock.blockentity.UnsuspiciousBlockEntity;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.LootSession;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContext;
import com.meteorite.unsuspiciousblock.journal.tracking.event.LootTrackingEvents;
import com.meteorite.unsuspiciousblock.journal.tracking.settlement.LootSettlementStrategies;
import com.meteorite.unsuspiciousblock.network.payload.s2c.SyncReaderScanResultPayload;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.meteorite.unsuspiciousblock.sound.ModSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** 可疑解析仪——右键可疑方块查看其内部战利品，支持范围扫描与能量系统 */
public class SuspiciousReaderItem extends Item {

    public static final int MAX_ENERGY = 512;
    public static final int ENERGY_PER_COIN = 256;
    public static final int MAX_SCAN_LEVEL = 3;

    // shift+右键空气充能时，双击强制充能的判定窗口（单位：tick）
    public static final int CHARGE_DOUBLE_CLICK_WINDOW_TICKS = 20;

    // 「满载而归」挑战：单次范围扫描扫出的对象（可疑方块 + 战利品容器）达到该数量即授予
    public static final int MOTHERLODE_MIN_OBJECTS = 8;

    // debug 开关：开启时创造模式也不跳过能量消耗，方便测试
    public static boolean DEBUG_FORCE_ENERGY_COST = false;

    private static final String TAG_ENERGY = "unsuspiciousblock_reader_energy";
    private static final String TAG_SCAN_LEVEL = "unsuspiciousblock_reader_scan_level";
    // 记录上一次“浪费保护提示”触发时的游戏刻，用于双击强制判定
    private static final String TAG_LAST_CHARGE_ATTEMPT = "unsuspiciousblock_reader_last_charge_attempt";

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
        tooltipLines.add(Component.translatable("item.unsuspiciousblock.suspicious_reader.tooltip_recharge")
                .withStyle(ChatFormatting.DARK_GRAY));

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
        int clamped = Math.clamp(energy, 0, MAX_ENERGY);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt(TAG_ENERGY, clamped));
    }

    public static int getScanLevel(ItemStack stack) {
        if (stack.getItem() != ModItems.SUSPICIOUS_READER) return 0;
        CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        return Math.min(MAX_SCAN_LEVEL, customData.copyTag().getInt(TAG_SCAN_LEVEL));
    }

    public static void setScanLevel(ItemStack stack, int level) {
        if (stack.getItem() != ModItems.SUSPICIOUS_READER) return;
        int clamped = Math.clamp(level, 0, MAX_SCAN_LEVEL);
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

    // 收集扫描范围内的所有可疑方块与含战利品表的容器
    private ScanTargets findScanTargets(Level level, BlockPos cubeCenter, int halfExtent) {
        List<BlockPos> suspicious = new ArrayList<>();
        List<BlockPos> lootContainers = new ArrayList<>();
        for (int dx = -halfExtent; dx <= halfExtent; dx++) {
            for (int dy = -halfExtent; dy <= halfExtent; dy++) {
                for (int dz = -halfExtent; dz <= halfExtent; dz++) {
                    BlockPos pos = cubeCenter.offset(dx, dy, dz);
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof BrushableBlockEntityScanState) {
                        suspicious.add(pos);
                    } else if (be instanceof RandomizableContainer container
                            && container.getLootTable() != null) {
                        // 仅检测是否绑定了战利品表，不调用 unpackLootTable（会清空 lootTable 字段导致容器状态改变）
                        lootContainers.add(pos);
                    }
                }
            }
        }
        return new ScanTargets(suspicious, lootContainers);
    }

    // ========== 核心交互逻辑 ==========

    @Override
    public @NotNull InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        BlockPos clickedPos = context.getClickedPos();

        // 主手扫描仪 + 副手考古铲：点击已扫描的可疑方块时，让位给副手考古铲取出战利品
        // 必须在客户端早返回之前判定--客户端须返回 PASS 才会触发原版流程尝试副手；
        if (context.getHand() == InteractionHand.MAIN_HAND
                && player != null
                && player.getItemInHand(InteractionHand.OFF_HAND).getItem() == ModItems.ARCHAEOLOGICAL_SHOVEL) {
            BlockEntity be = level.getBlockEntity(clickedPos);
            if (!(be instanceof UnsuspiciousBlockEntity)
                    && be instanceof BrushableBlockEntityScanState scanState
                    && scanState.unsuspiciousblock$isScanner(player.getUUID())) {
                return InteractionResult.PASS;
            }
        }

        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }

        ItemStack stack = context.getItemInHand();
        int scanLevel = getScanLevel(stack);
        boolean isCreative = player.isCreative() && !DEBUG_FORCE_ENERGY_COST;

        // 范围模式（1-3级）：允许点击任意方块
        if (scanLevel > 0) {
            // 计算正方体范围
            Direction faceDir = context.getClickedFace();
            BlockPos cubeCenter = getRangeScanCenter(clickedPos, faceDir, scanLevel);
            ScanTargets scanTargets = findScanTargets(level, cubeCenter, scanLevel);
            List<BlockPos> targets = scanTargets.suspicious();
            List<BlockPos> lootContainers = scanTargets.lootContainers();

            // 基础消耗 = 等级数（范围模式启动即计费，无论是否扫到可疑方块或战利品容器）
            if (targets.isEmpty() && lootContainers.isEmpty()) {
                if (!isCreative) {
                    int energy = getEnergyOrDefault(stack);
                    if (energy < scanLevel) {
                        energy = tryRecharge(stack, player, scanLevel);
                        if (energy < scanLevel) {
                            player.sendSystemMessage(
                                    Component.translatable("item.unsuspiciousblock.suspicious_reader.no_coins")
                                            .withStyle(style -> style.withColor(0xFF5555))
                            );
                            return InteractionResult.FAIL;
                        }
                    }
                    setEnergy(stack, Math.max(0, energy - scanLevel));
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
            int totalCost = scanLevel + unscannedCount;

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
            List<SyncReaderScanResultPayload.ScanEntry> scanResults = new ArrayList<>(targets.size());
            int newScanned = 0;
            for (BlockPos pos : targets) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof BrushableBlockEntityScanState targetScanState) {
                    // 已被同一玩家扫描过的方块仍显示结果，但不消耗额外能量
                    boolean alreadyScanned = targetScanState.unsuspiciousblock$isScanner(serverPlayer.getUUID());
                    ScanResult result = scanBrushable(serverPlayer, level, pos, be, targetScanState);
                    scanResults.add(createScanEntry(pos, result, alreadyScanned));
                    logScanResult(pos, result);
                    if (!alreadyScanned) {
                        newScanned++;
                    }
                }
            }

            // 「满载而归」挑战：单次范围扫描扫出的可疑方块 + 战利品容器合计达标即授予
            if (targets.size() + lootContainers.size() >= MOTHERLODE_MIN_OBJECTS) {
                AchievementManager.grantIfNotAlready(serverPlayer, ModAchievements.MOTHERLODE);
            }

            // 向客户端同步扫描到的高亮方块位置（可疑方块 + 战利品容器），用于描边透视显示
            Services.NETWORK.sendToPlayer(serverPlayer,
                    new SyncReaderScanResultPayload(true, scanResults, lootContainers));

            // 实际消耗 = 等级数 + 解析出新可疑方块数（仅1级及以上）
            if (!isCreative) {
                int actualCost = scanLevel + newScanned;
                int energy = getEnergyOrDefault(stack);
                setEnergy(stack, Math.max(0, energy - actualCost));
            }

            // 播放扫描音效
            level.playSound(null, clickedPos, ModSounds.SUSPICIOUS_READER_SCAN.value(),
                    SoundSource.PLAYERS, 1.0F, 1.0F);
            player.swing(context.getHand());
            return InteractionResult.SUCCESS;
        }

        // ===== 单方块模式（0级）：必须点击可疑方块 =====

        BlockEntity blockEntity = level.getBlockEntity(clickedPos);
        if (!(blockEntity instanceof BrushableBlockEntityScanState scanState)) {
            player.sendSystemMessage(
                    Component.translatable("item.unsuspiciousblock.suspicious_reader.not_suspicious")
                            .withStyle(style -> style.withColor(0xFF5555))
            );
            return InteractionResult.FAIL;
        }

        // 单方块模式免费（能量消耗为 0）
        boolean alreadyScanned = scanState.unsuspiciousblock$isScanner(serverPlayer.getUUID());
        ScanResult result = scanBrushable(serverPlayer, level, clickedPos, blockEntity, scanState);
        logScanResult(clickedPos, result);
        Services.NETWORK.sendToPlayer(serverPlayer, new SyncReaderScanResultPayload(
                false, List.of(createScanEntry(clickedPos, result, alreadyScanned)), List.of()));

        // 播放扫描音效
        level.playSound(null, clickedPos, ModSounds.SUSPICIOUS_READER_SCAN.value(),
                SoundSource.PLAYERS, 1.0F, 1.0F);
        player.swing(context.getHand());
        return InteractionResult.SUCCESS;
    }

    // ========== shift+右键空气：硬币充能 ==========

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(@NotNull Level level, @NotNull Player player,
                                                           @NotNull InteractionHand usedHand) {
        ItemStack stack = player.getItemInHand(usedHand);
        // 仅在 shift 按下时触发充能流程；否则交给默认行为
        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.success(stack);
        }

        int energy = getEnergyOrDefault(stack);

        // 已满：无需充能
        if (energy >= MAX_ENERGY) {
            player.sendSystemMessage(
                    Component.translatable("item.unsuspiciousblock.suspicious_reader.charge_full")
                            .withStyle(ChatFormatting.GRAY)
            );
            player.swing(usedHand);
            return InteractionResultHolder.success(stack);
        }

        boolean isCreative = serverPlayer.isCreative() && !DEBUG_FORCE_ENERGY_COST;
        int consumed = MAX_ENERGY - energy;
        long now = level.getGameTime();
        long lastAttempt = getLastChargeAttempt(stack);

        // 浪费保护：当前已消耗能量不足一个硬币的充能值，单次充能会因上限截断而浪费
        // 创造模式不受此限制（不消耗硬币，无所谓浪费）
        if (!isCreative && consumed < ENERGY_PER_COIN) {
            // 双击判定：在窗口期内再次 shift+右键空气 → 强制消耗硬币充能
            if (lastAttempt > 0 && now - lastAttempt <= CHARGE_DOUBLE_CLICK_WINDOW_TICKS) {
                consumeOneCoinAndCharge(stack, serverPlayer, true);
                setLastChargeAttempt(stack, 0L);
            } else {
                // 首次提示，记录时间戳等待双击
                setLastChargeAttempt(stack, now);
                player.sendSystemMessage(
                        Component.translatable(
                                "item.unsuspiciousblock.suspicious_reader.charge_waste_hint",
                                consumed, ENERGY_PER_COIN
                        ).withStyle(style -> style.withColor(0xFFAA00))
                );
            }
            player.swing(usedHand);
            return InteractionResultHolder.success(stack);
        }

        // 正常充能：已消耗能量足够一个硬币，无浪费风险
        consumeOneCoinAndCharge(stack, serverPlayer, false);
        player.swing(usedHand);
        return InteractionResultHolder.success(stack);
    }

    // 消耗一枚硬币补充 ENERGY_PER_COIN 能量；硬币不足时给出提示
    // forced 为 true 时表示双击触发的强制充能，提示文案区分
    private void consumeOneCoinAndCharge(ItemStack stack, ServerPlayer player, boolean forced) {
        int coinSlot = findCoinSlot(player);
        if (coinSlot < 0) {
            player.sendSystemMessage(
                    Component.translatable("item.unsuspiciousblock.suspicious_reader.no_coins")
                            .withStyle(style -> style.withColor(0xFF5555))
            );
            return;
        }
        player.getInventory().getItem(coinSlot).shrink(1);
        int energy = getEnergyOrDefault(stack);
        setEnergy(stack, Math.min(MAX_ENERGY, energy + ENERGY_PER_COIN));

        Component msg = forced
                ? Component.translatable(
                        "item.unsuspiciousblock.suspicious_reader.charge_forced")
                        .withStyle(style -> style.withColor(0xFFD700))
                : Component.translatable(
                        "item.unsuspiciousblock.suspicious_reader.coin_consumed", 1)
                        .withStyle(style -> style.withColor(0xFFD700));
        player.sendSystemMessage(msg);
    }

    private long getLastChargeAttempt(ItemStack stack) {
        if (stack.getItem() != ModItems.SUSPICIOUS_READER) return 0L;
        CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        return customData.copyTag().getLong(TAG_LAST_CHARGE_ATTEMPT);
    }

    private void setLastChargeAttempt(ItemStack stack, long gameTime) {
        if (stack.getItem() != ModItems.SUSPICIOUS_READER) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
                tag -> tag.putLong(TAG_LAST_CHARGE_ATTEMPT, gameTime));
    }

    private ScanResult scanBrushable(ServerPlayer player, Level level, BlockPos pos, BlockEntity blockEntity,
                                      BrushableBlockEntityScanState scanState) {
        ItemStack lootItem = scanState.unsuspiciousblock$resolveAndGetLoot(player);
        scanState.unsuspiciousblock$markScanned(player.getUUID());

        ResourceLocation lootTableName = scanState.unsuspiciousblock$getLootTableName();
        if (lootTableName != null) {
            long gameTime = level.getGameTime();
            long dayTime = level.getDayTime();
            LootTrackingContext context = LootTrackingContext.root(
                    player, lootTableName, LootSourceType.ARCHAEOLOGY, gameTime, dayTime, pos,
                    BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock()));
            LootTrackingEvents.submit(new LootSession(context), lootItem,
                    LootSettlementStrategies.deferred(scanState::unsuspiciousblock$setPendingJournalEntry));
        }

        SealedContents.CrafterIdentity crafter = blockEntity instanceof UnsuspiciousBlockEntity sealedBlock
                ? sealedBlock.getCrafter().orElse(null)
                : null;
        return new ScanResult(lootItem, crafter, blockEntity instanceof UnsuspiciousBlockEntity);
    }

    private SyncReaderScanResultPayload.ScanEntry createScanEntry(BlockPos pos, ScanResult result,
                                                                  boolean alreadyScanned) {
        ItemStack lootItem = result.lootItem();
        ResourceLocation itemId = lootItem.isEmpty()
                ? null
                : BuiltInRegistries.ITEM.getKey(lootItem.getItem());
        Component displayName = lootItem.isEmpty() ? Component.empty() : lootItem.getHoverName();
        String crafterName = "";
        if (result.crafter() != null) {
            crafterName = result.crafter().name().isBlank()
                    ? result.crafter().uuid().toString()
                    : result.crafter().name();
        }
        return new SyncReaderScanResultPayload.ScanEntry(
                pos, itemId, lootItem.getCount(), displayName, alreadyScanned,
                result.sealedByPlayer(), crafterName);
    }

    private void logScanResult(BlockPos pos, ScanResult result) {
        if (result.lootItem().isEmpty()) {
            Constants.LOG.debug("可疑方块 {} 内没有任何物品。", pos);
            return;
        }
        Constants.LOG.debug("可疑方块 {} 包含: {} ×{}", pos,
                result.lootItem().getHoverName().getString(), result.lootItem().getCount());
    }

    private record ScanResult(ItemStack lootItem, @Nullable SealedContents.CrafterIdentity crafter,
                              boolean sealedByPlayer) {
    }

    // 范围扫描结果分类：可疑方块与含战利品表的容器
    private record ScanTargets(List<BlockPos> suspicious, List<BlockPos> lootContainers) {
    }
}
