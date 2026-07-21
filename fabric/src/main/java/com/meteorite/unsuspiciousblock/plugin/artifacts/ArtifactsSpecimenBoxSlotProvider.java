package com.meteorite.unsuspiciousblock.plugin.artifacts;

import artifacts.equipment.EquipmentSlotManager;
import artifacts.equipment.EquipmentSlotProvider;
import artifacts.event.ArtifactHooks;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.plugin.trinket.SpecimenBoxTrinket;
import com.meteorite.unsuspiciousblock.plugin.trinket.TrinketSlotResolver;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxContents;
import dev.emi.trinkets.api.TrinketComponent;
import dev.emi.trinkets.api.TrinketInventory;
import dev.emi.trinkets.api.TrinketsApi;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * Fabric Artifacts 装备查询适配器，将 Trinkets 槽中标本箱的有效内容加入能力扫描。
 */
public final class ArtifactsSpecimenBoxSlotProvider
        implements EquipmentSlotProvider, SpecimenBoxTrinket.Lifecycle {

    private ArtifactsSpecimenBoxSlotProvider() {
    }

    // 仅在 Trinkets 与 Artifacts 均已加载时由 Fabric 初始化入口调用。
    public static ArtifactsSpecimenBoxSlotProvider register() {
        ArtifactsSpecimenBoxSlotProvider provider = new ArtifactsSpecimenBoxSlotProvider();
        EquipmentSlotManager.register(provider);
        return provider;
    }

    @Override
    public <T> T reduceEquipment(LivingEntity entity, T initial,
                                 BiFunction<ItemStack, T, T> reducer) {
        var component = TrinketsApi.getTrinketComponent(entity);
        if (component.isEmpty()) {
            return initial;
        }

        T result = initial;
        TrinketComponent trinkets = component.get();
        for (Map<String, TrinketInventory> group : trinkets.getInventory().values()) {
            for (TrinketInventory inventory : group.values()) {
                for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                    ItemStack equipped = inventory.getItem(slot);
                    if (!equipped.is(ModItems.SPECIMEN_BOX)) {
                        continue;
                    }
                    NonNullList<ItemStack> innerItems = SpecimenBoxContents.read(equipped);
                    NonNullList<ItemStack> previous = SpecimenBoxContents.copy(innerItems);
                    for (int innerSlot = 0; innerSlot < innerItems.size(); innerSlot++) {
                        ItemStack inner = innerItems.get(innerSlot);
                        if (!inner.isEmpty() && !inner.is(ModItems.SPECIMEN_BOX)
                                && TrinketSlotResolver.isValid(inner, trinkets, entity)) {
                            result = reducer.apply(inner, result);
                        }
                    }
                    SpecimenBoxContents.writeIfChanged(equipped, previous, innerItems);
                }
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
        var component = TrinketsApi.getTrinketComponent(entity);
        if (component.isEmpty()) {
            return;
        }
        NonNullList<ItemStack> innerItems = SpecimenBoxContents.read(boxStack);
        for (ItemStack inner : innerItems) {
            if (!inner.isEmpty() && TrinketSlotResolver.isValid(
                    inner, component.get(), entity)) {
                ArtifactHooks.onItemChanged(
                        entity,
                        equipped ? ItemStack.EMPTY : inner,
                        equipped ? inner : ItemStack.EMPTY);
            }
        }
    }
}
