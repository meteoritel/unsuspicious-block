package com.meteorite.unsuspiciousblock.specimen;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.item.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import org.jetbrains.annotations.NotNull;

/**
 * 标本箱菜单--5 格便携容器 + 玩家物品栏，布局参照漏斗菜单。
 * 物品存放在载体 ItemStack 的 DataComponents.CONTAINER 中，容器变更时实时写回。
 * 载体物品（标本箱自身）所在槽位被锁定，防止刷物品。
 */
public class SpecimenBoxMenu extends AbstractContainerMenu {
    public static final int CONTAINER_SIZE = 5;

    /** 菜单类型 ID，各平台注册时使用 */
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "specimen_box");
    /** 菜单类型，由平台模块注册后回写 */
    public static MenuType<SpecimenBoxMenu> TYPE;

    // 漏斗布局常量
    private static final int CONTAINER_X = 44;
    private static final int CONTAINER_Y = 20;
    private static final int PLAYER_INVENTORY_X = 8;
    private static final int PLAYER_INVENTORY_Y = 51;
    private static final int HOTBAR_Y = 109;

    private final Player owner;
    private final SimpleContainer container;
    private final InteractionHand carrierHand;
    private final int carrierSlotIndex;

    // 无手参数构造：MenuType 客户端侧使用，自动检测载体手持
    public SpecimenBoxMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, detectCarrierHand(playerInventory));
    }

    // 带手参数构造：SimpleMenuProvider 服务端侧使用
    public SpecimenBoxMenu(int containerId, Inventory playerInventory, InteractionHand hand) {
        super(TYPE, containerId);
        this.owner = playerInventory.player;
        this.carrierHand = hand;
        this.carrierSlotIndex = hand == InteractionHand.OFF_HAND
                ? Inventory.SLOT_OFFHAND : playerInventory.selected;
        this.container = new SimpleContainer(CONTAINER_SIZE) {
            @Override
            public void setChanged() {
                super.setChanged();
                SpecimenBoxMenu.this.saveToCarrier();
            }
        };
        this.loadFromCarrier();
        this.addContainerSlots();
        this.addPlayerSlots(playerInventory);
    }

    // 检测载体标本箱所在的手（主手优先）
    private static InteractionHand detectCarrierHand(Inventory playerInventory) {
        Player player = playerInventory.player;
        if (player.getMainHandItem().is(ModItems.SPECIMEN_BOX)) {
            return InteractionHand.MAIN_HAND;
        }
        if (player.getOffhandItem().is(ModItems.SPECIMEN_BOX)) {
            return InteractionHand.OFF_HAND;
        }
        return InteractionHand.MAIN_HAND;
    }

    // 添加 5 格容器槽位
    private void addContainerSlots() {
        for (int i = 0; i < CONTAINER_SIZE; i++) {
            this.addSlot(new Slot(this.container, i, CONTAINER_X + i * 18, CONTAINER_Y));
        }
    }

    // 添加玩家物品栏槽位（3×9 背包 + 9 快捷栏）
    private void addPlayerSlots(Inventory playerInventory) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        PLAYER_INVENTORY_X + col * 18, PLAYER_INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, PLAYER_INVENTORY_X + col * 18, HOTBAR_Y));
        }
    }

    // 从载体 ItemStack 的 CONTAINER 组件加载物品到运行时容器
    private void loadFromCarrier() {
        ItemStack carrier = this.getCarrierStack();
        ItemContainerContents contents = carrier.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        contents.copyInto(this.container.getItems());
    }

    // 将运行时容器物品写回载体 ItemStack 的 CONTAINER 组件
    private void saveToCarrier() {
        ItemStack carrier = this.getCarrierStack();
        carrier.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(this.container.getItems()));
    }

    // 获取载体物品栈
    private ItemStack getCarrierStack() {
        return this.carrierHand == InteractionHand.OFF_HAND
                ? this.owner.getOffhandItem()
                : this.owner.getInventory().getItem(this.carrierSlotIndex);
    }

    // 获取载体所在的手
    public InteractionHand getCarrierHand() {
        return this.carrierHand;
    }

    // 判断指定 slotId 是否为载体物品所在格（阻止抽出标本箱自身）
    private boolean isCarrierMenuSlot(int slotId) {
        if (this.carrierHand == InteractionHand.OFF_HAND) {
            // 副手不在菜单内
            return false;
        }
        return slotId == this.menuSlotIdForPlayerSlot(this.carrierSlotIndex);
    }

    // 玩家槽位索引 -> 菜单 slotId 换算
    private int menuSlotIdForPlayerSlot(int playerSlotIndex) {
        if (playerSlotIndex >= 9 && playerSlotIndex < 36) {
            // 背包 slotId 5-31
            return CONTAINER_SIZE + (playerSlotIndex - 9);
        }
        if (playerSlotIndex >= 0 && playerSlotIndex < 9) {
            // 快捷栏 slotId 32-40
            return CONTAINER_SIZE + 27 + playerSlotIndex;
        }
        return -1;
    }

    @Override
    public void clicked(int slotId, int button, @NotNull ClickType clickType, @NotNull Player player) {
        // 阻止对载体物品槽位的操作（防刷物品）
        if (this.isCarrierMenuSlot(slotId)) {
            return;
        }
        // 阻止 SWAP（数字键交换）对载体槽位
        if (clickType == ClickType.SWAP && this.carrierHand == InteractionHand.MAIN_HAND
                && button == this.carrierSlotIndex) {
            return;
        }
        super.clicked(slotId, button, clickType, player);
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
        // 载体物品必须仍在原位
        if (this.carrierHand == InteractionHand.OFF_HAND) {
            return player.getOffhandItem().is(ModItems.SPECIMEN_BOX);
        }
        if (this.carrierSlotIndex >= 0 && this.carrierSlotIndex < player.getInventory().getContainerSize()) {
            return player.getInventory().getItem(this.carrierSlotIndex).is(ModItems.SPECIMEN_BOX);
        }
        return player.getMainHandItem().is(ModItems.SPECIMEN_BOX) || player.getOffhandItem().is(ModItems.SPECIMEN_BOX);
    }

    @Override
    public void removed(@NotNull Player player) {
        super.removed(player);
        this.container.stopOpen(player);
    }
}
