package com.meteorite.unsuspiciousblock.blockentity;

import com.meteorite.unsuspiciousblock.block.PotteryWheelBlock;
import com.meteorite.unsuspiciousblock.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.PotDecorations;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.ItemTags;
import org.jetbrains.annotations.NotNull;

/** 纹饰陶轮台的 6 个输入/输出槽位、动态配方与漏斗交互逻辑。 */
public class PotteryWheelBlockEntity extends BlockEntity implements WorldlyContainer, MenuProvider {
    public static final int TOP = 0;
    public static final int LEFT = 1;
    public static final int RIGHT = 2;
    public static final int BOTTOM = 3;
    public static final int CLAY = 4;
    public static final int WATER = 5;
    public static final int OUTPUT = 6;
    public static final int SIZE = 7;
    public static final int PROCESS_TIME = 8 * 20;

    private static final String PROCESS_PROGRESS_TAG = "ProcessProgress";
    private static final int[] TOP_ACCESS_SLOTS = {CLAY, WATER};
    private static final int[] SIDE_ACCESS_SLOTS = {TOP, LEFT, RIGHT, BOTTOM, CLAY, WATER};
    private static final int[] BOTTOM_ACCESS_SLOTS = {OUTPUT, WATER};

    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> processProgress;
                case 1 -> PROCESS_TIME;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            if (index == 0) processProgress = value;
        }

        @Override
        public int getCount() {
            return 2;
        }
    };
    private int processProgress;

    public PotteryWheelBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.POTTERY_WHEEL.get(), pos, state);
    }

    @Override
    public @NotNull Component getDisplayName() {
        return Component.translatable("block.unsuspiciousblock.pottery_wheel");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, @NotNull Inventory inventory, @NotNull Player player) {
        return new com.meteorite.unsuspiciousblock.pottery.PotteryWheelMenu(id, inventory, this);
    }

    @Override
    public int getContainerSize() { return SIZE; }

    @Override
    public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }

    @Override
    public @NotNull ItemStack getItem(int slot) { return items.get(slot); }

    @Override
    public @NotNull ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(items, slot, amount);
        if (!result.isEmpty()) {
            if (slot == OUTPUT) {
                setChanged();
            } else {
                inputsChanged();
            }
        }
        return result;
    }

    @Override
    public @NotNull ItemStack removeItemNoUpdate(int slot) { return ContainerHelper.takeItem(items, slot); }

    @Override
    public void setItem(int slot, @NotNull ItemStack stack) {
        items.set(slot, stack);
        if (slot != OUTPUT) {
            inputsChanged();
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
    public boolean stillValid(@NotNull Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        items.clear();
        inputsChanged();
    }

    @Override
    public boolean canPlaceItem(int slot, @NotNull ItemStack stack) {
        if (slot == OUTPUT) return false;
        if (slot == CLAY) return isClayMaterial(stack);
        if (slot == WATER) return isWaterBottle(stack);
        if (slot >= TOP && slot <= BOTTOM) {
            return stack.is(ItemTags.DECORATED_POT_INGREDIENTS);
        }
        return false;
    }

    @Override
    public int @NotNull [] getSlotsForFace(@NotNull Direction direction) {
        if (direction == Direction.UP) return TOP_ACCESS_SLOTS;
        if (direction == Direction.DOWN) return BOTTOM_ACCESS_SLOTS;
        return SIDE_ACCESS_SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, @NotNull ItemStack stack, Direction direction) {
        if (direction == Direction.DOWN) return false;
        if (direction == Direction.UP && slot != CLAY && slot != WATER) return false;
        return canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, @NotNull ItemStack stack, @NotNull Direction direction) {
        return direction == Direction.DOWN
                && (slot == OUTPUT || slot == WATER && stack.is(Items.GLASS_BOTTLE));
    }

    // 输入变化后重新计时，并同步“湿黏土”方块状态
    private void inputsChanged() {
        processProgress = 0;
        updateWetClayState();
        setChanged();
    }

    // 根据当前黏土和水瓶状态更新方块模型
    private void updateWetClayState() {
        boolean wet = isClayMaterial(items.get(CLAY)) && isWaterBottle(items.get(WATER));
        if (level != null && level.getBlockState(worldPosition).hasProperty(PotteryWheelBlock.WET_CLAY)
                && level.getBlockState(worldPosition).getValue(PotteryWheelBlock.WET_CLAY) != wet) {
            level.setBlock(worldPosition, level.getBlockState(worldPosition).setValue(PotteryWheelBlock.WET_CLAY, wet), 3);
        }
    }

    // 服务端每 tick 推进一次制作；输入失效或结果槽被占用时停止并清空进度
    public static void serverTick(PotteryWheelBlockEntity wheel) {
        ItemStack result = getProcessingResult(wheel);
        if (!wheel.items.get(OUTPUT).isEmpty() || result.isEmpty()) {
            wheel.resetProgress();
            return;
        }

        wheel.processProgress++;
        if (wheel.processProgress >= PROCESS_TIME) {
            wheel.completeProcessing(result);
        }
    }

    // 完成时消耗材料，优先把空瓶和结果直接写入正下方漏斗
    private void completeProcessing(ItemStack result) {
        if (result.is(ModItems.UNFIRED_DECORATED_POT)) {
            consumeClayUnits();
        } else {
            items.get(CLAY).shrink(1);
        }
        items.get(WATER).shrink(1);
        ItemStack bottle = new ItemStack(Items.GLASS_BOTTLE);
        items.set(OUTPUT, insertIntoHopperBelow(result));
        items.set(WATER, insertIntoHopperBelow(bottle));
        processProgress = 0;
        updateWetClayState();
        setChanged();
    }

    // 返回漏斗未能接收的剩余物品，调用方将其保存在陶轮台槽位中
    private ItemStack insertIntoHopperBelow(ItemStack stack) {
        if (level == null || !(level.getBlockEntity(worldPosition.below()) instanceof HopperBlockEntity hopper)) {
            return stack;
        }
        return HopperBlockEntity.addItem(this, hopper, stack, Direction.UP);
    }

    // 仅在状态真正变化时标脏，避免空闲方块每 tick 触发区块保存
    private void resetProgress() {
        if (processProgress == 0) return;
        processProgress = 0;
        setChanged();
    }

    // 根据指定容器的输入计算未来产物，供服务端处理和客户端预览共同使用
    public static ItemStack getProcessingResult(Container container) {
        ItemStack result = getPotResult(container);
        return result.isEmpty() ? getSherdResult(container) : result;
    }

    // 预览只检查陶片槽，黏土和水瓶仅决定能否实际开始加工
    public static ItemStack getPreviewResult(Container container) {
        ItemStack result = createPotResult(container);
        return result.isEmpty() ? createSherdResult(container, 1) : result;
    }

    public ContainerData getDataAccess() {
        return dataAccess;
    }

    // 从任意同步容器计算陶罐结果
    private static ItemStack getPotResult(Container container) {
        if (!hasFourClayUnits(container) || !isWaterBottle(container.getItem(WATER))) {
            return ItemStack.EMPTY;
        }
        return createPotResult(container);
    }

    // 仅根据四个陶片槽构造陶罐预览或实际结果
    private static ItemStack createPotResult(Container container) {
        for (int i = TOP; i <= BOTTOM; i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.is(ItemTags.DECORATED_POT_INGREDIENTS)) return ItemStack.EMPTY;
        }
        ItemStack result = new ItemStack(ModItems.UNFIRED_DECORATED_POT);
        result.set(DataComponents.POT_DECORATIONS, new PotDecorations(
                container.getItem(TOP).getItem(), container.getItem(LEFT).getItem(),
                container.getItem(RIGHT).getItem(), container.getItem(BOTTOM).getItem()));
        return result;
    }

    // 从任意同步容器计算陶片复制结果
    private static ItemStack getSherdResult(Container container) {
        if (!isClayMaterial(container.getItem(CLAY))
                || !isWaterBottle(container.getItem(WATER))) {
            return ItemStack.EMPTY;
        }
        int count = container.getItem(CLAY).is(Blocks.CLAY.asItem()) ? 4 : 1;
        return createSherdResult(container, count);
    }

    // 查找第一个可复制陶片，并按指定数量构造预览或实际结果
    private static ItemStack createSherdResult(Container container, int count) {
        Item source = null;
        for (int i = TOP; i <= BOTTOM; i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && stack.is(ItemTags.DECORATED_POT_SHERDS)) {
                source = stack.getItem();
                break;
            }
        }
        if (source == null) return ItemStack.EMPTY;
        ItemStack result = new ItemStack(ModItems.UNFIRED_DECORATED_SHERD, count);
        result.set(DataComponents.POT_DECORATIONS, new PotDecorations(source, source, source, source));
        return result;
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

    // 黏土块等价于四个黏土球
    private static boolean hasFourClayUnits(Container container) {
        ItemStack clay = container.getItem(CLAY);
        return clay.is(Blocks.CLAY.asItem()) || clay.is(Items.CLAY_BALL) && clay.getCount() >= 4;
    }

    // 当前陶罐配方固定消耗四份，优先保持黏土块的单次消耗语义
    private void consumeClayUnits() {
        ItemStack clay = items.get(CLAY);
        clay.shrink(clay.is(Blocks.CLAY.asItem()) ? 1 : 4);
    }

    // 方块被破坏时掉落全部真实槽位内容
    public void dropInputs(Level level) {
        for (int i = TOP; i <= OUTPUT; i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), stack);
                items.set(i, ItemStack.EMPTY);
            }
        }
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        tag.putInt(PROCESS_PROGRESS_TAG, processProgress);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.loadAdditional(tag, registries);
        ContainerHelper.loadAllItems(tag, items, registries);
        processProgress = Math.max(0, Math.min(tag.getInt(PROCESS_PROGRESS_TAG), PROCESS_TIME - 1));
    }
}
