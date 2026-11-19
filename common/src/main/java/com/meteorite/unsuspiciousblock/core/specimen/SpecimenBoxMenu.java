package com.meteorite.unsuspiciousblock.core.specimen;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.bootstrap.ModContent;
import com.meteorite.unsuspiciousblock.core.item.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 标本箱菜单。服务端将箱内交互提交到打开时绑定的载体。 */
public class SpecimenBoxMenu extends AbstractContainerMenu {
    public static final int CONTAINER_SIZE = 5;

    /** 菜单类型 ID，各平台注册时使用 */
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "specimen_box");

    // 漏斗布局常量
    private static final int CONTAINER_X = 44;
    private static final int CONTAINER_Y = 20;
    private static final int PLAYER_INVENTORY_X = 8;
    private static final int PLAYER_INVENTORY_Y = 51;
    private static final int HOTBAR_Y = 109;

    private static final int NO_CARRIER_SLOT = -1;

    private final Inventory inventory;
    private final SimpleContainer container = new SimpleContainer(CONTAINER_SIZE);
    private final DataSlot carrierSlot = DataSlot.standalone();
    private final @Nullable ItemStack carrier;
    private @Nullable ItemContainerContents displayedContents;

    /** 创建客户端菜单，载体槽由服务端随菜单数据同步。 */
    public SpecimenBoxMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, NO_CARRIER_SLOT);
    }

    /** 创建服务端菜单并绑定实际使用的手和物品栈。 */
    public SpecimenBoxMenu(int containerId, Inventory inventory, InteractionHand hand) {
        this(containerId, inventory, hand == InteractionHand.OFF_HAND ? Inventory.SLOT_OFFHAND : inventory.selected);
    }

    private SpecimenBoxMenu(int containerId, Inventory inventory, int carrierSlotIndex) {
        super(ModContent.specimenBoxMenu(), containerId);
        this.inventory = inventory;
        this.carrierSlot.set(carrierSlotIndex);
        this.carrier = carrierSlotIndex == NO_CARRIER_SLOT ? null : inventory.getItem(carrierSlotIndex);
        addDataSlot(this.carrierSlot);
        refreshContents();
        addContainerSlots();
        addPlayerSlots(inventory);
    }

    // 添加 5 格容器槽位
    private void addContainerSlots() {
        for (int i = 0; i < CONTAINER_SIZE; i++) {
            this.addSlot(new Slot(this.container, i,
                    CONTAINER_X + i * 18, CONTAINER_Y) {
                @Override
                public boolean mayPlace(@NotNull ItemStack stack) {
                    // 禁止标本箱嵌套，避免递归容器造成无法取出的物品。
                    return !stack.is(ModItems.SPECIMEN_BOX);
                }
            });
        }
    }

    // 添加玩家物品栏槽位（3×9 背包 + 9 快捷栏）
    private void addPlayerSlots(Inventory playerInventory) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(playerSlot(playerInventory, col + row * 9 + 9,
                        PLAYER_INVENTORY_X + col * 18, PLAYER_INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(playerSlot(playerInventory, col, PLAYER_INVENTORY_X + col * 18, HOTBAR_Y));
        }
    }

    private Slot playerSlot(Inventory inventory, int index, int x, int y) {
        return new Slot(inventory, index, x, y) {
            @Override
            public boolean mayPickup(Player player) {
                return index != carrierSlot.get();
            }

            @Override
            public boolean mayPlace(ItemStack stack) {
                return index != carrierSlot.get();
            }
        };
    }

    private void refreshContents() {
        if (this.carrier == null) {
            return;
        }
        ItemContainerContents contents = SpecimenBoxContents.contents(this.carrier);
        if (contents != this.displayedContents) {
            contents.copyInto(this.container.getItems());
            this.displayedContents = contents;
        }
    }

    @Override
    public void broadcastChanges() {
        refreshContents();
        super.broadcastChanges();
    }

    @Override
    public void broadcastFullState() {
        refreshContents();
        super.broadcastFullState();
    }

    @Override
    public void clicked(int slotId, int button, @NotNull ClickType clickType, @NotNull Player player) {
        if (!stillValid(player) || clickType == ClickType.SWAP && button == this.carrierSlot.get()) {
            return;
        }
        if (slotId >= CONTAINER_SIZE && slotId < this.slots.size()
                && getSlot(slotId).getContainerSlot() == this.carrierSlot.get()) {
            return;
        }
        refreshContents();
        super.clicked(slotId, button, clickType, player);
        if (this.carrier != null) {
            this.displayedContents = SpecimenBoxContents.commit(this.carrier, this.container.getItems());
        }
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack original = slot.getItem();
        ItemStack copy = original.copy();
        if (index < CONTAINER_SIZE) {
            // 从容器移到玩家背包（反向）
            if (!this.moveItemStackTo(original, CONTAINER_SIZE, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 从玩家背包移到容器（正向）
            if (!this.moveItemStackTo(original, 0, CONTAINER_SIZE, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (original.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return this.carrier == null || this.carrier.is(ModItems.SPECIMEN_BOX)
                && this.inventory.getItem(this.carrierSlot.get()) == this.carrier;
    }
}
