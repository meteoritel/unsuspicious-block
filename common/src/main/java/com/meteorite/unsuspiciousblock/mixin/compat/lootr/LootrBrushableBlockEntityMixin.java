package com.meteorite.unsuspiciousblock.mixin.compat.lootr;

import com.meteorite.unsuspiciousblock.blockentity.BrushableBlockEntityScanState;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import noobanidus.mods.lootr.common.block.entity.LootrBrushableBlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;
import java.util.UUID;

/**
 * 为 Lootr 的 {@link LootrBrushableBlockEntity} 实现 {@link BrushableBlockEntityScanState}，
 * 使其与原版可疑方块一样可被扫描仪、考古铲等工具交互。
 * <p>
 * Lootr 可疑方块独立于原版 {@code BrushableBlockEntity}，因此需要自己的 Mixin 注入扫描状态、
 * 战利品表状态与日志追踪字段。
 */
@Mixin(value = LootrBrushableBlockEntity.class, remap = false)
public abstract class LootrBrushableBlockEntityMixin implements BrushableBlockEntityScanState {

    // ========== NBT 键 ========== //
    @Unique
    private static final String UNSUSPICIOUSBLOCK_SCANNED_TAG = "unsuspiciousblock_scanned";

    @Unique
    private static final String UNSUSPICIOUSBLOCK_SCANNER_UUID_TAG = "unsuspiciousblock_scanner_uuid";

    @Unique
    private static final String UNSUSPICIOUSBLOCK_LOOT_TABLE_NAME_TAG = "unsuspiciousblock_loot_table_name";

    @Unique
    private static final String UNSUSPICIOUSBLOCK_LOOT_TABLE_PARSED_TAG = "unsuspiciousblock_loot_table_parsed";

    @Unique
    private static final String UNSUSPICIOUSBLOCK_PENDING_JOURNAL_ENTRY_TAG = "unsuspiciousblock_pending_journal_entry";

    // ========== Shadow Lootr 私有字段 ========== //
    @Shadow
    @Nullable
    private ResourceKey<LootTable> lootTable;

    @Shadow
    private long lootTableSeed;

    // ========== 扫描状态字段 ========== //
    @Unique
    private boolean unsuspiciousblock$scanned;

    @Unique
    @Nullable
    private UUID unsuspiciousblock$scannerUuid;

    // ========== 战利品表状态字段 ========== //
    @Unique
    @Nullable
    private ResourceLocation unsuspiciousblock$lootTableName;

    @Unique
    private boolean unsuspiciousblock$lootTableParsed;

    // ========== 物品缓存字段 ========== //
    @Unique
    private ItemStack unsuspiciousblock$storedItem = ItemStack.EMPTY;

    // ========== 日志追踪字段 ========== //
    @Unique
    @Nullable
    private ExcavationLogEntry unsuspiciousblock$pendingJournalEntry;

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

    // ========== 扫描状态 NBT ========== //

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

    // ========== 战利品表状态 NBT ========== //

    @Unique
    private void unsuspiciousblock$writeLootTableData(CompoundTag tag) {
        tag.putBoolean(UNSUSPICIOUSBLOCK_LOOT_TABLE_PARSED_TAG, this.unsuspiciousblock$lootTableParsed);
        if (this.unsuspiciousblock$lootTableParsed && this.unsuspiciousblock$lootTableName != null) {
            tag.putString(UNSUSPICIOUSBLOCK_LOOT_TABLE_NAME_TAG, this.unsuspiciousblock$lootTableName.toString());
        } else {
            tag.remove(UNSUSPICIOUSBLOCK_LOOT_TABLE_NAME_TAG);
        }
        if (this.unsuspiciousblock$pendingJournalEntry != null) {
            tag.put(UNSUSPICIOUSBLOCK_PENDING_JOURNAL_ENTRY_TAG, this.unsuspiciousblock$pendingJournalEntry.toTag());
        } else {
            tag.remove(UNSUSPICIOUSBLOCK_PENDING_JOURNAL_ENTRY_TAG);
        }
    }

    @Unique
    private void unsuspiciousblock$readLootTableData(CompoundTag tag) {
        if (!tag.getBoolean(UNSUSPICIOUSBLOCK_LOOT_TABLE_PARSED_TAG)) {
            this.unsuspiciousblock$lootTableParsed = false;
            this.unsuspiciousblock$lootTableName = null;
            this.unsuspiciousblock$pendingJournalEntry = null;
            return;
        }

        ResourceLocation lootTableName = tag.contains(UNSUSPICIOUSBLOCK_LOOT_TABLE_NAME_TAG)
                ? ResourceLocation.tryParse(tag.getString(UNSUSPICIOUSBLOCK_LOOT_TABLE_NAME_TAG))
                : null;
        this.unsuspiciousblock$lootTableParsed = lootTableName != null;
        this.unsuspiciousblock$lootTableName = lootTableName;
        this.unsuspiciousblock$pendingJournalEntry = tag.contains(UNSUSPICIOUSBLOCK_PENDING_JOURNAL_ENTRY_TAG, Tag.TAG_COMPOUND)
                ? ExcavationLogEntry.fromTag(tag.getCompound(UNSUSPICIOUSBLOCK_PENDING_JOURNAL_ENTRY_TAG))
                : null;
    }

    // ========== 扫描状态接口实现 ========== //

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
        this.unsuspiciousblock$pendingJournalEntry = null;
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

    // ========== 战利品表状态接口实现 ========== //

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
        return this.unsuspiciousblock$storedItem;
    }

    @Override
    public void unsuspiciousblock$setItem(ItemStack stack) {
        this.unsuspiciousblock$storedItem = stack;
    }

    // ========== 日志追踪接口实现 ========== //

    @Override
    @Nullable
    public ExcavationLogEntry unsuspiciousblock$getPendingJournalEntry() {
        return this.unsuspiciousblock$pendingJournalEntry;
    }

    @Override
    public void unsuspiciousblock$setPendingJournalEntry(@Nullable ExcavationLogEntry entry) {
        if (Objects.equals(this.unsuspiciousblock$pendingJournalEntry, entry)) {
            return;
        }
        this.unsuspiciousblock$pendingJournalEntry = entry;
        this.unsuspiciousblock$syncBlockEntity();
    }

    // ========== 战利品表解析 ========== //

    // 解析战利品表并返回生成的物品——使用原版 fill() 掷出战利品表
    @Override
    public ItemStack unsuspiciousblock$resolveAndGetLoot(Player player) {
        if (!(player instanceof ServerPlayer sp)) {
            return ItemStack.EMPTY;
        }

        BlockEntity be = this.unsuspiciousblock$asBlockEntity();
        Level level = be.getLevel();
        if (level == null || level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return ItemStack.EMPTY;
        }

        if (this.lootTable == null) {
            return ItemStack.EMPTY;
        }

        LootTable table = serverLevel.getServer().reloadableRegistries().getLootTable(this.lootTable);
        if (table == LootTable.EMPTY) {
            return ItemStack.EMPTY;
        }

        // 记录战利品表标识，供扫描仪 publish 日志用
        this.unsuspiciousblock$lootTableName = this.lootTable.location();
        this.unsuspiciousblock$lootTableParsed = true;

        LootParams params = new LootParams.Builder(serverLevel)
                .withParameter(LootContextParams.ORIGIN, be.getBlockPos().getCenter())
                .withParameter(LootContextParams.THIS_ENTITY, player)
                .withLuck(player.getLuck())
                .create(LootContextParamSets.CHEST);

        SimpleContainer tempContainer = new SimpleContainer(1);
        table.fill(tempContainer, params, this.lootTableSeed);

        this.unsuspiciousblock$storedItem = tempContainer.getItem(0).copy();
        return this.unsuspiciousblock$storedItem.copy();
    }

    // 标记方块实体已变更并同步到客户端
    @Override
    public void unsuspiciousblock$markBlockEntityChanged() {
        this.unsuspiciousblock$syncBlockEntity();
    }

    // ========== NBT 持久化注入 ========== //

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
}