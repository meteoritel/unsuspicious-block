package com.meteorite.unsuspiciousblock.core.blockentity;

import com.meteorite.unsuspiciousblock.core.block.SealedContents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * 保存不可疑方块的封存物品、封存者身份与扫描状态。
 */
public class UnsuspiciousBlockEntity extends BlockEntity implements BrushableBlockEntityScanState {
    private static final String TAG_CONTENTS = "SealedContents";
    private static final String TAG_CRAFTER_UUID = "CrafterUuid";
    private static final String TAG_CRAFTER_NAME = "CrafterName";

    private ItemStack sealedItem = ItemStack.EMPTY;
    @Nullable
    private SealedContents.CrafterIdentity crafter;
    private final ScanIdentity scan = new ScanIdentity(ScanIdentity.Layout.SEALED);

    public UnsuspiciousBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.UNSUSPICIOUS_BLOCK.get(), pos, state);
    }

    // 从被放置的方块物品复制封存内容与身份快照
    public void loadFromItem(ItemStack carrier) {
        this.sealedItem = SealedContents.getSealedItem(carrier);
        this.crafter = SealedContents.getCrafter(carrier).orElse(null);
        this.scan.clear();
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
        this.scan.write(tag);
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
        this.scan.read(tag);
    }

    @Override
    public ScanIdentity unsuspiciousblock$scanIdentity() {
        return this.scan;
    }

    @Override
    public @Nullable ResourceLocation unsuspiciousblock$getLootTableName() {
        return null;
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
    public ItemStack unsuspiciousblock$resolveAndGetLoot(Player player) {
        return this.sealedItem.copy();
    }

    @Override
    public void unsuspiciousblock$markBlockEntityChanged() {
        this.markChanged();
    }

    private void markChanged() {
        BlockEntitySync.markChanged(this, level -> this.getBlockState());
    }
}
