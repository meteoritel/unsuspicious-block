package com.meteorite.unsuspiciousblock.pottery;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.blockentity.PotteryWheelBlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/** 纹饰陶轮台菜单，复用一组十字输入槽支持陶罐与陶片压印。 */
public class PotteryWheelMenu extends AbstractContainerMenu {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "pottery_wheel");
    public static MenuType<PotteryWheelMenu> TYPE;

    private final net.minecraft.world.Container container;
    private final PotteryWheelBlockEntity wheel;
    private final ContainerData data;

    // 客户端 MenuType 构造
    public PotteryWheelMenu(int id, Inventory inventory) {
        this(id, inventory, new SimpleContainer(PotteryWheelBlockEntity.SIZE), null, new SimpleContainerData(3));
    }

    // 服务端方块实体构造
    public PotteryWheelMenu(int id, Inventory inventory, PotteryWheelBlockEntity wheel) {
        this(id, inventory, wheel, wheel, wheel.getDataAccess());
    }

    private PotteryWheelMenu(int id, Inventory inventory, net.minecraft.world.Container container,
                             PotteryWheelBlockEntity wheel, ContainerData data) {
        super(TYPE, id);
        this.container = container;
        this.wheel = wheel;
        this.data = data;
        addWheelSlots();
        addPlayerSlots(inventory);
        addDataSlots(data);
    }

    private void addWheelSlots() {
        addSlot(new InputSlot(container, PotteryWheelBlockEntity.TOP, 44, 18));
        addSlot(new InputSlot(container, PotteryWheelBlockEntity.LEFT, 26, 36));
        addSlot(new InputSlot(container, PotteryWheelBlockEntity.RIGHT, 62, 36));
        addSlot(new InputSlot(container, PotteryWheelBlockEntity.BOTTOM, 44, 54));
        addSlot(new InputSlot(container, PotteryWheelBlockEntity.CLAY, 44, 36));
        addSlot(new InputSlot(container, PotteryWheelBlockEntity.WATER, 89, 36));
        addSlot(new OutputSlot(container, PotteryWheelBlockEntity.OUTPUT, 145, 61));
    }

    private void addPlayerSlots(Inventory inventory) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 9 + col * 18, 85 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 9 + col * 18, 143));
        }
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return wheel == null || wheel.stillValid(player);
    }

    public ItemStack getPreviewResult() {
        return PotteryWheelBlockEntity.getPreviewResult(container);
    }

    public int getScaledProgress(int width) {
        int progress = data.get(0);
        int total = data.get(1);
        return progress <= 0 || total <= 0 ? 0 : Math.min(width, (progress * width + total - 1) / total);
    }

    public PotteryWheelBlockEntity.ControlMode getControlMode() {
        return PotteryWheelBlockEntity.ControlMode.byId(data.get(2));
    }

    // 客户端先更新按钮图标，服务端状态随后通过 ContainerData 同步。
    public void setClientControlMode(PotteryWheelBlockEntity.ControlMode mode) {
        data.set(2, mode.ordinal());
    }

    @Override
    public boolean clickMenuButton(@NotNull Player player, int id) {
        if (id != 0 || wheel == null) return false;
        if (!player.level().isClientSide()) wheel.cycleControlMode();
        return true;
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack original = slot.getItem();
        ItemStack copy = original.copy();
        if (index == PotteryWheelBlockEntity.OUTPUT) {
            if (!moveItemStackTo(original, 7, slots.size(), true)) return ItemStack.EMPTY;
            slot.onTake(player, copy);
        } else if (index < 7) {
            if (!moveItemStackTo(original, 7, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            boolean moved;
            if (PotteryWheelBlockEntity.isValidInput(PotteryWheelBlockEntity.CLAY, original)) {
                moved = moveItemStackTo(original, PotteryWheelBlockEntity.CLAY,
                        PotteryWheelBlockEntity.CLAY + 1, false);
            } else if (PotteryWheelBlockEntity.isValidInput(PotteryWheelBlockEntity.WATER, original)) {
                moved = moveItemStackTo(original, PotteryWheelBlockEntity.WATER,
                        PotteryWheelBlockEntity.WATER + 1, false);
            } else {
                moved = moveItemStackTo(original, PotteryWheelBlockEntity.TOP,
                        PotteryWheelBlockEntity.BOTTOM + 1, false);
            }
            if (!moved) return ItemStack.EMPTY;
        }
        if (original.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        return copy;
    }

    private static class InputSlot extends Slot {
        InputSlot(net.minecraft.world.Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            return PotteryWheelBlockEntity.isValidInput(getContainerSlot(), stack);
        }
    }

    private static class OutputSlot extends Slot {
        OutputSlot(net.minecraft.world.Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) { return false; }

        @Override
        public boolean mayPickup(@NotNull Player player) { return hasItem(); }
    }
}
