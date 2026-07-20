package com.meteorite.unsuspiciousblock.specimen;

import com.meteorite.unsuspiciousblock.item.ModItems;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.*;

/**
 * 标本箱快速交互服务端逻辑：放入/取出 + 滚动选择状态管理。
 */
public final class SpecimenBoxQuickInteraction {
    private static final Map<UUID, Integer> PLAYER_SELECTED_SLOTS = new HashMap<>();

    private SpecimenBoxQuickInteraction() {
    }

    // 存储玩家滚动选择的内部槽位
    public static void handleScroll(ServerPlayer player, int selectedInnerSlot) {
        PLAYER_SELECTED_SLOTS.put(player.getUUID(), selectedInnerSlot);
    }

    // 获取玩家选中的内部槽位，-1 表示取最后一个
    public static int getSelectedInnerSlot(ServerPlayer player) {
        return PLAYER_SELECTED_SLOTS.getOrDefault(player.getUUID(), -1);
    }

    // 玩家断开时清理
    public static void clearPlayer(ServerPlayer player) {
        PLAYER_SELECTED_SLOTS.remove(player.getUUID());
    }

    /**
     * 尝试将手中物品放入标本箱。
     *
     * @param count 要放入的数量（整组或 1 个）
     * @return 是否成功放入（至少一个物品）
     */
    public static boolean tryInsert(AbstractContainerMenu menu, Slot specimenSlot, Player player, int count) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) return false;

        ItemStack box = specimenSlot.getItem();
        if (!box.is(ModItems.SPECIMEN_BOX)) return false;

        int toInsert = Math.min(count, carried.getCount());
        ItemContainerContents contents = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        NonNullList<ItemStack> items = NonNullList.createWithCapacity(SpecimenBoxMenu.CONTAINER_SIZE);
        contents.copyInto(items);

        int remaining = toInsert;

        // 先尝试合并到已有堆叠
        for (int i = 0; i < items.size() && remaining > 0; i++) {
            ItemStack slotStack = items.get(i);
            if (!slotStack.isEmpty() && ItemStack.isSameItemSameComponents(slotStack, carried)) {
                int canAdd = Math.min(remaining, slotStack.getMaxStackSize() - slotStack.getCount());
                if (canAdd > 0) {
                    slotStack.grow(canAdd);
                    remaining -= canAdd;
                }
            }
        }

        // 再尝试放入空格
        for (int i = 0; i < items.size() && remaining > 0; i++) {
            ItemStack slotStack = items.get(i);
            if (slotStack.isEmpty()) {
                int canAdd = Math.min(remaining, carried.getMaxStackSize());
                items.set(i, carried.copyWithCount(canAdd));
                remaining -= canAdd;
            }
        }

        int inserted = toInsert - remaining;
        if (inserted <= 0) return false;

        // 写回标本箱
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        specimenSlot.setChanged();

        // 减少手中物品
        carried.shrink(inserted);
        if (carried.isEmpty()) {
            menu.setCarried(ItemStack.EMPTY);
        }

        player.level().playSound(null, player, SoundEvents.BUNDLE_INSERT, SoundSource.PLAYERS, 1.0F, 1.0F);
        return true;
    }

    /**
     * 从标本箱中取出选中槽位的一组物品到光标上。
     *
     * @return 是否成功取出
     */
    public static boolean tryExtract(AbstractContainerMenu menu, Slot specimenSlot, ServerPlayer player) {
        ItemStack box = specimenSlot.getItem();
        if (!box.is(ModItems.SPECIMEN_BOX)) return false;

        ItemContainerContents contents = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        NonNullList<ItemStack> items = NonNullList.createWithCapacity(SpecimenBoxMenu.CONTAINER_SIZE);
        contents.copyInto(items);

        int targetIndex = findTargetSlot(items, getSelectedInnerSlot(player));
        if (targetIndex < 0) return false;

        ItemStack target = items.get(targetIndex);
        if (target.isEmpty()) return false;

        // 取出整组
        ItemStack extracted = target.copy();
        items.set(targetIndex, ItemStack.EMPTY);

        // 写回标本箱
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        specimenSlot.setChanged();

        // 放到光标上
        menu.setCarried(extracted);

        player.level().playSound(null, player, SoundEvents.BUNDLE_REMOVE_ONE, SoundSource.PLAYERS, 1.0F, 1.0F);
        return true;
    }

    // 找到目标内部槽位：优先使用选中索引，否则取最后一个非空槽位
    private static int findTargetSlot(List<ItemStack> items, int selected) {
        List<Integer> nonEmptyIndices = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isEmpty()) nonEmptyIndices.add(i);
        }
        if (nonEmptyIndices.isEmpty()) return -1;

        if (selected >= 0 && selected < items.size() && !items.get(selected).isEmpty()) {
            return selected;
        }
        return nonEmptyIndices.get(nonEmptyIndices.size() - 1);
    }
}