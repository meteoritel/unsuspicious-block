package com.meteorite.unsuspiciousblock.core.mixin.compat.lootr;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.meteorite.unsuspiciousblock.core.block.UnsuspiciousBlockInteractions;
import com.meteorite.unsuspiciousblock.core.blockentity.BlockEntitySync;
import com.meteorite.unsuspiciousblock.core.blockentity.BrushableBlockEntityScanState;
import com.meteorite.unsuspiciousblock.core.blockentity.BrushableLootDropHelper;
import com.meteorite.unsuspiciousblock.core.blockentity.BrushableLootDropHelper.BrushStroke;
import com.meteorite.unsuspiciousblock.core.blockentity.BrushableTracking;
import com.meteorite.unsuspiciousblock.core.blockentity.ScanIdentity;
import com.meteorite.unsuspiciousblock.core.journal.state.ExcavationLogEntry;
import com.meteorite.unsuspiciousblock.core.journal.tracking.ContainerLootTracking;
import com.meteorite.unsuspiciousblock.core.journal.tracking.GeneratedLootInventory;
import com.meteorite.unsuspiciousblock.core.journal.tracking.InventoryLootTracking;
import com.meteorite.unsuspiciousblock.core.loottable.signature.LootResultSignature;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootTable;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.data.ILootrSavedData;
import noobanidus.mods.lootr.common.block.entity.LootrBrushableBlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.UUID;

/** 将扫描授权绑定到真实 Lootr 库存；刷拭和考古铲均由实际库存结算。 */
@Mixin(LootrBrushableBlockEntity.class)
public abstract class LootrBrushableBlockEntityMixin implements BrushableBlockEntityScanState {
    @Unique
    private static final String unsuspiciousblock$SCAN_GENERATION = "unsuspiciousblock_scan_inventory_generation";
    @Unique
    private static final int unsuspiciousblock$LOOT_SLOT = 0;
    @Unique
    private final BrushableTracking unsuspiciousblock$scan = new BrushableTracking(this::unsuspiciousblock$markBlockEntityChanged);
    @Unique
    private @Nullable UUID unsuspiciousblock$scanGeneration;

    @Override
    public ScanIdentity unsuspiciousblock$scanIdentity() {
        return unsuspiciousblock$scan.scan();
    }

    @Override
    public void unsuspiciousblock$onScanCleared() {
        unsuspiciousblock$scanGeneration = null;
        unsuspiciousblock$scan.clearResolved();
    }

    @Override
    public ItemStack unsuspiciousblock$getItem() {
        return unsuspiciousblock$host().getItem();
    }

    @Override
    public @Nullable ResourceLocation unsuspiciousblock$getLootTableName() {
        ResourceKey<LootTable> table = unsuspiciousblock$host().getInfoLootTable();
        return table == null ? null : table.location();
    }

    @Override
    public ItemStack unsuspiciousblock$scanFor(ServerPlayer player) {
        // 新生成的库存不参与旧扫描迁移。
        if (unsuspiciousblock$scanGeneration == null && unsuspiciousblock$scan.pending().current() != null
                && unsuspiciousblock$isScanner(player.getUUID())) {
            GeneratedLootInventory existing = unsuspiciousblock$existingInventory(player);
            if (existing != null) unsuspiciousblock$migrateScan(player, existing);
        }
        if (!(LootrAPI.getInventory(unsuspiciousblock$host(), player) instanceof GeneratedLootInventory inventory)) {
            return ItemStack.EMPTY;
        }
        unsuspiciousblock$scan.clearResolved();
        UUID generation = inventory.unsuspiciousblock$inventoryLoot().bindGeneration();
        boolean changedInventory = !generation.equals(unsuspiciousblock$scanGeneration);
        unsuspiciousblock$scanGeneration = generation;
        if (unsuspiciousblock$scanIdentity().mark(player.getUUID()) || changedInventory) {
            unsuspiciousblock$markBlockEntityChanged();
        }
        return inventory.getItem(unsuspiciousblock$LOOT_SLOT).copy();
    }

    @Override
    public Extraction unsuspiciousblock$takeScannedLoot(ServerPlayer player) {
        if (!unsuspiciousblock$isScanner(player.getUUID())) return Extraction.EMPTY;
        GeneratedLootInventory inventory = unsuspiciousblock$existingInventory(player);
        if (inventory == null) {
            unsuspiciousblock$clearScanned();
            return Extraction.EMPTY;
        }
        unsuspiciousblock$migrateScan(player, inventory);
        InventoryLootTracking tracking = inventory.unsuspiciousblock$inventoryLoot();
        if (unsuspiciousblock$scanGeneration == null || !unsuspiciousblock$scanGeneration.equals(tracking.generation())) {
            unsuspiciousblock$clearScanned();
            return Extraction.EMPTY;
        }
        ItemStack item = inventory.removeItemNoUpdate(unsuspiciousblock$LOOT_SLOT);
        if (item.isEmpty()) {
            unsuspiciousblock$clearScanned();
            return Extraction.EMPTY;
        }
        ItemStack original = item.copy();
        return new Extraction(item, delivered -> tracking.acquireBrushLoot(player, original, delivered.getCount()));
    }

    @Override
    public void unsuspiciousblock$markBlockEntityChanged() {
        LootrBrushableBlockEntity host = unsuspiciousblock$host();
        BlockEntitySync.markChanged(host, level -> host.getBlockState());
    }

    @Unique
    private LootrBrushableBlockEntity unsuspiciousblock$host() {
        return (LootrBrushableBlockEntity) (Object) this;
    }

    @Unique
    private @Nullable GeneratedLootInventory unsuspiciousblock$existingInventory(ServerPlayer player) {
        ILootrSavedData data = LootrAPI.getData(unsuspiciousblock$host());
        return data != null && data.getInventory(player) instanceof GeneratedLootInventory inventory ? inventory : null;
    }

    @Unique
    private void unsuspiciousblock$migrateScan(ServerPlayer player, GeneratedLootInventory inventory) {
        if (unsuspiciousblock$scanGeneration != null || !unsuspiciousblock$isScanner(player.getUUID())) return;
        ExcavationLogEntry source = unsuspiciousblock$scan.pending().current();
        ResourceLocation table = unsuspiciousblock$scan.table();
        if (source == null || table == null || !table.equals(unsuspiciousblock$getLootTableName())) return;
        List<LootResultSignature> signatures = source.expectedLoot().keySet().stream()
                .map(LootResultSignature::fromStoredKey).toList();
        if (signatures.isEmpty() || !ContainerLootTracking.collect(inventory, signatures).equals(source.expectedLoot())) return;
        InventoryLootTracking tracking = inventory.unsuspiciousblock$inventoryLoot();
        if (!tracking.hasSource()) tracking.adoptScannedSource(table, source);
        unsuspiciousblock$scanGeneration = tracking.bindGeneration();
        unsuspiciousblock$scan.clearResolved();
    }

    // 旧扫描需要在 Lootr 清槽前核对库存内容。
    @Inject(method = "popItem", at = @At("HEAD"), remap = false)
    private void unsuspiciousblock$restoreScanBeforePop(Player player, CallbackInfoReturnable<ItemStack> cir) {
        if (unsuspiciousblock$scanGeneration != null || unsuspiciousblock$scan.pending().current() == null
                || !(player instanceof ServerPlayer serverPlayer) || !unsuspiciousblock$isScanner(player.getUUID())) return;
        GeneratedLootInventory inventory = unsuspiciousblock$existingInventory(serverPlayer);
        if (inventory != null) unsuspiciousblock$migrateScan(serverPlayer, inventory);
    }

    @ModifyVariable(method = "dropContent", at = @At("STORE"), name = "theItem", remap = false, require = 1, allow = 1)
    private ItemStack unsuspiciousblock$prepareDrop(ItemStack theItem, Player player,
            @Share("lootInventory") LocalRef<GeneratedLootInventory> inventoryRef,
            @Share("originalLoot") LocalRef<ItemStack> originalRef,
            @Share("extraDropCount") LocalIntRef extraCount) {
        if (!(player instanceof ServerPlayer serverPlayer) || theItem.isEmpty()) return theItem;
        inventoryRef.set(unsuspiciousblock$existingInventory(serverPlayer));
        ItemStack original = theItem.copy();
        originalRef.set(original);
        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        ItemStack tool = UnsuspiciousBlockInteractions.isBrush(mainHand) ? mainHand
                : UnsuspiciousBlockInteractions.isBrush(offHand) ? offHand : ItemStack.EMPTY;
        LootrBrushableBlockEntity host = unsuspiciousblock$host();
        BrushStroke context = new BrushStroke(serverPlayer, host.getBlockPos(), host.getHitDirection(), tool);
        var drop = BrushableLootDropHelper.rollBrushItemDrop(context, theItem);
        for (ItemStack extra : BrushableLootDropHelper.spawnExtraDrops(context, drop.extraDrops())) {
            if (ItemStack.isSameItemSameComponents(original, extra)) {
                extraCount.set(extraCount.get() + extra.getCount());
            }
        }
        return drop.rolledItem();
    }

    @WrapOperation(method = "dropContent", remap = false, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z", remap = true))
    private boolean unsuspiciousblock$recordDrop(Level level, Entity entity, Operation<Boolean> spawn,
            @Local(argsOnly = true) Player player,
            @Share("lootInventory") LocalRef<GeneratedLootInventory> inventoryRef,
            @Share("originalLoot") LocalRef<ItemStack> originalRef,
            @Share("extraDropCount") LocalIntRef extraCount) {
        boolean spawned = spawn.call(level, entity);
        GeneratedLootInventory inventory = inventoryRef.get();
        if (inventory != null && player instanceof ServerPlayer serverPlayer) {
            ItemStack main = ((ItemEntity) entity).getItem();
            ItemStack origin = originalRef.get();
            int mainCount = spawned && ItemStack.isSameItemSameComponents(origin, main) ? main.getCount() : 0;
            InventoryLootTracking tracking = inventory.unsuspiciousblock$inventoryLoot();
            tracking.acquireBrushLoot(serverPlayer, origin, mainCount + extraCount.get());
            if (unsuspiciousblock$scanGeneration != null && unsuspiciousblock$scanGeneration.equals(tracking.generation())) {
                unsuspiciousblock$clearScanned();
            }
        }
        return spawned;
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void unsuspiciousblock$saveScan(CompoundTag compoundTag, HolderLookup.Provider provider, CallbackInfo ci) {
        unsuspiciousblock$scan.write(compoundTag);
        if (unsuspiciousblock$scanGeneration != null) {
            compoundTag.putUUID(unsuspiciousblock$SCAN_GENERATION, unsuspiciousblock$scanGeneration);
        }
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void unsuspiciousblock$loadScan(CompoundTag compoundTag, HolderLookup.Provider provider, CallbackInfo ci) {
        unsuspiciousblock$scan.read(compoundTag);
        unsuspiciousblock$scanGeneration = compoundTag.hasUUID(unsuspiciousblock$SCAN_GENERATION)
                ? compoundTag.getUUID(unsuspiciousblock$SCAN_GENERATION) : null;
    }

    @Inject(method = "getUpdateTag", at = @At("RETURN"))
    private void unsuspiciousblock$appendScanIdentity(HolderLookup.Provider provider, CallbackInfoReturnable<CompoundTag> cir) {
        unsuspiciousblock$scanIdentity().write(cir.getReturnValue());
    }

    @Inject(method = "setLootTableInternal", at = @At("TAIL"), remap = false)
    private void unsuspiciousblock$clearReplacedScan(ResourceKey<LootTable> lootTable, long seed, CallbackInfo ci) {
        unsuspiciousblock$clearScanned();
    }
}
