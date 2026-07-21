package com.meteorite.unsuspiciousblock.plugin.trinket;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxContents;
import com.meteorite.unsuspiciousblock.specimen.SpecimenBoxMenu;
import dev.emi.trinkets.api.SlotReference;
import dev.emi.trinkets.api.Trinket;
import dev.emi.trinkets.api.TrinketEnums;
import dev.emi.trinkets.api.TrinketsApi;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 标本箱 Trinkets 代理，将箱内物品按各自 validator 模拟为独立 Trinket。
 */
public final class SpecimenBoxTrinket implements Trinket {
    private final Lifecycle lifecycle;

    public SpecimenBoxTrinket(Lifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public void tick(ItemStack stack, SlotReference slotReference, LivingEntity entity) {
        mutateInnerTrinkets(stack, slotReference, entity,
                inner -> inner.trinket().tick(inner.stack(), inner.reference(), entity));
    }

    @Override
    public void onEquip(ItemStack stack, SlotReference slotReference, LivingEntity entity) {
        mutateInnerTrinkets(stack, slotReference, entity,
                inner -> inner.trinket().onEquip(inner.stack(), inner.reference(), entity));
        this.lifecycle.onEquip(entity, stack);
    }

    @Override
    public void onUnequip(ItemStack stack, SlotReference slotReference, LivingEntity entity) {
        this.lifecycle.onUnequip(entity, stack);
        mutateInnerTrinkets(stack, slotReference, entity,
                inner -> inner.trinket().onUnequip(inner.stack(), inner.reference(), entity));
    }

    @Override
    public boolean canUnequip(ItemStack stack, SlotReference slotReference, LivingEntity entity) {
        return true;
    }

    @Override
    public boolean canEquipFromUse(ItemStack stack, LivingEntity entity) {
        // 右键保留给标本箱 GUI；仍可在 Trinkets 界面中手动装备。
        return false;
    }

    @Override
    public Multimap<Holder<Attribute>, AttributeModifier> getModifiers(
            ItemStack stack, SlotReference slotReference, LivingEntity entity,
            ResourceLocation id) {
        Multimap<Holder<Attribute>, AttributeModifier> result = LinkedHashMultimap.create();
        for (InnerTrinket inner : findInnerTrinkets(stack, slotReference, entity)) {
            result.putAll(inner.trinket().getModifiers(
                    inner.stack(), inner.reference(), entity,
                    innerModifierId(id, inner.containerSlot())));
        }
        return result;
    }

    @Override
    public void onBreak(ItemStack stack, SlotReference slotReference, LivingEntity entity) {
        mutateInnerTrinkets(stack, slotReference, entity,
                inner -> inner.trinket().onBreak(inner.stack(), inner.reference(), entity));
    }

    @Override
    public TrinketEnums.DropRule getDropRule(
            ItemStack stack, SlotReference slotReference, LivingEntity entity) {
        for (InnerTrinket inner : findInnerTrinkets(stack, slotReference, entity)) {
            if (inner.trinket().getDropRule(
                    inner.stack(), inner.reference(), entity) == TrinketEnums.DropRule.KEEP) {
                // 标本箱无法拆分掉落；任一内部饰品要求保留时保留整个箱子。
                return TrinketEnums.DropRule.KEEP;
            }
        }
        return TrinketEnums.DropRule.DEFAULT;
    }

    // 执行可能修改内部 ItemStack 的回调，并仅在内容实际变化时写回组件。
    private static void mutateInnerTrinkets(ItemStack boxStack, SlotReference boxReference,
                                            LivingEntity entity, Consumer<InnerTrinket> action) {
        NonNullList<ItemStack> items = SpecimenBoxContents.read(boxStack);
        NonNullList<ItemStack> previous = SpecimenBoxContents.copy(items);
        for (InnerTrinket inner : findInnerTrinkets(items, boxReference, entity)) {
            action.accept(inner);
        }
        SpecimenBoxContents.writeIfChanged(boxStack, previous, items);
    }

    private static List<InnerTrinket> findInnerTrinkets(
            ItemStack boxStack, SlotReference boxReference, LivingEntity entity) {
        return findInnerTrinkets(SpecimenBoxContents.read(boxStack), boxReference, entity);
    }

    private static List<InnerTrinket> findInnerTrinkets(
            NonNullList<ItemStack> items, SlotReference boxReference, LivingEntity entity) {
        List<InnerTrinket> result = new ArrayList<>();
        for (int containerSlot = 0; containerSlot < items.size(); containerSlot++) {
            ItemStack innerStack = items.get(containerSlot);
            if (innerStack.isEmpty()) {
                continue;
            }
            int virtualIndex = boxReference.index() * SpecimenBoxMenu.CONTAINER_SIZE + containerSlot;
            int currentSlot = containerSlot;
            TrinketSlotResolver.find(
                    innerStack, boxReference.inventory().getComponent(), entity, virtualIndex)
                    .ifPresent(reference -> result.add(new InnerTrinket(
                            currentSlot, innerStack,
                            TrinketsApi.getTrinket(innerStack.getItem()), reference)));
        }
        return result;
    }

    private static ResourceLocation innerModifierId(ResourceLocation boxSlotId, int containerSlot) {
        return ResourceLocation.fromNamespaceAndPath(
                boxSlotId.getNamespace(), boxSlotId.getPath() + "/specimen_box/" + containerSlot);
    }

    private record InnerTrinket(int containerSlot, ItemStack stack, Trinket trinket,
                                SlotReference reference) {
    }

    /**
     * 第三方饰品系统的装备生命周期桥接点。
     */
    public interface Lifecycle {
        Lifecycle NONE = new Lifecycle() {
        };

        default void onEquip(LivingEntity entity, ItemStack boxStack) {
        }

        default void onUnequip(LivingEntity entity, ItemStack boxStack) {
        }
    }
}
