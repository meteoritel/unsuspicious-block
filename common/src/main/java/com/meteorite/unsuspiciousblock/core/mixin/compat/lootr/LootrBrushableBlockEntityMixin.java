package com.meteorite.unsuspiciousblock.core.mixin.compat.lootr;

import com.meteorite.unsuspiciousblock.core.block.UnsuspiciousBlockInteractions;
import com.meteorite.unsuspiciousblock.core.blockentity.BrushableBlockEntityScanState;
import com.meteorite.unsuspiciousblock.core.blockentity.BrushableTracking;
import com.meteorite.unsuspiciousblock.core.blockentity.PendingJournalHost;
import com.meteorite.unsuspiciousblock.core.blockentity.BrushableLootDropHelper;
import com.meteorite.unsuspiciousblock.core.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.core.journal.tracking.PendingJournal;
import com.meteorite.unsuspiciousblock.core.plugin.lootr.LootrBrushableTrackingAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.data.ILootrInfoProvider;
import noobanidus.mods.lootr.common.api.data.inventory.ILootrInventory;
import noobanidus.mods.lootr.common.block.entity.LootrBrushableBlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * 为 Lootr 的 {@link LootrBrushableBlockEntity} 实现 {@link BrushableBlockEntityScanState}，
 * 使其与原版可疑方块一样可被扫描仪、考古铲等工具交互。
 * <p>
 * Lootr 可疑方块独立于原版 {@code BrushableBlockEntity}，因此需要自己的 Mixin 注入扫描状态、
 * 战利品表状态与日志追踪字段。
 */
@Mixin(value = LootrBrushableBlockEntity.class, remap = false)
public abstract class LootrBrushableBlockEntityMixin
        implements PendingJournalHost, BrushableBlockEntityScanState, LootrBrushableTrackingAccess {

    // ========== Shadow Lootr 私有字段 ========== //
    @Shadow
    @Nullable
    private ResourceKey<LootTable> lootTable;

    @Shadow
    @Nullable
    private Direction hitDirection;

    // ========== 物品缓存字段 ========== //
    @Unique
    private ItemStack unsuspiciousblock$storedItem = ItemStack.EMPTY;

    @Unique
    @Nullable
    private ILootrInventory unsuspiciousblock$scanInventory;

    @Unique
    private final BrushableTracking unsuspiciousblock$tracking = new BrushableTracking(this::unsuspiciousblock$syncBlockEntity);

    @Unique
    private long unsuspiciousblock$brushGameTime = -1L;

    @Unique
    private long unsuspiciousblock$brushDayTime = -1L;

    @Unique
    private ItemStack unsuspiciousblock$brushTool = ItemStack.EMPTY;

    // ========== 辅助方法 ========== //

    // 将自身强转为 BlockEntity，避免散落重复强转
    @Unique
    @SuppressWarnings("DataFlowIssue")
    private BlockEntity unsuspiciousblock$asBlockEntity() {
        return (BlockEntity) (Object) this;
    }

    // 标记方块实体已变更并通知客户端
    @Unique
    private void unsuspiciousblock$syncBlockEntity() {
        BlockEntity blockEntity = this.unsuspiciousblock$asBlockEntity();
        blockEntity.setChanged();

        Level level = blockEntity.getLevel();
        if (level != null && !level.isClientSide()) {
            BlockPos pos = blockEntity.getBlockPos();
            var state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }

    // 按扫描玩家 UUID 找回 Lootr 的持久化每玩家库存，支持服务端重启后继续用考古铲提取
    @Unique
    @Nullable
    private ILootrInventory unsuspiciousblock$getScanInventory() {
        if (this.unsuspiciousblock$scanInventory != null) {
            return this.unsuspiciousblock$scanInventory;
        }
        if (!this.unsuspiciousblock$tracking.scan().scanned() || this.unsuspiciousblock$tracking.scan().scanner() == null) {
            return null;
        }

        Level level = this.unsuspiciousblock$asBlockEntity().getLevel();
        if (level == null || level.isClientSide() || level.getServer() == null) {
            return null;
        }
        ServerPlayer scanner = level.getServer().getPlayerList().getPlayer(this.unsuspiciousblock$tracking.scan().scanner());
        if (scanner == null) {
            return null;
        }

        this.unsuspiciousblock$scanInventory = LootrAPI.getInventory(
                (ILootrInfoProvider) (Object) this, scanner);
        return this.unsuspiciousblock$scanInventory;
    }

    // ========== 扫描状态接口实现 ========== //

    @Override
    public void unsuspiciousblock$markScanned(UUID scannerUuid) {
        if (this.unsuspiciousblock$tracking.scan().mark(scannerUuid)) {
            this.unsuspiciousblock$syncBlockEntity();
        }
    }

    @Override
    public void unsuspiciousblock$clearScanned() {
        if (this.unsuspiciousblock$tracking.clearScan()) {
            this.unsuspiciousblock$scanInventory = null;
            this.unsuspiciousblock$storedItem = ItemStack.EMPTY;
            this.unsuspiciousblock$syncBlockEntity();
        }
    }

    @Override
    public boolean unsuspiciousblock$isScanned() {
        return this.unsuspiciousblock$tracking.scan().scanned();
    }

    @Override
    @Nullable
    public UUID unsuspiciousblock$getScannerUuid() {
        return this.unsuspiciousblock$tracking.scan().scanner();
    }

    // ========== 战利品表状态接口实现 ========== //

    @Override
    @Nullable
    public ResourceLocation unsuspiciousblock$getLootTableName() {
        return this.unsuspiciousblock$tracking.table();
    }


    @Override
    public ItemStack unsuspiciousblock$getItem() {
        ILootrInventory inventory = this.unsuspiciousblock$getScanInventory();
        if (inventory != null) {
            this.unsuspiciousblock$storedItem = inventory.getItem(0).copy();
        }
        return this.unsuspiciousblock$storedItem.copy();
    }

    @Override
    public void unsuspiciousblock$setItem(ItemStack stack) {
        this.unsuspiciousblock$storedItem = stack.copy();

        ILootrInventory inventory = this.unsuspiciousblock$getScanInventory();
        if (inventory != null) {
            inventory.setItem(0, stack.copy());
            if (inventory instanceof TrackedContainerLootState trackedContainer && stack.isEmpty()) {
                trackedContainer.unsuspiciousblock$clearAllTrackingState();
            }
        }
    }

    // ========== 日志追踪接口实现 ========== //

    @Override
    public PendingJournal unsuspiciousblock$pendingJournal() {
        return this.unsuspiciousblock$tracking.pending();
    }

    // ========== 战利品表解析 ========== //

    // 读取 Lootr 为当前玩家维护的真实库存，避免扫描后正常刷拭再次生成一份战利品
    @Override
    public ItemStack unsuspiciousblock$resolveAndGetLoot(Player player) {
        if (!(player instanceof ServerPlayer sp)) {
            return ItemStack.EMPTY;
        }

        BlockEntity be = this.unsuspiciousblock$asBlockEntity();
        Level level = be.getLevel();
        if (level == null || level.isClientSide()) {
            return ItemStack.EMPTY;
        }

        ILootrInventory inventory = LootrAPI.getInventory(
                (ILootrInfoProvider) (Object) this, sp);
        if (inventory == null) {
            return ItemStack.EMPTY;
        }

        this.unsuspiciousblock$scanInventory = inventory;
        this.unsuspiciousblock$storedItem = inventory.getItem(0).copy();
        if (this.lootTable != null) {
            this.unsuspiciousblock$recordResolvedLootTable(this.lootTable.location());
        }

        // 扫描仪会在返回后创建方块级待定日志，清除 filler 创建的菜单型临时追踪，避免重复结算
        if (inventory instanceof TrackedContainerLootState trackedContainer) {
            trackedContainer.unsuspiciousblock$clearAllTrackingState();
        }
        return this.unsuspiciousblock$storedItem.copy();
    }

    // 标记方块实体已变更并同步到客户端
    @Override
    public void unsuspiciousblock$markBlockEntityChanged() {
        this.unsuspiciousblock$syncBlockEntity();
    }

    // 接收 DefaultBrushableLootFiller 已完成解析的表标识
    @Override
    public void unsuspiciousblock$recordResolvedLootTable(ResourceLocation tableId) {
        if (this.unsuspiciousblock$tracking.recordResolved(tableId)) {
            this.unsuspiciousblock$syncBlockEntity();
        }
    }

    // 缓存本次刷拭使用的时间与工具，供最终掉落时执行精掘附魔
    @Inject(method = "IBrushable$brush", at = @At("HEAD"))
    private void unsuspiciousblock$onBrushStart(
            long gameTime, Player player, Direction direction,
            CallbackInfoReturnable<Boolean> cir) {
        this.unsuspiciousblock$brushGameTime = gameTime;
        this.unsuspiciousblock$brushDayTime = player.level().getDayTime();
        ItemStack mainHand = player.getMainHandItem();
        if (UnsuspiciousBlockInteractions.isBrush(mainHand)) {
            this.unsuspiciousblock$brushTool = mainHand;
        } else {
            ItemStack offHand = player.getOffhandItem();
            this.unsuspiciousblock$brushTool = UnsuspiciousBlockInteractions.isBrush(offHand)
                    ? offHand : ItemStack.EMPTY;
        }
    }

    // 单次刷拭调用结束后清理临时上下文；最终掉落发生在 RETURN 之前
    @Inject(method = "IBrushable$brush", at = @At("RETURN"))
    private void unsuspiciousblock$onBrushEnd(
            long gameTime, Player player, Direction direction,
            CallbackInfoReturnable<Boolean> cir) {
        this.unsuspiciousblock$brushGameTime = -1L;
        this.unsuspiciousblock$brushDayTime = -1L;
        this.unsuspiciousblock$brushTool = ItemStack.EMPTY;
    }

    // Lootr 已从每玩家库存取出物品后，在返回给掉落流程前应用精掘并结算对应玩家日志
    @Inject(method = "popItem", at = @At("RETURN"), cancellable = true)
    private void unsuspiciousblock$processBrushDrop(
            Player player, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack originalItem = cir.getReturnValue();
        if (!(player instanceof ServerPlayer serverPlayer) || originalItem.isEmpty()) {
            return;
        }

        ILootrInventory inventory = LootrAPI.getInventory(
                (ILootrInfoProvider) (Object) this, serverPlayer);

        TrackedContainerLootState trackedContainer = inventory instanceof TrackedContainerLootState tracked
                ? tracked
                : null;
        ResourceLocation tableId = trackedContainer == null
                ? null
                : trackedContainer.unsuspiciousblock$getTrackedLootTableName();
        PendingJournal pendingEntry = trackedContainer == null
                ? null
                : trackedContainer.unsuspiciousblock$pendingJournal();

        // 扫描会清除 Lootr 库存的临时追踪状态，实际刷取时需回退到扫描者对应的方块级待定日志
        if (tableId == null) {
            tableId = this.unsuspiciousblock$tracking.table();
        }
        if ((pendingEntry == null || pendingEntry.current() == null) && this.unsuspiciousblock$isScanner(serverPlayer.getUUID())) {
            pendingEntry = this.unsuspiciousblock$tracking.pending();
        }

        BlockEntity blockEntity = this.unsuspiciousblock$asBlockEntity();
        BrushableLootDropHelper.DropContext context = new BrushableLootDropHelper.DropContext(
                serverPlayer, blockEntity.getBlockPos(), blockEntity.getLevel(), this.hitDirection,
                this.unsuspiciousblock$brushTool,
                this.unsuspiciousblock$brushGameTime, this.unsuspiciousblock$brushDayTime,
                tableId, pendingEntry);
        BrushableLootDropHelper.DropResult result =
                BrushableLootDropHelper.rollBrushItemDrop(context, originalItem);
        BrushableLootDropHelper.spawnExtraDrops(context, result.extraDrops());
        BrushableLootDropHelper.settleJournal(context, result.totalTrackedItem());

        if (trackedContainer != null) {
            trackedContainer.unsuspiciousblock$clearAllTrackingState();
        }
        this.unsuspiciousblock$tracking.pending().discard();
        cir.setReturnValue(result.rolledItem());
    }

    // ========== NBT 持久化注入 ========== //

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void unsuspiciousblock$saveExtraData(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        this.unsuspiciousblock$tracking.write(tag);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void unsuspiciousblock$loadExtraData(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        this.unsuspiciousblock$tracking.read(tag);
    }

    @Inject(method = "getUpdateTag", at = @At("RETURN"))
    private void unsuspiciousblock$appendExtraDataToUpdateTag(HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> cir) {
        this.unsuspiciousblock$tracking.write(cir.getReturnValue());
    }

    // Lootr 为方块设置新表时清除上一轮扫描与解析缓存
    @Inject(method = "setLootTableInternal", at = @At("TAIL"))
    private void unsuspiciousblock$clearResolvedState(
            ResourceKey<LootTable> table, long seed, CallbackInfo ci) {
        this.unsuspiciousblock$tracking.clearResolved();
        this.unsuspiciousblock$scanInventory = null;
        this.unsuspiciousblock$storedItem = ItemStack.EMPTY;
    }
}
