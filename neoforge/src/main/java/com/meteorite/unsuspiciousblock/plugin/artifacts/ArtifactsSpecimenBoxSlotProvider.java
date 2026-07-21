package com.meteorite.unsuspiciousblock.plugin.artifacts;

import artifacts.equipment.EquipmentSlotManager;
import artifacts.equipment.EquipmentSlotProvider;
import artifacts.event.ArtifactHooks;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.plugin.curio.SpecimenBoxContents;
import com.meteorite.unsuspiciousblock.plugin.curio.SpecimenBoxCurio;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.function.BiFunction;

/**
 * Artifacts 装备查询适配器，将已装备标本箱中的有效 Curios 饰品加入能力扫描。
 * 该 provider 只提供查询，不参与物品插入、取出或 Artifacts 自动装备。
 */
public final class ArtifactsSpecimenBoxSlotProvider
        implements EquipmentSlotProvider, SpecimenBoxCurio.Lifecycle {

    private ArtifactsSpecimenBoxSlotProvider() {
    }

    // 仅在 Curios 与 Artifacts 均已加载时由 NeoForge 初始化入口调用。
    public static ArtifactsSpecimenBoxSlotProvider register() {
        ArtifactsSpecimenBoxSlotProvider provider = new ArtifactsSpecimenBoxSlotProvider();
        EquipmentSlotManager.register(provider);
        return provider;
    }

    @Override
    public <T> T reduceEquipment(LivingEntity entity, T initial,
                                 BiFunction<ItemStack, T, T> reducer) {
        var inventory = CuriosApi.getCuriosInventory(entity);
        if (inventory.isEmpty()) {
            return initial;
        }

        T result = initial;
        for (ICurioStacksHandler stacksHandler : inventory.get().getCurios().values()) {
            IDynamicStackHandler equippedStacks = stacksHandler.getStacks();
            for (int slot = 0; slot < equippedStacks.getSlots(); slot++) {
                ItemStack equipped = equippedStacks.getStackInSlot(slot);
                if (!equipped.is(ModItems.SPECIMEN_BOX)) {
                    continue;
                }
                NonNullList<ItemStack> innerItems = SpecimenBoxContents.read(equipped);
                NonNullList<ItemStack> previous = SpecimenBoxContents.copy(innerItems);
                for (ItemStack inner : innerItems) {
                    if (!inner.is(ModItems.SPECIMEN_BOX)
                            && !inner.isEmpty()
                            && !CuriosApi.getItemStackSlots(inner, entity).isEmpty()) {
                        result = reducer.apply(inner, result);
                    }
                }
                SpecimenBoxContents.writeIfChanged(equipped, previous, innerItems);
            }
        }
        return result;
    }

    @Override
    public boolean tryEquipItem(LivingEntity entity, ItemStack stack) {
        return false;
    }

    @Override
    public void onEquip(LivingEntity entity, ItemStack boxStack) {
        notifyItemChanges(entity, boxStack, true);
    }

    @Override
    public void onUnequip(LivingEntity entity, ItemStack boxStack) {
        notifyItemChanges(entity, boxStack, false);
    }

    private static void notifyItemChanges(LivingEntity entity, ItemStack boxStack,
                                          boolean equipped) {
        for (ItemStack inner : SpecimenBoxContents.read(boxStack)) {
            if (!inner.isEmpty() && !CuriosApi.getItemStackSlots(inner, entity).isEmpty()) {
                ArtifactHooks.onItemChanged(
                        entity,
                        equipped ? ItemStack.EMPTY : inner,
                        equipped ? inner : ItemStack.EMPTY);
            }
        }
    }
}
