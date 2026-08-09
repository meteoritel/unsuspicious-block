package com.meteorite.unsuspiciousblock.mixin.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.meteorite.unsuspiciousblock.block.UnsuspiciousBlockInteractions;
import com.meteorite.unsuspiciousblock.blockentity.BrushableBlockEntityScanState;
import com.meteorite.unsuspiciousblock.blockentity.BrushableLootDropHelper;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.LootSession;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContext;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContextHolder;
import com.meteorite.unsuspiciousblock.journal.tracking.event.LootTrackingEvents;
import com.meteorite.unsuspiciousblock.journal.tracking.settlement.LootSettlementStrategies;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
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

    // 解析战利品表并返回生成的物品——委托给原版 unpackLootTable
    @Override
    public ItemStack unsuspiciousblock$resolveAndGetLoot(Player player) {
        ((BrushableBlockEntity) (Object) this).unpackLootTable(player);
        return this.item.copy();
    }

    // 标记方块实体已变更并同步到客户端
    @Override
    public void unsuspiciousblock$markBlockEntityChanged() {
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
        if (UnsuspiciousBlockInteractions.isBrush(mainHand)) {
            this.unsuspiciousblock$brushTool = mainHand;
        } else {
            ItemStack offHand = player.getOffhandItem();
            this.unsuspiciousblock$brushTool = UnsuspiciousBlockInteractions.isBrush(offHand)
                    ? offHand : ItemStack.EMPTY;
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
        // 仅在服务端、且有战利品表、且物品非空时走完整精掘+日志流程
        if (this.unsuspiciousblock$lootTableName == null
                || !(player instanceof ServerPlayer sp)
                || this.item.isEmpty()) {
            return;
        }

        BlockEntity blockEntity = this.unsuspiciousblock$asBlockEntity();
        Level level = blockEntity.getLevel();
        BrushableLootDropHelper.DropContext ctx = new BrushableLootDropHelper.DropContext(
                sp, blockEntity.getBlockPos(), level, this.hitDirection,
                this.unsuspiciousblock$brushTool,
                this.unsuspiciousblock$brushGameTime, this.unsuspiciousblock$brushDayTime,
                this.unsuspiciousblock$lootTableName, this.unsuspiciousblock$pendingJournalEntry);

        // 精掘翻倍：dispatch 后更新主物品，额外副本由 helper 弹出
        BrushableLootDropHelper.DropResult result =
                BrushableLootDropHelper.rollBrushItemDrop(ctx, this.item);
        this.item = result.rolledItem();
        BrushableLootDropHelper.spawnExtraDrops(ctx, result.extraDrops());

        // 使用翻倍后的总计数结算待定日志条目
        BrushableLootDropHelper.settleJournal(ctx, result.totalTrackedItem());
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

    // 在战利品表解析前捕获本次可疑方块使用的表标识与追踪上下文
    @Inject(method = "unpackLootTable", at = @At("HEAD"))
    private void unsuspiciousblock$captureLootTableName(Player player, CallbackInfo ci,
                                                        @Share("trackingContext") LocalRef<LootTrackingContext> trackingContext,
                                                        @Share("lootSession") LocalRef<LootSession> lootSession) {
        trackingContext.set(null);
        lootSession.set(null);
        this.unsuspiciousblock$lootTableParsedThisCall = false;
        Level level = this.unsuspiciousblock$asBlockEntity().getLevel();
        if (this.lootTable == null || level == null || level.isClientSide() || level.getServer() == null) {
            return;
        }

        this.unsuspiciousblock$lootTableName = this.lootTable.location();
        this.unsuspiciousblock$lootTableParsed = true;
        this.unsuspiciousblock$lootTableParsedThisCall = true;

        // 命中追踪规则时准备上下文，由 getRandomItems 调用点建立异常安全的作用域
        if (ArchaeologyJournalServerCatalog.isTrackedTable(this.unsuspiciousblock$lootTableName)
                && player instanceof ServerPlayer sp
                && level instanceof ServerLevel serverLevel) {
            long gameTime = this.unsuspiciousblock$brushContext && this.unsuspiciousblock$brushGameTime >= 0L
                    ? this.unsuspiciousblock$brushGameTime
                    : serverLevel.getGameTime();
            long dayTime = this.unsuspiciousblock$brushContext && this.unsuspiciousblock$brushDayTime >= 0L
                    ? this.unsuspiciousblock$brushDayTime
                    : serverLevel.getDayTime();
            BlockEntity blockEntity = this.unsuspiciousblock$asBlockEntity();
            BlockPos pos = blockEntity.getBlockPos();
            ResourceLocation sourceBlockId = BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock());
            LootTrackingContext context = LootTrackingContext.root(
                    sp, this.unsuspiciousblock$lootTableName, LootSourceType.ARCHAEOLOGY,
                    gameTime, dayTime, pos, sourceBlockId);
            trackingContext.set(context);
            lootSession.set(new LootSession(context));
        }
    }

    // 仅在 LootTable.getRandomItems 执行期间激活上下文，异常时由 Scope 自动恢复栈
    @WrapOperation(
            method = "unpackLootTable",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;J)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;")
    )
    private ObjectArrayList<ItemStack> unsuspiciousblock$scopeLootRoll(
            LootTable lootTable, LootParams lootParams, long seed,
            Operation<ObjectArrayList<ItemStack>> original,
            @Share("trackingContext") LocalRef<LootTrackingContext> trackingContext,
            @Share("lootSession") LocalRef<LootSession> lootSession) {
        try (LootTrackingContextHolder.Scope ignored = LootTrackingContextHolder.open(
                lootSession.get(), trackingContext.get())) {
            return original.call(lootTable, lootParams, seed);
        }
    }

    // 在战利品表解析后同步考古笔记状态并刷新方块实体
    @Inject(method = "unpackLootTable", at = @At("TAIL"))
    private void unsuspiciousblock$syncLootTableState(Player player, CallbackInfo ci,
                                                      @Share("lootSession") LocalRef<LootSession> lootSession) {
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
        LootSession session = lootSession.get();
        if (session != null) {
            LootTrackingEvents.submit(session, originalItem,
                    LootSettlementStrategies.deferred(this::unsuspiciousblock$setPendingJournalEntry));
        }

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
        if (hadLootTableState) {
            this.unsuspiciousblock$syncBlockEntity();
        }
    }
}
