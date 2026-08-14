package com.meteorite.unsuspiciousblock.item;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.block.UnsuspiciousBlockInteractions;
import com.meteorite.unsuspiciousblock.blockentity.BrushableBlockEntityScanState;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import com.meteorite.unsuspiciousblock.journal.tracking.LootSession;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContext;
import com.meteorite.unsuspiciousblock.journal.tracking.RecentLootTableService;
import com.meteorite.unsuspiciousblock.journal.tracking.event.LootTrackingEvents;
import com.meteorite.unsuspiciousblock.journal.tracking.settlement.LootSettlementStrategies;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/***
 * 考古铲——兼具铲子的全部挖掘功能，并附带以下特性：
 * 1. 自定义工具材质：铁挖掘等级、256 点耐久。
 * 2. 挖掘铲子可挖方块时，向下垂直连挖共 4 格（潜行时仅 1 格），不会破坏可疑方块及其支撑方块。
 * 3. 挖掘沙子 / 砂砾时有极小概率掉落古代金币。
 * 4. 右键已被扫描的可疑方块可直接取出战利品。
 */
public class ArchaeologicalShovelItem extends ShovelItem {

    // 向下连挖时，原始方块之外额外挖掘的方块数（原块 + 2 = 共 3 格）
    private static final int EXTRA_DIG_DEPTH = 2;
    // 挖掘沙 / 砾石掉落古代金币的概率
    private static final float ANCIENT_COIN_CHANCE = 0.003F;

    /***
     * 考古铲工具材质——铁挖掘等级、256 点耐久，其余属性沿用铁工具。
     */
    public static final Tier TIER = new Tier() {
        @Override
        public int getUses() {
            return 256;
        }

        @Override
        public float getSpeed() {
            return Tiers.IRON.getSpeed();
        }

        @Override
        public float getAttackDamageBonus() {
            return Tiers.IRON.getAttackDamageBonus();
        }

        @Override
        public @NotNull TagKey<Block> getIncorrectBlocksForDrops() {
            return Tiers.IRON.getIncorrectBlocksForDrops();
        }

        @Override
        public int getEnchantmentValue() {
            return Tiers.IRON.getEnchantmentValue();
        }

        @Override
        public @NotNull Ingredient getRepairIngredient() {
            return Ingredient.of(Items.IRON_INGOT);
        }
    };

    public ArchaeologicalShovelItem(Properties properties) {
        super(TIER, properties);
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @NotNull TooltipContext context,
                                @NotNull List<Component> tooltipLines, @NotNull TooltipFlag flag) {
        tooltipLines.add(Component.translatable(
                        "item.unsuspiciousblock.archaeological_shovel.tooltip_extract")
                .withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, tooltipLines, flag);
    }

    // ========== 挖掘行为 ==========

    @Override
    public boolean mineBlock(@NotNull ItemStack stack, @NotNull Level level, @NotNull BlockState state,
                             @NotNull BlockPos pos, @NotNull LivingEntity entity) {
        // 先执行原版逻辑（原始方块的耐久消耗由父类基于 Tool 组件处理）
        boolean result = super.mineBlock(stack, level, state, pos, entity);

        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel) || !(entity instanceof Player player)) {
            return result;
        }

        // 仅对可被铲子挖掘的方块触发额外行为
        if (!state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            return result;
        }

        // 原始方块若为沙 / 砾石，尝试掉落古代金币
        tryDropAncientCoin(serverLevel, pos, state);

        // 潜行时只挖一格
        if (player.isShiftKeyDown()) {
            return result;
        }

        // 向下垂直连挖额外方块（共 EXTRA_DIG_DEPTH + 1 格）
        BlockPos.MutableBlockPos cursor = pos.mutable();
        for (int i = 0; i < EXTRA_DIG_DEPTH; i++) {
            cursor.move(Direction.DOWN);
            if (!tryChainDig(serverLevel, stack, player, cursor.immutable())) {
                break;
            }
            if (stack.isEmpty()) {
                break;
            }
        }
        return result;
    }

    // 尝试连挖指定位置的方块，返回是否应继续向下挖掘
    private boolean tryChainDig(ServerLevel level, ItemStack stack, Player player, BlockPos pos) {
        BlockState target = level.getBlockState(pos);

        // 空气或不可被铲子挖掘的方块——中断连挖
        if (target.isAir() || !target.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            return false;
        }

        // 保护可疑方块本身
        if (level.getBlockEntity(pos) instanceof BrushableBlockEntityScanState) {
            return false;
        }

        // 保护可疑方块下方的支撑方块（移除后可疑沙 / 砾石会掉落而丢失战利品）
        if (level.getBlockEntity(pos.above()) instanceof BrushableBlockEntityScanState) {
            return false;
        }

        // 沙 / 砾石掉落古代金币
        tryDropAncientCoin(level, pos, target);

        // 破坏方块并掉落战利品
        level.destroyBlock(pos, true, player);

        // 消耗耐久
        ServerPlayer serverPlayer = player instanceof ServerPlayer sp ? sp : null;
        stack.hurtAndBreak(1, level, serverPlayer, item -> {});
        return true;
    }

    // 挖掘沙 / 砾石时按概率掉落古代金币
    private void tryDropAncientCoin(ServerLevel level, BlockPos pos, BlockState state) {
        if (ModItems.ANCIENT_COIN == null) {
            return;
        }
        boolean isSandLike = state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL);
        if (isSandLike && level.getRandom().nextFloat() < ANCIENT_COIN_CHANCE) {
            Block.popResource(level, pos, new ItemStack(ModItems.ANCIENT_COIN));
        }
    }

    // ========== 右键交互 ==========

    @Override
    public @NotNull InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockEntity be = level.getBlockEntity(pos);

        // 平台事件通常会先处理；此处作为直接物品调用时的兼容兜底
        Player interactionPlayer = context.getPlayer();
        if (interactionPlayer != null
                && UnsuspiciousBlockInteractions.tryBreak(
                        level, pos, interactionPlayer, context.getItemInHand())) {
            return InteractionResult.sidedSuccess(level.isClientSide());
        }

        // 可疑方块：取出已扫描的战利品
        if (be instanceof BrushableBlockEntityScanState scanState) {
            // 主手考古铲 + 副手扫描仪：若目标方块未被当前玩家扫描，让位给副手扫描仪优先触发
            Player player = context.getPlayer();
            if (context.getHand() == InteractionHand.MAIN_HAND
                    && player != null
                    && player.getItemInHand(InteractionHand.OFF_HAND).getItem() == ModItems.SUSPICIOUS_READER
                    && !scanState.unsuspiciousblock$isScanner(player.getUUID())) {
                return InteractionResult.PASS;
            }
            return extractFromSuspicious(context, level, pos, scanState);
        }

        // 非可疑方块：执行原版铲子行为（铲出路径 / 熄灭篝火）
        return super.useOn(context);
    }

    // 从已扫描的可疑方块中取出战利品
    private InteractionResult extractFromSuspicious(UseOnContext context, Level level, BlockPos pos,
                                                    BrushableBlockEntityScanState scanState) {
        Player player = context.getPlayer();
        if (level.isClientSide() || player == null) {
            return InteractionResult.SUCCESS;
        }

        if (!scanState.unsuspiciousblock$isScanner(player.getUUID())) {
            player.sendSystemMessage(
                    Component.translatable("item.unsuspiciousblock.archaeological_shovel.not_scanned")
                            .withStyle(style -> style.withColor(0xFFAA00))
            );
            return InteractionResult.FAIL;
        }

        ItemStack lootItem = scanState.unsuspiciousblock$getItem();

        if (lootItem.isEmpty()) {
            player.sendSystemMessage(
                    Component.translatable("item.unsuspiciousblock.archaeological_shovel.already_empty",
                                    pos.getX(), pos.getY(), pos.getZ())
                            .withStyle(style -> style.withColor(0xAAAAAA))
            );
            return InteractionResult.SUCCESS;
        }

        ItemStack extracted = lootItem.copy();
        scanState.unsuspiciousblock$setItem(ItemStack.EMPTY);
        scanState.unsuspiciousblock$markBlockEntityChanged();

        ResourceLocation lootTableName = scanState.unsuspiciousblock$getLootTableName();
        if (lootTableName != null && player instanceof ServerPlayer sp) {
            RecentLootTableService.record(sp, lootTableName);
            if (scanState.unsuspiciousblock$getPendingJournalEntry() == null) {
                long gameTime = level.getGameTime();
                long dayTime = level.getDayTime();
                LootTrackingContext trackingContext = LootTrackingContext.root(
                        sp, lootTableName, LootSourceType.ARCHAEOLOGY, gameTime, dayTime, pos,
                        BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()));
                LootTrackingEvents.submit(new LootSession(trackingContext), extracted,
                        LootSettlementStrategies.deferred(scanState::unsuspiciousblock$setPendingJournalEntry));
            }
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
        Constants.LOG.debug("从 {} 处取出了 {} ×{}",
                pos, extracted.getHoverName().getString(), extracted.getCount());
        return InteractionResult.SUCCESS;
    }
}
