package com.meteorite.unsuspiciousblock.core.blockentity;

import com.meteorite.unsuspiciousblock.core.pottery.PotteryWheelMenu;
import com.meteorite.unsuspiciousblock.core.achievement.AchievementManager;
import com.meteorite.unsuspiciousblock.core.achievement.ModAchievements;
import com.meteorite.unsuspiciousblock.core.block.PotteryWheelBlock;
import com.meteorite.unsuspiciousblock.core.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.PotDecorations;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.ItemTags;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** 纹饰陶轮台的 6 个输入/输出槽位、动态配方与漏斗交互逻辑。 */
public class PotteryWheelBlockEntity extends BlockEntity implements WorldlyContainer, MenuProvider {
    /** 陶轮台的工作许可模式。 */
    public enum ControlMode {
        ENABLED,
        DISABLED,
        REDSTONE_ENABLED;

        public static ControlMode byId(int id) {
            ControlMode[] values = values();
            return values[Math.floorMod(id, values.length)];
        }
    }
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
    private static final String CONTROL_MODE_TAG = "ControlMode";
    private static final int[] TOP_ACCESS_SLOTS = {CLAY, WATER};
    private static final int[] SIDE_ACCESS_SLOTS = {TOP, LEFT, RIGHT, BOTTOM, CLAY, WATER};
    private static final int[] BOTTOM_ACCESS_SLOTS = {OUTPUT, WATER};

    private static final int CLAY_UNITS_PER_BLOCK = 4;

    private final NonNullList<ItemStack> inputsAtPlan = NonNullList.withSize(OUTPUT, ItemStack.EMPTY);
    private @Nullable ProcessingPlan processingPlan;
    private @Nullable ReloadableServerRegistries.Holder planRegistries;
    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> processProgress;
                case 1 -> PROCESS_TIME;
                case 2 -> controlMode.ordinal();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            if (index == 0) processProgress = value;
            if (index == 2) controlMode = ControlMode.byId(value);
        }

        @Override
        public int getCount() {
            return 3;
        }
    };
    private int processProgress;
    private ControlMode controlMode = ControlMode.ENABLED;
    // 最近一次打开陶轮台菜单的玩家（仅内存，不持久化），用于「首次开始工作」进度授予
    @Nullable
    private UUID lastOperator;

    public PotteryWheelBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.POTTERY_WHEEL.get(), pos, state);
    }

    @Override
    public @NotNull Component getDisplayName() {
        return Component.translatable("block.unsuspiciousblock.pottery_wheel");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, @NotNull Inventory inventory, @NotNull Player player) {
        return new PotteryWheelMenu(id, inventory, this);
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
        return isValidInput(slot, stack);
    }

    // 客户端与服务端菜单共用输入规则，避免 shift 移动的预测结果不一致。
    public static boolean isValidInput(int slot, ItemStack stack) {
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

    // 输入变化后重新计时，并同步陶坯与水槽的外观状态。
    private void inputsChanged() {
        processingPlan = null;
        processProgress = 0;
        updateInputVisualState();
        setChanged();
    }

    private void updateInputVisualState() {
        if (level == null || level.isClientSide()) return;
        BlockState state = level.getBlockState(worldPosition);
        if (!(state.getBlock() instanceof PotteryWheelBlock)) return;
        BlockState updated = state
                .setValue(PotteryWheelBlock.WET_CLAY, isClayMaterial(items.get(CLAY)))
                .setValue(PotteryWheelBlock.HAS_WATER, isWaterBottle(items.get(WATER)));
        if (updated != state) {
            level.setBlock(worldPosition, updated, Block.UPDATE_ALL);
        }
    }

    // 同步实际加工状态，供客户端转盘动画和粒子效果使用。
    private void updateWorkingState(boolean working) {
        if (level != null && level.getBlockState(worldPosition).hasProperty(PotteryWheelBlock.WORKING)
                && level.getBlockState(worldPosition).getValue(PotteryWheelBlock.WORKING) != working) {
            level.setBlock(worldPosition, level.getBlockState(worldPosition)
                    .setValue(PotteryWheelBlock.WORKING, working), 3);
        }
    }

    // 服务端每 tick 推进一次制作；输入失效或结果槽无法容纳产物时停止并清空进度
    public static void serverTick(PotteryWheelBlockEntity wheel) {
        wheel.updateInputVisualState();
        if (!wheel.canWorkNow()) {
            wheel.updateWorkingState(false);
            return;
        }
        ProcessingPlan plan = wheel.currentPlan();
        if (plan.result().isEmpty() || !wheel.canAcceptOutput(plan.result())) {
            wheel.updateWorkingState(false);
            wheel.resetProgress();
            return;
        }

        wheel.updateWorkingState(true);
        // 进度从 0 开始递增的第一个 tick 视为「开始工作」，向最近操作者授予进度
        if (wheel.processProgress == 0) {
            wheel.grantRoundAndRound();
        }
        wheel.processProgress++;
        if (wheel.processProgress >= PROCESS_TIME) {
            wheel.completeProcessing(plan);
        }
    }

    // 「吱悠~吱悠~」进度：陶轮台首次开始工作时授予给最近一次操作者（重复触发由 grantIfNotAlready 幂等处理）
    private void grantRoundAndRound() {
        if (lastOperator == null || level == null || level.isClientSide() || level.getServer() == null) {
            return;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(lastOperator);
        if (player != null) {
            AchievementManager.grantIfNotAlready(player, ModAchievements.ROUND_AND_ROUND);
        }
    }

    // 由菜单在服务端构造时登记操作者，供「首次开始工作」进度授予
    public void noteOperator(Player player) {
        this.lastOperator = player.getUUID();
    }

    // 客户端稳定生成随转盘向外飞散的黏土碎屑。
    public static void clientTick(Level level, BlockPos pos, BlockState state) {
        if (!state.getValue(PotteryWheelBlock.WORKING)) return;
        RandomSource random = level.getRandom();
        if (random.nextFloat() >= 0.35F) return;

        double angle = random.nextDouble() * Math.PI * 2.0D;
        double radius = 0.20D + random.nextDouble() * 0.28D;
        double x = pos.getX() + 0.5D + Math.cos(angle) * radius;
        double y = pos.getY() + 0.90D + random.nextDouble() * 0.10D;
        double z = pos.getZ() + 0.5D + Math.sin(angle) * radius;
        double outward = 0.008D + random.nextDouble() * 0.012D;
        level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.CLAY.defaultBlockState()),
                x, y, z, Math.cos(angle) * outward, 0.015D + random.nextDouble() * 0.02D,
                Math.sin(angle) * outward);
    }

    // 完成时消耗材料，优先把空瓶和结果直接写入正下方漏斗
    private void completeProcessing(ProcessingPlan plan) {
        items.get(CLAY).shrink(plan.clayCost());
        items.get(WATER).shrink(1);
        storeOutput(insertIntoHopperBelow(plan.result().copy()));
        ItemStack bottle = insertIntoHopperBelow(new ItemStack(Items.GLASS_BOTTLE));
        if (items.get(WATER).isEmpty()) {
            items.set(WATER, bottle);
        } else if (!bottle.isEmpty()) {
            Block.popResource(level, worldPosition, bottle);
        }
        updateWorkingState(false);
        inputsChanged();
    }

    // 返回漏斗未能接收的剩余物品，调用方将其保存在陶轮台槽位中
    private ItemStack insertIntoHopperBelow(ItemStack stack) {
        if (level == null || !(level.getBlockEntity(worldPosition.below()) instanceof HopperBlockEntity hopper)) {
            return stack;
        }
        return HopperBlockEntity.addItem(this, hopper, stack, Direction.UP);
    }

    // 空槽或相同物品且剩余空间足够时，可以继续加工
    private boolean canAcceptOutput(ItemStack result) {
        ItemStack output = items.get(OUTPUT);
        return output.isEmpty() || ItemStack.isSameItemSameComponents(output, result)
                && output.getCount() + result.getCount() <= output.getMaxStackSize();
    }

    // 漏斗未接收的产物安全写入或合并到结果槽
    private void storeOutput(ItemStack result) {
        if (result.isEmpty()) return;
        ItemStack output = items.get(OUTPUT);
        if (output.isEmpty()) {
            items.set(OUTPUT, result);
        } else {
            output.grow(result.getCount());
        }
    }

    // 仅在状态真正变化时标脏，避免空闲方块每 tick 触发区块保存
    private void resetProgress() {
        if (processProgress == 0) return;
        processProgress = 0;
        setChanged();
    }

    private ProcessingPlan currentPlan() {
        ReloadableServerRegistries.Holder registries = level.getServer().reloadableRegistries();
        if (processingPlan != null && planRegistries == registries && inputsMatchPlan()) {
            return processingPlan;
        }
        // 漏斗和菜单可以直接增减栈数量，比较输入快照才能识别这种修改。
        if (processingPlan != null) {
            resetProgress();
        }
        for (int slot = TOP; slot < OUTPUT; slot++) {
            inputsAtPlan.set(slot, items.get(slot).copy());
        }
        planRegistries = registries;
        processingPlan = plan(this);
        return processingPlan;
    }

    private boolean inputsMatchPlan() {
        for (int slot = TOP; slot < OUTPUT; slot++) {
            if (!ItemStack.matches(items.get(slot), inputsAtPlan.get(slot))) {
                return false;
            }
        }
        return true;
    }

    /** 返回当前实际产物，材料未齐时展示纹饰槽匹配的预览。 */
    public static ItemStack getPreviewResult(Container container) {
        return plan(container).preview();
    }

    private static ProcessingPlan plan(Container container) {
        ItemStack clay = container.getItem(CLAY);
        boolean isClayBlock = clay.is(Blocks.CLAY.asItem());
        boolean hasWater = isWaterBottle(container.getItem(WATER));
        int potCost = isClayBlock ? 1 : CLAY_UNITS_PER_BLOCK;
        ItemStack pot = createPotResult(container);
        if (!pot.isEmpty() && hasWater && isClayMaterial(clay) && clay.getCount() >= potCost) {
            return new ProcessingPlan(pot, pot, potCost);
        }
        boolean canCopy = hasWater && isClayMaterial(clay);
        ItemStack sherd = createSherdResult(container, canCopy && isClayBlock ? CLAY_UNITS_PER_BLOCK : 1);
        if (canCopy && !sherd.isEmpty()) {
            return new ProcessingPlan(sherd, sherd, 1);
        }
        return new ProcessingPlan(pot.isEmpty() ? sherd : pot, ItemStack.EMPTY, 0);
    }

    private record ProcessingPlan(ItemStack preview, ItemStack result, int clayCost) {}

    public ContainerData getDataAccess() {
        return dataAccess;
    }

    public ControlMode getControlMode() {
        return controlMode;
    }

    // 切换工作许可并立即停止不再允许运行的动画。
    public void cycleControlMode() {
        controlMode = ControlMode.byId(controlMode.ordinal() + 1);
        if (!canWorkNow()) updateWorkingState(false);
        setChanged();
    }

    private boolean canWorkNow() {
        return switch (controlMode) {
            case ENABLED -> true;
            case DISABLED -> false;
            case REDSTONE_ENABLED -> level != null && level.hasNeighborSignal(worldPosition);
        };
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
        tag.putInt(CONTROL_MODE_TAG, controlMode.ordinal());
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.loadAdditional(tag, registries);
        ContainerHelper.loadAllItems(tag, items, registries);
        processingPlan = null;
        processProgress = Math.max(0, Math.min(tag.getInt(PROCESS_PROGRESS_TAG), PROCESS_TIME - 1));
        controlMode = ControlMode.byId(tag.contains(CONTROL_MODE_TAG) ? tag.getInt(CONTROL_MODE_TAG) : 0);
    }
}
