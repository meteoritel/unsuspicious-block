package com.meteorite.unsuspiciousblock.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.PotDecorations;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

/** 保存未烧制陶罐四面纹饰，并负责转换为可烧制的物品组件。 */
public class UnfiredDecoratedPotBlockEntity extends BlockEntity {
    private PotDecorations decorations = PotDecorations.EMPTY;

    public UnfiredDecoratedPotBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.UNFIRED_DECORATED_POT.get(), pos, state);
    }

    public void setFromItem(ItemStack stack) {
        this.decorations = stack.getOrDefault(DataComponents.POT_DECORATIONS, PotDecorations.EMPTY);
        setChanged();
    }

    public ItemStack getPotAsItem() {
        ItemStack result = new ItemStack(com.meteorite.unsuspiciousblock.item.ModItems.UNFIRED_DECORATED_POT);
        result.set(DataComponents.POT_DECORATIONS, decorations);
        return result;
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Decorations", decorations.save(new CompoundTag()));
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Decorations", CompoundTag.TAG_COMPOUND)) {
            decorations = PotDecorations.load(tag.getCompound("Decorations"));
        }
    }
}
