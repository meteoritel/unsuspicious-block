package com.meteorite.unsuspiciousblock.mixin.block;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.blockentity.BrushableBlockEntityScanState;
import com.meteorite.unsuspiciousblock.enchantment.framework.EnchantmentManager;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerType;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.ArchaeologyLootRuntimeTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 为可疑方块补充扫描状态、战利品表状态与考古笔记追踪。
 */
@Mixin(BrushableBlockEntity.class)
public abstract class BrushableBlockEntityMixin implements BrushableBlockEntityScanState {
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

    @Shadow
    @Nullable
    private ResourceKey<LootTable> lootTable;

    @Shadow
    private ItemStack item;

    // 刷拭命中方向——原版 dropContent 依据此字段计算物品弹出位置
    @Shadow
    @Nullable
    private Direction hitDirection;

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

    // ========== 日志追踪字段 ========== //
    @Unique
    @Nullable
    private ExcavationLogEntry unsuspiciousblock$pendingJournalEntry;

    @Unique
    private boolean unsuspiciousblock$lootTableParsedThisCall;

    // ========== 刷拭上下文字段 ========== //
    @Unique
    private boolean unsuspiciousblock$brushContext;

    @Unique
    private long unsuspiciousblock$brushGameTime = -1L;

    @Unique
    private long unsuspiciousblock$brushDayTime = -1L;

    // HEAD 阶段扫描玩家双手缓存"正在使用的刷子"，供精掘翻倍查询附魔等级
    @Unique
    private ItemStack unsuspiciousblock$brushTool = ItemStack.EMPTY;

    // 精掘翻倍产生的额外战利品——在 dropContent 时一并发弹出
    @Unique
    private List<ItemStack> unsuspiciousblock$extraDrops = List.of();

    // ========== 扫描状态 NBT ========== //
    // 将扫描状态写入方块实体 NBT
    @Unique
    private void unsuspiciousblock$writeScanData(CompoundTag tag) {
        tag.putBoolean(UNSUSPICIOUSBLOCK_SCANNED_TAG, this.unsuspiciousblock$scanned);
        if (this.unsuspiciousblock$scanned && this.unsuspiciousblock$scannerUuid != null) {
            tag.putUUID(UNSUSPICIOUSBLOCK_SCANNER_UUID_TAG, this.unsuspiciousblock$scannerUuid);
        } else {
            tag.remove(UNSUSPICIOUSBLOCK_SCANNER_UUID_TAG);
        }
    }

    // 从方块实体 NBT 中读取扫描状态
    @Unique
    private void unsuspiciousblock$readScanData(CompoundTag tag) {
        this.unsuspiciousblock$scanned = tag.getBoolean(UNSUSPICIOUSBLOCK_SCANNED_TAG);
        this.unsuspiciousblock$scannerUuid = this.unsuspiciousblock$scanned && tag.contains(UNSUSPICIOUSBLOCK_SCANNER_UUID_TAG)
                ? tag.getUUID(UNSUSPICIOUSBLOCK_SCANNER_UUID_TAG)
                : null;
    }

    // ========== 战利品表状态 NBT ========== //
    // 将战利品表解析状态写入方块实体 NBT
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

    // 从方块实体 NBT 中读取战利品表解析状态
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

    // Mixin 运行时实例实际就是 BrushableBlockEntity，这里集中封装为 BlockEntity 访问，避免散落重复强转。
    @Unique
    @SuppressWarnings("DataFlowIssue")
    private BlockEntity unsuspiciousblock$asBlockEntity() {
        return (BlockEntity) (Object) this;
    }

    // 计算精掘翻倍后的总追踪物品——this.item 与 extraDrops 中相同物品的计数合并
    // 用于考古笔记日志记录，确保日志数量反映翻倍后的实际获取量而非原始数量
    @Unique
    private ItemStack unsuspiciousblock$computeTotalTrackedItem() {
        ItemStack total = this.item.copy();
        for (ItemStack extra : this.unsuspiciousblock$extraDrops) {
            if (!extra.isEmpty() && ItemStack.isSameItemSameComponents(total, extra)) {
                total.grow(extra.getCount());
            }
        }
        return total;
    }

    // 将状态变化同步回方块实体并通知区块更新
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

    // ========== 扫描状态接口实现 ========== //
    // 标记当前可疑方块已被指定玩家扫描
    @Override
    public void unsuspiciousblock$markScanned(UUID scannerUuid) {
        if (this.unsuspiciousblock$scanned && scannerUuid.equals(this.unsuspiciousblock$scannerUuid)) {
            return;
        }

        this.unsuspiciousblock$scanned = true;
        this.unsuspiciousblock$scannerUuid = scannerUuid;
        this.unsuspiciousblock$syncBlockEntity();
    }

    // 清除当前可疑方块的扫描标记
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

    // 返回当前可疑方块是否已被扫描
    @Override
    public boolean unsuspiciousblock$isScanned() {
        return this.unsuspiciousblock$scanned;
    }

    // 返回记录的扫描者 UUID
    @Override
    @Nullable
    public UUID unsuspiciousblock$getScannerUuid() {
        return this.unsuspiciousblock$scannerUuid;
    }

    // ========== 战利品表状态接口实现 ========== //
    // 返回当前缓存的战利品表名称
    @Override
    @Nullable
    public ResourceLocation unsuspiciousblock$getLootTableName() {
        return this.unsuspiciousblock$lootTableName;
    }

    // 返回当前可疑方块是否已经解析过战利品表
    @Override
    public boolean unsuspiciousblock$isLootTableParsed() {
        return this.unsuspiciousblock$lootTableParsed;
    }

    // 返回当前缓存的战利品
    @Override
    public ItemStack unsuspiciousblock$getItem() {
        return this.item;
    }

    // 更新当前战利品
    @Override
    public void unsuspiciousblock$setItem(ItemStack stack) {
        this.item = stack;
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

    // ========== Mixin 注入 ========== //
    // 在刷拭开始时记录本次刷拭与时间信息，并缓存实际使用的刷子
    @Inject(method = "brush", at = @At("HEAD"))
    private void unsuspiciousblock$onBrushStart(long gameTime, Player player, net.minecraft.core.Direction direction, CallbackInfoReturnable<Boolean> cir) {
        this.unsuspiciousblock$brushContext = true;
        this.unsuspiciousblock$brushGameTime = gameTime;
        this.unsuspiciousblock$brushDayTime = player.level().getDayTime();
        // brush() 签名不携带 ItemStack，此处扫描双手确定实际使用的刷子：
        // 主手优先——原版 BrushItem.useOn 用 context.getItemInHand() 调用 brush()，
        // 若玩家副手持刷，主手为空或非刷子时，使用副手刷子
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.is(Items.BRUSH)) {
            this.unsuspiciousblock$brushTool = mainHand;
        } else {
            ItemStack offHand = player.getOffhandItem();
            this.unsuspiciousblock$brushTool = offHand.is(Items.BRUSH) ? offHand : ItemStack.EMPTY;
        }
    }

    // 在刷拭结束时清理本次刷拭信息
    @Inject(method = "brush", at = @At("TAIL"))
    private void unsuspiciousblock$onBrushEnd(long gameTime, Player player, net.minecraft.core.Direction direction, CallbackInfoReturnable<Boolean> cir) {
        this.unsuspiciousblock$brushContext = false;
        this.unsuspiciousblock$brushGameTime = -1L;
        this.unsuspiciousblock$brushDayTime = -1L;
        this.unsuspiciousblock$brushTool = ItemStack.EMPTY;
    }

    // 在可疑方块真正掉出物品时抽取精掘翻倍，弹出额外副本，并记录获得次数写入日志
    @Inject(method = "dropContent", at = @At("HEAD"))
    private void unsuspiciousblock$onBrushItemDrop(Player player, CallbackInfo ci) {
        // 精掘翻倍：在刷子刷出物品时抽取一次，决定当前物品是否翻倍
        // 时序在记录考古笔记物品数量之前，确保日志期望值保留原战利品表结果，实际值按翻倍后总数结算
        if (this.unsuspiciousblock$lootTableName != null && player instanceof ServerPlayer sp && !this.item.isEmpty()) {
            BlockEntity blockEntity = this.unsuspiciousblock$asBlockEntity();
            TriggerContext peCtx = TriggerContext.builder(sp, sp.serverLevel())
                    .pos(blockEntity.getBlockPos())
                    .tool(this.unsuspiciousblock$brushTool)
                    .build();
            List<ItemStack> drops = EnchantmentManager.dispatchValue(
                    TriggerType.BRUSH_ITEM_DROP, peCtx, List.of(this.item));
            if (!drops.isEmpty()) {
                this.item = drops.getFirst();
                if (drops.size() > 1) {
                    this.unsuspiciousblock$extraDrops = new ArrayList<>(drops.subList(1, drops.size()));
                }
            }
        }

        // 先计算翻倍后的总追踪物品——extraDrops 清空后无法再计算
        ItemStack totalTrackedItem = this.unsuspiciousblock$computeTotalTrackedItem();

        // 精掘翻倍：先弹出额外战利品副本
        if (!this.unsuspiciousblock$extraDrops.isEmpty()) {
            BlockEntity blockEntity = this.unsuspiciousblock$asBlockEntity();
            Level level = blockEntity.getLevel();
            BlockPos pos = blockEntity.getBlockPos();
            if (level != null) {
                // 原版 dropContent 的位置公式：方块在 hitDirection 方向外偏移 1 格的中心
                double d0 = EntityType.ITEM.getWidth();
                double d1 = 1.0 - d0;
                double d2 = d0 / 2.0;
                Direction direction = Objects.requireNonNullElse(this.hitDirection, Direction.UP);
                BlockPos blockpos = pos.relative(direction, 1);
                double d3 = (double) blockpos.getX() + 0.5 * d1 + d2;
                double d4 = (double) blockpos.getY() + 0.5 + (double) (EntityType.ITEM.getHeight() / 2.0F);
                double d5 = (double) blockpos.getZ() + 0.5 * d1 + d2;
                for (ItemStack extra : this.unsuspiciousblock$extraDrops) {
                    if (!extra.isEmpty()) {
                        ItemEntity itemEntity = new ItemEntity(level, d3, d4, d5, extra.copy());
                        itemEntity.setDeltaMovement(Vec3.ZERO);
                        level.addFreshEntity(itemEntity);
                    }
                }
            }
            this.unsuspiciousblock$extraDrops = List.of();
        }

        if (this.unsuspiciousblock$lootTableName == null || !(player instanceof ServerPlayer sp) || this.item.isEmpty()) {
            return;
        }

        long gameTime = this.unsuspiciousblock$brushGameTime >= 0L
                ? this.unsuspiciousblock$brushGameTime
                : sp.serverLevel().getGameTime();
        long dayTime = this.unsuspiciousblock$brushDayTime >= 0L
                ? this.unsuspiciousblock$brushDayTime
                : sp.serverLevel().getDayTime();
        // 使用翻倍后的总计数结算待定日志条目，确保考古手册日志的 actualLoot 反映翻倍后的实际获取量
        ArchaeologyLootRuntimeTracker.applyPendingLoot(sp, this.unsuspiciousblock$lootTableName,
                this.unsuspiciousblock$pendingJournalEntry, totalTrackedItem, gameTime, dayTime);
        this.unsuspiciousblock$setPendingJournalEntry(null);
    }

    // 在方块实体保存附加数据时写入扫描与战利品表状态
    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void unsuspiciousblock$saveExtraData(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        this.unsuspiciousblock$writeScanData(tag);
        this.unsuspiciousblock$writeLootTableData(tag);
    }

    // 在方块实体读取附加数据时恢复扫描与战利品表状态
    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void unsuspiciousblock$loadExtraData(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        this.unsuspiciousblock$readScanData(tag);
        this.unsuspiciousblock$readLootTableData(tag);
    }

    // 在同步更新包时附带扫描与战利品表状态
    @Inject(method = "getUpdateTag", at = @At("RETURN"))
    private void unsuspiciousblock$appendExtraDataToUpdateTag(HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> cir) {
        this.unsuspiciousblock$writeScanData(cir.getReturnValue());
        this.unsuspiciousblock$writeLootTableData(cir.getReturnValue());
    }

    // 在战利品表解析前捕获本次可疑方块使用的表标识
    @Inject(method = "unpackLootTable", at = @At("HEAD"))
    private void unsuspiciousblock$captureLootTableName(Player player, CallbackInfo ci) {
        this.unsuspiciousblock$lootTableParsedThisCall = false;
        Level level = this.unsuspiciousblock$asBlockEntity().getLevel();
        if (this.lootTable == null || level == null || level.isClientSide() || level.getServer() == null) {
            return;
        }

        this.unsuspiciousblock$lootTableName = this.lootTable.location();
        this.unsuspiciousblock$lootTableParsed = true;
        this.unsuspiciousblock$lootTableParsedThisCall = true;
    }

    // 在战利品表解析后同步考古笔记状态并刷新方块实体
    @Inject(method = "unpackLootTable", at = @At("TAIL"))
    private void unsuspiciousblock$syncLootTableState(Player player, CallbackInfo ci) {
        // parsedThisCall=true 表示本次实际解析了战利品表（this.lootTable 原先非 null，原版 roll 后置 null）
        // 原版 brush() 每个 tick 都调用 unpackLootTable，但只有首次调用实际 roll 战利品，后续都是空操作
        boolean parsedThisCall = this.unsuspiciousblock$lootTableParsedThisCall;
        this.unsuspiciousblock$lootTableParsedThisCall = false;

        // 仅在实际生成战利品时处理——避免刷拭过程中每次空调用都重复 dispatch 精掘
        if (!parsedThisCall) {
            return;
        }

        // 仅在刷拭上下文中处理精掘与日志——扫描仪路径由 SuspiciousReaderItem 自行处理追踪
        if (!this.unsuspiciousblock$brushContext || this.unsuspiciousblock$lootTableName == null
                || !(player instanceof ServerPlayer sp) || this.item.isEmpty()) {
            this.unsuspiciousblock$syncBlockEntity();
            return;
        }

        // 记录战利品表原始解析结果（翻倍前），作为考古日志的期望值
        ItemStack originalItem = this.item.copy();

        long gameTime = this.unsuspiciousblock$brushGameTime >= 0L
                ? this.unsuspiciousblock$brushGameTime
                : sp.serverLevel().getGameTime();
        long dayTime = this.unsuspiciousblock$brushDayTime >= 0L
                ? this.unsuspiciousblock$brushDayTime
                : sp.serverLevel().getDayTime();

        // 首次发现记录使用原始战利品——解锁与首次发现时间基于战利品表结果
        ArchaeologyLootRuntimeTracker.onLootDiscovered(sp, this.unsuspiciousblock$lootTableName,
                originalItem, LootSourceType.ARCHAEOLOGY, gameTime, dayTime);

        BlockEntity blockEntity = this.unsuspiciousblock$asBlockEntity();

        // 创建待定日志条目，期望值 = 原始战利品表结果（翻倍前）
        // 精掘翻倍在 dropContent 时抽取，actualLoot 按翻倍后总数结算
        this.unsuspiciousblock$setPendingJournalEntry(ArchaeologyLootRuntimeTracker.createPendingEntry(
                sp,
                this.unsuspiciousblock$lootTableName,
                LootSourceType.ARCHAEOLOGY,
                BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock()),
                blockEntity.getBlockPos(),
                originalItem,
                gameTime,
                dayTime
        ));

        this.unsuspiciousblock$syncBlockEntity();
    }

    // 重新设置战利品表时清空旧的解析状态
    @Inject(method = "setLootTable", at = @At("TAIL"))
    private void unsuspiciousblock$clearLootTableState(ResourceKey<LootTable> lootTable, long seed, CallbackInfo ci) {
        boolean hadLootTableState = this.unsuspiciousblock$lootTableParsed || this.unsuspiciousblock$lootTableName != null
                || this.unsuspiciousblock$pendingJournalEntry != null;
        this.unsuspiciousblock$lootTableName = null;
        this.unsuspiciousblock$lootTableParsed = false;
        this.unsuspiciousblock$pendingJournalEntry = null;
        this.unsuspiciousblock$lootTableParsedThisCall = false;
        this.unsuspiciousblock$extraDrops = List.of();
        if (hadLootTableState) {
            this.unsuspiciousblock$syncBlockEntity();
        }
    }
}
