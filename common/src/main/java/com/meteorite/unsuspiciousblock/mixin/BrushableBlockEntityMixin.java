package com.meteorite.unsuspiciousblock.mixin;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.blockentity.BrushableBlockEntityScanState;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalLogCollector;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.network.ArchaeologyJournalNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(BrushableBlockEntity.class)
public abstract class BrushableBlockEntityMixin implements BrushableBlockEntityScanState {
    @Unique
    private static final String UNSUSPICIOUSBLOCK_SCANNED_TAG = "unsuspiciousblock_scanned";

    @Unique
    private static final String UNSUSPICIOUSBLOCK_SCANNER_UUID_TAG = "unsuspiciousblock_scanner_uuid";

    @Unique
    private static final String UNSUSPICIOUSBLOCK_LOOT_TABLE_NAME_TAG = "unsuspiciousblock_loot_table_name";

    @Unique
    private static final String UNSUSPICIOUSBLOCK_LOOT_TABLE_PARSED_TAG = "unsuspiciousblock_loot_table_parsed";

    @Shadow
    @Nullable
    private ResourceKey<LootTable> lootTable;

    @Shadow
    private ItemStack item;

    @Unique
    private boolean unsuspiciousblock$scanned;

    @Unique
    @Nullable
    private UUID unsuspiciousblock$scannerUuid;

    @Unique
    @Nullable
    private ResourceLocation unsuspiciousblock$lootTableName;

    @Unique
    private boolean unsuspiciousblock$lootTableParsed;

    @Unique
    private boolean unsuspiciousblock$lootTableParsedThisCall;

    @Unique
    private boolean unsuspiciousblock$brushContext;

    @Unique
    private long unsuspiciousblock$brushGameTime = -1L;

    @Unique
    private long unsuspiciousblock$brushDayTime = -1L;

    @Unique
    private void unsuspiciousblock$writeScanData(CompoundTag tag) {
        tag.putBoolean(UNSUSPICIOUSBLOCK_SCANNED_TAG, this.unsuspiciousblock$scanned);
        if (this.unsuspiciousblock$scanned && this.unsuspiciousblock$scannerUuid != null) {
            tag.putUUID(UNSUSPICIOUSBLOCK_SCANNER_UUID_TAG, this.unsuspiciousblock$scannerUuid);
        } else {
            tag.remove(UNSUSPICIOUSBLOCK_SCANNER_UUID_TAG);
        }
    }

    @Unique
    private void unsuspiciousblock$readScanData(CompoundTag tag) {
        this.unsuspiciousblock$scanned = tag.getBoolean(UNSUSPICIOUSBLOCK_SCANNED_TAG);
        this.unsuspiciousblock$scannerUuid = this.unsuspiciousblock$scanned && tag.contains(UNSUSPICIOUSBLOCK_SCANNER_UUID_TAG)
                ? tag.getUUID(UNSUSPICIOUSBLOCK_SCANNER_UUID_TAG)
                : null;
    }

    @Unique
    private void unsuspiciousblock$writeLootTableData(CompoundTag tag) {
        tag.putBoolean(UNSUSPICIOUSBLOCK_LOOT_TABLE_PARSED_TAG, this.unsuspiciousblock$lootTableParsed);
        if (this.unsuspiciousblock$lootTableParsed && this.unsuspiciousblock$lootTableName != null) {
            tag.putString(UNSUSPICIOUSBLOCK_LOOT_TABLE_NAME_TAG, this.unsuspiciousblock$lootTableName.toString());
        } else {
            tag.remove(UNSUSPICIOUSBLOCK_LOOT_TABLE_NAME_TAG);
        }
    }

    @Unique
    private void unsuspiciousblock$readLootTableData(CompoundTag tag) {
        if (!tag.getBoolean(UNSUSPICIOUSBLOCK_LOOT_TABLE_PARSED_TAG)) {
            this.unsuspiciousblock$lootTableParsed = false;
            this.unsuspiciousblock$lootTableName = null;
            return;
        }

        ResourceLocation lootTableName = tag.contains(UNSUSPICIOUSBLOCK_LOOT_TABLE_NAME_TAG)
                ? ResourceLocation.tryParse(tag.getString(UNSUSPICIOUSBLOCK_LOOT_TABLE_NAME_TAG))
                : null;
        this.unsuspiciousblock$lootTableParsed = lootTableName != null;
        this.unsuspiciousblock$lootTableName = lootTableName;
    }

    @Unique
    private void unsuspiciousblock$syncBlockEntity() {
        BlockEntity blockEntity = (BlockEntity) (Object) this;
        blockEntity.setChanged();

        Level level = blockEntity.getLevel();
        if (level != null && !level.isClientSide()) {
            BlockPos pos = blockEntity.getBlockPos();
            var state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }

    // ========== 接口实现 ========== //
    @Override
    public void unsuspiciousblock$markScanned(UUID scannerUuid) {
        if (this.unsuspiciousblock$scanned && scannerUuid.equals(this.unsuspiciousblock$scannerUuid)) {
            return;
        }

        this.unsuspiciousblock$scanned = true;
        this.unsuspiciousblock$scannerUuid = scannerUuid;
        this.unsuspiciousblock$syncBlockEntity();
    }

    @Override
    public void unsuspiciousblock$clearScanned() {
        if (!this.unsuspiciousblock$scanned && this.unsuspiciousblock$scannerUuid == null) {
            return;
        }

        this.unsuspiciousblock$scanned = false;
        this.unsuspiciousblock$scannerUuid = null;
        this.unsuspiciousblock$syncBlockEntity();
    }

    @Override
    public boolean unsuspiciousblock$isScanned() {
        return this.unsuspiciousblock$scanned;
    }

    @Override
    @Nullable
    public UUID unsuspiciousblock$getScannerUuid() {
        return this.unsuspiciousblock$scannerUuid;
    }

    @Override
    @Nullable
    public ResourceLocation unsuspiciousblock$getLootTableName() {
        return this.unsuspiciousblock$lootTableName;
    }

    @Override
    public boolean unsuspiciousblock$isLootTableParsed() {
        return this.unsuspiciousblock$lootTableParsed;
    }

    @Override
    public ItemStack unsuspiciousblock$getItem() {
        return this.item;
    }

    @Override
    public void unsuspiciousblock$setItem(ItemStack stack) {
        this.item = stack;
    }

    // ========== 注入 ========== //
    // 标记刷子 context 开始
    @Inject(method = "brush", at = @At("HEAD"))
    private void unsuspiciousblock$onBrushStart(long gameTime, Player player, net.minecraft.core.Direction direction, CallbackInfoReturnable<Boolean> cir) {
        this.unsuspiciousblock$brushContext = true;
        this.unsuspiciousblock$brushGameTime = gameTime;
        this.unsuspiciousblock$brushDayTime = player.level().getDayTime();
    }

    // 标记刷子 context 结束
    @Inject(method = "brush", at = @At("TAIL"))
    private void unsuspiciousblock$onBrushEnd(long gameTime, Player player, net.minecraft.core.Direction direction, CallbackInfoReturnable<Boolean> cir) {
        this.unsuspiciousblock$brushContext = false;
        this.unsuspiciousblock$brushGameTime = -1L;
        this.unsuspiciousblock$brushDayTime = -1L;
    }

    // 刷拭完成物品掉落时增加获得计数
    @Inject(method = "dropContent", at = @At("HEAD"))
    private void unsuspiciousblock$onBrushItemDrop(Player player, CallbackInfo ci) {
        if (this.unsuspiciousblock$lootTableName != null
                && player instanceof ServerPlayer sp
                && player instanceof ArchaeologyJournalStateHolder holder) {
            if (!this.item.isEmpty()) {
                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(this.item.getItem());
                ArchaeologyJournalState journalState = holder.unsuspiciousblock$getArchaeologyJournalState();
                boolean tableUnlockedBefore = journalState.isTableUnlocked(this.unsuspiciousblock$lootTableName);
                journalState.recordItemAcquired(this.unsuspiciousblock$lootTableName, itemId);
                long gameTime = this.unsuspiciousblock$brushGameTime >= 0L
                        ? this.unsuspiciousblock$brushGameTime
                        : sp.serverLevel().getGameTime();
                long dayTime = this.unsuspiciousblock$brushDayTime >= 0L
                        ? this.unsuspiciousblock$brushDayTime
                        : sp.serverLevel().getDayTime();
                if (!tableUnlockedBefore) {
                    ArchaeologyJournalLogCollector.recordFirstUnlock(sp, this.unsuspiciousblock$lootTableName,
                            gameTime, dayTime);
                }
                ArchaeologyJournalLogCollector.recordExcavation(sp, this.unsuspiciousblock$lootTableName,
                        itemId, ((BlockEntity) (Object) this).getBlockPos(), gameTime, dayTime);
                ArchaeologyJournalNetwork.syncState(sp);
            }
        }
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void unsuspiciousblock$saveExtraData(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        this.unsuspiciousblock$writeScanData(tag);
        this.unsuspiciousblock$writeLootTableData(tag);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void unsuspiciousblock$loadExtraData(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        this.unsuspiciousblock$readScanData(tag);
        this.unsuspiciousblock$readLootTableData(tag);
    }

    @Inject(method = "getUpdateTag", at = @At("RETURN"))
    private void unsuspiciousblock$appendExtraDataToUpdateTag(HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> cir) {
        this.unsuspiciousblock$writeScanData(cir.getReturnValue());
        this.unsuspiciousblock$writeLootTableData(cir.getReturnValue());
    }

    @Inject(method = "unpackLootTable", at = @At("HEAD"))
    private void unsuspiciousblock$captureLootTableName(Player player, CallbackInfo ci) {
        this.unsuspiciousblock$lootTableParsedThisCall = false;
        BlockEntity blockEntity = (BlockEntity) (Object) this;
        Level level = blockEntity.getLevel();
        if (this.lootTable == null || level == null || level.isClientSide() || level.getServer() == null) {
            return;
        }

        this.unsuspiciousblock$lootTableName = this.lootTable.location();
        this.unsuspiciousblock$lootTableParsed = true;
        this.unsuspiciousblock$lootTableParsedThisCall = true;
    }

    @Inject(method = "unpackLootTable", at = @At("TAIL"))
    private void unsuspiciousblock$syncLootTableState(Player player, CallbackInfo ci) {
        if (!this.unsuspiciousblock$lootTableParsedThisCall) {
            return;
        }

        this.unsuspiciousblock$lootTableParsedThisCall = false;

        // 刷子首次刷出物品时解锁该物品
        if (this.unsuspiciousblock$brushContext && this.unsuspiciousblock$lootTableName != null
                && player instanceof ServerPlayer sp
                && player instanceof ArchaeologyJournalStateHolder holder) {
            if (!this.item.isEmpty()) {
                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(this.item.getItem());
                ArchaeologyJournalState journalState = holder.unsuspiciousblock$getArchaeologyJournalState();
                boolean tableUnlockedBefore = journalState.isTableUnlocked(this.unsuspiciousblock$lootTableName);
                journalState.unlockItem(this.unsuspiciousblock$lootTableName, itemId);
                if (!tableUnlockedBefore) {
                    long gameTime = this.unsuspiciousblock$brushGameTime >= 0L
                            ? this.unsuspiciousblock$brushGameTime
                            : sp.serverLevel().getGameTime();
                    long dayTime = this.unsuspiciousblock$brushDayTime >= 0L
                            ? this.unsuspiciousblock$brushDayTime
                            : sp.serverLevel().getDayTime();
                    ArchaeologyJournalLogCollector.recordFirstUnlock(sp, this.unsuspiciousblock$lootTableName,
                            gameTime, dayTime);
                }

                Constants.LOG.debug("刷子刷物品刚露头");
                ArchaeologyJournalNetwork.syncState(sp);
            }
        }

        this.unsuspiciousblock$syncBlockEntity();
    }

    @Inject(method = "setLootTable", at = @At("TAIL"))
    private void unsuspiciousblock$clearLootTableState(ResourceKey<LootTable> lootTable, long seed, CallbackInfo ci) {
        boolean hadLootTableState = this.unsuspiciousblock$lootTableParsed || this.unsuspiciousblock$lootTableName != null;
        this.unsuspiciousblock$lootTableName = null;
        this.unsuspiciousblock$lootTableParsed = false;
        this.unsuspiciousblock$lootTableParsedThisCall = false;
        if (hadLootTableState) {
            this.unsuspiciousblock$syncBlockEntity();
        }
    }

}
