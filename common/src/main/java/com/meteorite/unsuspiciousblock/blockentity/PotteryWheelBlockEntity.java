package com.meteorite.unsuspiciousblock.blockentity;

import com.meteorite.unsuspiciousblock.block.PotteryWheelBlock;
import com.meteorite.unsuspiciousblock.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.PotDecorations;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.ItemTags;
import org.jetbrains.annotations.NotNull;

/** 纹饰陶轮台的 6 个输入/输出槽位与动态配方逻辑。 */
public class PotteryWheelBlockEntity extends BlockEntity implements Container, MenuProvider {
    public static final int TOP = 0;
    public static final int LEFT = 1;
    public static final int RIGHT = 2;
    public static final int BOTTOM = 3;
    public static final int CLAY = 4;
    public static final int WATER = 5;
    public static final int OUTPUT = 6;
    public static final int SIZE = 7;

    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private boolean refreshing;

    public PotteryWheelBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.POTTERY_WHEEL.get(), pos, state);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.unsuspiciousblock.pottery_wheel");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new com.meteorite.unsuspiciousblock.pottery.PotteryWheelMenu(id, inventory, this);
    }

    @Override
    public int getContainerSize() { return SIZE; }

    @Override
    public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }

    @Override
    public ItemStack getItem(int slot) { return items.get(slot); }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(items, slot, amount);
        if (!result.isEmpty()) setChanged();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) { return ContainerHelper.takeItem(items, slot); }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        if (!refreshing && slot != OUTPUT) {
            refreshRecipe();
        } else {
            setChanged();
        }
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() { items.clear(); refreshRecipe(); }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot == OUTPUT) return false;
        if (slot == CLAY) return isClayMaterial(stack);
        if (slot == WATER) return isWaterBottle(stack);
        if (slot >= TOP && slot <= BOTTOM) {
            return stack.is(ItemTags.DECORATED_POT_INGREDIENTS);
        }
        return false;
    }

    // 刷新输出，并同步“湿黏土”方块状态
    public void refreshRecipe() {
        ItemStack result = getPotResult();
        if (result.isEmpty()) result = getSherdResult();
        refreshing = true;
        items.set(OUTPUT, result);
        refreshing = false;
        boolean wet = isClayMaterial(items.get(CLAY)) && isWaterBottle(items.get(WATER));
        if (level != null && level.getBlockState(worldPosition).hasProperty(PotteryWheelBlock.WET_CLAY)
                && level.getBlockState(worldPosition).getValue(PotteryWheelBlock.WET_CLAY) != wet) {
            level.setBlock(worldPosition, level.getBlockState(worldPosition).setValue(PotteryWheelBlock.WET_CLAY, wet), 3);
        }
        setChanged();
    }

    // 四个纹饰槽满足原版陶罐配方时优先输出陶罐
    public ItemStack getPotResult() {
        if (items.get(TOP).isEmpty() || items.get(LEFT).isEmpty() || items.get(RIGHT).isEmpty() || items.get(BOTTOM).isEmpty()) {
            return ItemStack.EMPTY;
        }
        for (int i = TOP; i <= BOTTOM; i++) {
            ItemStack stack = items.get(i);
            if (!stack.is(ItemTags.DECORATED_POT_INGREDIENTS)) return ItemStack.EMPTY;
        }
        return DecoratedPotBlockEntity.createDecoratedPotItem(new PotDecorations(
                items.get(TOP).getItem(), items.get(LEFT).getItem(), items.get(RIGHT).getItem(), items.get(BOTTOM).getItem()));
    }

    // 黏土块、水瓶和一个陶片共同生成未烧制纹饰陶片
    public ItemStack getSherdResult() {
        if (!isClayMaterial(items.get(CLAY))
                || !isWaterBottle(items.get(WATER))) {
            return ItemStack.EMPTY;
        }
        Item source = null;
        for (int i = TOP; i <= BOTTOM; i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty() && stack.is(ItemTags.DECORATED_POT_SHERDS)) {
                source = stack.getItem();
                break;
            }
        }
        if (source == null) return ItemStack.EMPTY;
        ItemStack result = new ItemStack(ModItems.UNFIRED_DECORATED_SHERD);
        result.set(DataComponents.POT_DECORATIONS, new PotDecorations(source, source, source, source));
        return result;
    }

    // 玩家取出输出时消耗对应输入；水瓶转为空玻璃瓶
    public void consumeResult() {
        if (getPotResult().isEmpty() && getSherdResult().isEmpty()) return;
        if (!getPotResult().isEmpty()) {
            for (int i = TOP; i <= BOTTOM; i++) removeItem(i, 1);
        } else {
            removeItem(CLAY, 1);
            for (int i = TOP; i <= BOTTOM; i++) {
                if (!items.get(i).isEmpty() && items.get(i).is(ItemTags.DECORATED_POT_SHERDS)) {
                    removeItem(i, 1); break;
                }
            }
            removeItem(WATER, 1);
            if (items.get(WATER).isEmpty()) setItem(WATER, new ItemStack(Items.GLASS_BOTTLE));
        }
        refreshRecipe();
    }

    // 返回复制预览用的原版陶片
    public ItemStack getSherdPreview() {
        ItemStack result = getSherdResult();
        if (result.isEmpty()) return ItemStack.EMPTY;
        Item item = result.get(DataComponents.POT_DECORATIONS).ordered().getFirst();
        return new ItemStack(item);
    }

    // 精确识别原版水瓶，排除其他药水和压印后返还的玻璃瓶
    private static boolean isWaterBottle(ItemStack stack) {
        PotionContents potion = stack.get(DataComponents.POTION_CONTENTS);
        return stack.is(Items.POTION) && potion != null && potion.is(Potions.WATER);
    }

    // 黏土槽允许黏土块与黏土球，二者均可作为陶坯材料
    private static boolean isClayMaterial(ItemStack stack) {
        return stack.is(Blocks.CLAY.asItem()) || stack.is(Items.CLAY_BALL);
    }

    // 方块被破坏时仅掉落真实输入，避免把动态输出重复掉落
    public void dropInputs(Level level) {
        for (int i = TOP; i <= WATER; i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), stack);
                items.set(i, ItemStack.EMPTY);
            }
        }
        items.set(OUTPUT, ItemStack.EMPTY);
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.loadAdditional(tag, registries);
        ContainerHelper.loadAllItems(tag, items, registries);
    }
}
