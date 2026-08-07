package com.meteorite.unsuspiciousblock.pottery;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.blockentity.PotteryWheelBlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/** 纹饰陶轮台菜单，复用一组十字输入槽支持陶罐与陶片压印。 */
public class PotteryWheelMenu extends AbstractContainerMenu {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "pottery_wheel");
    public static MenuType<PotteryWheelMenu> TYPE;

    private final net.minecraft.world.Container container;
    private final PotteryWheelBlockEntity wheel;

    // 客户端 MenuType 构造
    public PotteryWheelMenu(int id, Inventory inventory) {
        this(id, inventory, new SimpleContainer(PotteryWheelBlockEntity.SIZE), null);
    }

    // 服务端方块实体构造
    public PotteryWheelMenu(int id, Inventory inventory, PotteryWheelBlockEntity wheel) {
        this(id, inventory, wheel, wheel);
    }

    private PotteryWheelMenu(int id, Inventory inventory, net.minecraft.world.Container container,
                             PotteryWheelBlockEntity wheel) {
        super(TYPE, id);
        this.container = container;
        this.wheel = wheel;
        addWheelSlots();
        addPlayerSlots(inventory);
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

    public ItemStack getSherdPreview() {
        return wheel == null ? ItemStack.EMPTY : wheel.getSherdPreview();
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
        } else if (!moveItemStackTo(original, 0, 6, false)) {
            return ItemStack.EMPTY;
        }
        if (original.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        return copy;
    }

    private static class InputSlot extends Slot {
        private final net.minecraft.world.Container inputContainer;

        InputSlot(net.minecraft.world.Container container, int index, int x, int y) {
            super(container, index, x, y);
            this.inputContainer = container;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return inputContainer.canPlaceItem(getContainerSlot(), stack);
        }
    }

    private static class OutputSlot extends Slot {
        private final net.minecraft.world.Container outputContainer;

        OutputSlot(net.minecraft.world.Container container, int index, int x, int y) {
            super(container, index, x, y);
            this.outputContainer = container;
        }

        @Override
        public boolean mayPlace(ItemStack stack) { return false; }

        @Override
        public boolean mayPickup(Player player) { return hasItem(); }

        @Override
        public void onTake(Player player, ItemStack stack) {
            super.onTake(player, stack);
            if (outputContainer instanceof PotteryWheelBlockEntity wheel) wheel.consumeResult();
        }
    }
}
