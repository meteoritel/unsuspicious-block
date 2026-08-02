package com.meteorite.unsuspiciousblock.blockentity;

import com.meteorite.unsuspiciousblock.block.SealedContents;
import com.meteorite.unsuspiciousblock.journal.state.ExcavationLogEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 保存不可疑方块的封存物品、封存者身份与扫描状态。
 */
public class UnsuspiciousBlockEntity extends BlockEntity implements BrushableBlockEntityScanState {
    private static final String TAG_CONTENTS = "SealedContents";
    private static final String TAG_CRAFTER_UUID = "CrafterUuid";
    private static final String TAG_CRAFTER_NAME = "CrafterName";
    private static final String TAG_SCANNER_UUID = "ScannerUuid";

    private ItemStack sealedItem = ItemStack.EMPTY;
    @Nullable
    private SealedContents.CrafterIdentity crafter;
    @Nullable
    private UUID scannerUuid;

    public UnsuspiciousBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.UNSUSPICIOUS_BLOCK.get(), pos, state);
    }

    // 从被放置的方块物品复制封存内容与身份快照
    public void loadFromItem(ItemStack carrier) {
        this.sealedItem = SealedContents.getSealedItem(carrier);
        this.crafter = SealedContents.getCrafter(carrier).orElse(null);
        this.scannerUuid = null;
        this.markChanged();
    }

    // 返回封存者身份；缺失时由扫描仪显示为未知
    public Optional<SealedContents.CrafterIdentity> getCrafter() {
        return Optional.ofNullable(this.crafter);
    }

    // 取出并清空封存物，保证同一次方块移除最多生成一份掉落
    public ItemStack takeSealedItem() {
        ItemStack result = this.sealedItem;
        this.sealedItem = ItemStack.EMPTY;
        this.setChanged();
        return result;
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.saveAdditional(tag, registries);

        CompoundTag contentsTag = new CompoundTag();
        NonNullList<ItemStack> contents = NonNullList.withSize(1, ItemStack.EMPTY);
        contents.set(0, this.sealedItem);
        ContainerHelper.saveAllItems(contentsTag, contents, registries);
        tag.put(TAG_CONTENTS, contentsTag);

        if (this.crafter != null) {
            tag.putUUID(TAG_CRAFTER_UUID, this.crafter.uuid());
            tag.putString(TAG_CRAFTER_NAME, this.crafter.name());
        }
        if (this.scannerUuid != null) {
            tag.putUUID(TAG_SCANNER_UUID, this.scannerUuid);
        }
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.loadAdditional(tag, registries);

        NonNullList<ItemStack> contents = NonNullList.withSize(1, ItemStack.EMPTY);
        if (tag.contains(TAG_CONTENTS, Tag.TAG_COMPOUND)) {
            ContainerHelper.loadAllItems(tag.getCompound(TAG_CONTENTS), contents, registries);
        }
        this.sealedItem = contents.getFirst();

        this.crafter = tag.hasUUID(TAG_CRAFTER_UUID)
                ? new SealedContents.CrafterIdentity(
                        tag.getUUID(TAG_CRAFTER_UUID), tag.getString(TAG_CRAFTER_NAME))
                : null;
        this.scannerUuid = tag.hasUUID(TAG_SCANNER_UUID) ? tag.getUUID(TAG_SCANNER_UUID) : null;
    }

    @Override
    public void unsuspiciousblock$markScanned(UUID scannerUuid) {
        if (!Objects.equals(this.scannerUuid, scannerUuid)) {
            this.scannerUuid = scannerUuid;
            this.markChanged();
        }
    }

    @Override
    public void unsuspiciousblock$clearScanned() {
        if (this.scannerUuid != null) {
            this.scannerUuid = null;
            this.markChanged();
        }
    }

    @Override
    public boolean unsuspiciousblock$isScanned() {
        return this.scannerUuid != null;
    }

    @Override
    public @Nullable UUID unsuspiciousblock$getScannerUuid() {
        return this.scannerUuid;
    }

    @Override
    public @Nullable ResourceLocation unsuspiciousblock$getLootTableName() {
        return null;
    }

    @Override
    public boolean unsuspiciousblock$isLootTableParsed() {
        return true;
    }

    @Override
    public ItemStack unsuspiciousblock$getItem() {
        return this.sealedItem;
    }

    @Override
    public void unsuspiciousblock$setItem(ItemStack stack) {
        this.sealedItem = stack.copy();
    }

    @Override
    public @Nullable ExcavationLogEntry unsuspiciousblock$getPendingJournalEntry() {
        return null;
    }

    @Override
    public void unsuspiciousblock$setPendingJournalEntry(@Nullable ExcavationLogEntry entry) {
        // 玩家封存物品不来自战利品表，无需写入考古日志待结算状态
    }

    @Override
    public ItemStack unsuspiciousblock$resolveAndGetLoot(Player player) {
        return this.sealedItem.copy();
    }

    @Override
    public void unsuspiciousblock$markBlockEntityChanged() {
        this.markChanged();
    }

    // 标记区块数据脏状态；无需向客户端广播内部封存物品
    private void markChanged() {
        this.setChanged();
        Level level = this.getLevel();
        if (level != null && !level.isClientSide()) {
            BlockState state = this.getBlockState();
            level.sendBlockUpdated(this.getBlockPos(), state, state, 3);
        }
    }
}
