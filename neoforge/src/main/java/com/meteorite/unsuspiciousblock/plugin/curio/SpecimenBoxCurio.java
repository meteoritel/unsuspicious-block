package com.meteorite.unsuspiciousblock.plugin.curio;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.ISlotType;
import top.theillusivec4.curios.api.type.capability.ICurio;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 标本箱 Curios 代理，将箱内物品按各自注册的 slot type 模拟为独立饰品。
 * 本类负责 Curios 回调与属性结算，第三方模组的装备扫描由各自的可选适配器接入。
 */
public final class SpecimenBoxCurio implements ICurioItem {
    private final Lifecycle lifecycle;

    public SpecimenBoxCurio() {
        this(Lifecycle.NONE);
    }

    public SpecimenBoxCurio(Lifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public void curioTick(SlotContext slotContext, ItemStack stack) {
        mutateInnerCurios(stack, slotContext.entity(), inner -> inner.curio().curioTick(inner.context()));
    }

    @Override
    public void onEquip(SlotContext slotContext, ItemStack prevStack, ItemStack stack) {
        mutateInnerCurios(stack, slotContext.entity(),
                inner -> inner.curio().onEquip(inner.context(), ItemStack.EMPTY));
        this.lifecycle.onEquip(slotContext.entity(), stack);
    }

    @Override
    public void onUnequip(SlotContext slotContext, ItemStack newStack, ItemStack stack) {
        this.lifecycle.onUnequip(slotContext.entity(), stack);
        mutateInnerCurios(stack, slotContext.entity(),
                inner -> inner.curio().onUnequip(inner.context(), ItemStack.EMPTY));
    }

    @Override
    public boolean canEquip(SlotContext slotContext, ItemStack stack) {
        return true;
    }

    @Override
    public boolean canUnequip(SlotContext slotContext, ItemStack stack) {
        return true;
    }

    @Override
    public Multimap<Holder<Attribute>, AttributeModifier> getAttributeModifiers(
            SlotContext slotContext, ResourceLocation id, ItemStack stack) {
        Multimap<Holder<Attribute>, AttributeModifier> result = LinkedHashMultimap.create();
        for (InnerCurio inner : findInnerCurios(stack, slotContext.entity())) {
            result.putAll(CuriosApi.getAttributeModifiers(
                    inner.context(), innerModifierId(id, inner.containerSlot()), inner.stack()));
        }
        return result;
    }

    @Override
    public boolean canEquipFromUse(SlotContext slotContext, ItemStack stack) {
        // 右键保留给标本箱 GUI；仍可在 Curios 界面中手动装备。
        return false;
    }

    @Override
    public void curioBreak(SlotContext slotContext, ItemStack stack) {
        mutateInnerCurios(stack, slotContext.entity(),
                inner -> inner.curio().curioBreak(inner.context()));
    }

    @Override
    public boolean canSync(SlotContext slotContext, ItemStack stack) {
        // 内部物品的组件变更会写回标本箱，并由标本箱 ItemStack 自身同步。
        return false;
    }

    @Override
    public ICurio.@NotNull DropRule getDropRule(SlotContext slotContext, DamageSource source,
                                                 boolean recentlyHit, ItemStack stack) {
        for (InnerCurio inner : findInnerCurios(stack, slotContext.entity())) {
            ICurio.DropRule rule = inner.curio().getDropRule(
                    inner.context(), source, recentlyHit);
            if (rule == ICurio.DropRule.ALWAYS_KEEP) {
                // 标本箱无法拆分掉落；任一内部饰品要求保留时保留整个箱子。
                return ICurio.DropRule.ALWAYS_KEEP;
            }
        }
        return ICurio.DropRule.DEFAULT;
    }

    @Override
    public List<Component> getSlotsTooltip(List<Component> tooltips, TooltipContext context,
                                            ItemStack stack) {
        List<Component> result = tooltips;
        for (ICurio curio : findInnerCuriosForTooltip(stack)) {
            result = curio.getSlotsTooltip(result, context);
        }
        return result;
    }

    @Override
    public List<Component> getAttributesTooltip(List<Component> tooltips, TooltipContext context,
                                                 ItemStack stack) {
        List<Component> result = tooltips;
        for (ICurio curio : findInnerCuriosForTooltip(stack)) {
            result = curio.getAttributesTooltip(result, context);
        }
        return result;
    }

    @Override
    public int getFortuneLevel(SlotContext slotContext, @Nullable LootContext lootContext,
                               ItemStack stack) {
        return findInnerCurios(stack, slotContext.entity()).stream()
                .mapToInt(inner -> inner.curio().getFortuneLevel(inner.context(), lootContext))
                .max()
                .orElse(0);
    }

    @Override
    public int getLootingLevel(SlotContext slotContext, @Nullable LootContext lootContext,
                               ItemStack stack) {
        return findInnerCurios(stack, slotContext.entity()).stream()
                .mapToInt(inner -> inner.curio().getLootingLevel(inner.context(), lootContext))
                .max()
                .orElse(0);
    }

    @Override
    public boolean makesPiglinsNeutral(SlotContext slotContext, ItemStack stack) {
        return findInnerCurios(stack, slotContext.entity()).stream()
                .anyMatch(inner -> inner.curio().makesPiglinsNeutral(inner.context()));
    }

    @Override
    public boolean canWalkOnPowderedSnow(SlotContext slotContext, ItemStack stack) {
        return findInnerCurios(stack, slotContext.entity()).stream()
                .anyMatch(inner -> inner.curio().canWalkOnPowderedSnow(inner.context()));
    }

    @Override
    public boolean isEnderMask(SlotContext slotContext, EnderMan enderMan, ItemStack stack) {
        return findInnerCurios(stack, slotContext.entity()).stream()
                .anyMatch(inner -> inner.curio().isEnderMask(inner.context(), enderMan));
    }

    // 执行可能修改内部 ItemStack 的回调，并仅在内容实际变化时写回组件。
    private static void mutateInnerCurios(ItemStack boxStack, LivingEntity entity,
                                          Consumer<InnerCurio> action) {
        NonNullList<ItemStack> items = SpecimenBoxContents.read(boxStack);
        NonNullList<ItemStack> previous = SpecimenBoxContents.copy(items);
        for (InnerCurio inner : findInnerCurios(items, entity)) {
            action.accept(inner);
        }
        SpecimenBoxContents.writeIfChanged(boxStack, previous, items);
    }

    private static List<InnerCurio> findInnerCurios(ItemStack boxStack, LivingEntity entity) {
        return findInnerCurios(SpecimenBoxContents.read(boxStack), entity);
    }

    private static List<InnerCurio> findInnerCurios(NonNullList<ItemStack> items,
                                                     LivingEntity entity) {
        List<InnerCurio> result = new ArrayList<>();
        Map<String, Integer> indicesBySlot = new HashMap<>();
        for (int containerSlot = 0; containerSlot < items.size(); containerSlot++) {
            ItemStack innerStack = items.get(containerSlot);
            if (innerStack.isEmpty()) {
                continue;
            }
            Map<String, ISlotType> validSlots = CuriosApi.getItemStackSlots(innerStack, entity);
            if (validSlots.isEmpty()) {
                continue;
            }
            String slotIdentifier = validSlots.keySet().iterator().next();
            int virtualIndex = indicesBySlot.getOrDefault(slotIdentifier, 0);
            indicesBySlot.put(slotIdentifier, virtualIndex + 1);
            SlotContext context = new SlotContext(
                    slotIdentifier, entity, virtualIndex, false, true);
            int currentSlot = containerSlot;
            CuriosApi.getCurio(innerStack).ifPresent(curio ->
                    result.add(new InnerCurio(currentSlot, innerStack, curio, context)));
        }
        return result;
    }

    private static List<ICurio> findInnerCuriosForTooltip(ItemStack boxStack) {
        List<ICurio> result = new ArrayList<>();
        for (ItemStack innerStack : SpecimenBoxContents.read(boxStack)) {
            if (!innerStack.isEmpty()) {
                CuriosApi.getCurio(innerStack).ifPresent(result::add);
            }
        }
        return result;
    }

    private static ResourceLocation innerModifierId(ResourceLocation boxSlotId, int containerSlot) {
        return ResourceLocation.fromNamespaceAndPath(
                boxSlotId.getNamespace(), boxSlotId.getPath() + "/specimen_box/" + containerSlot);
    }

    private record InnerCurio(int containerSlot, ItemStack stack, ICurio curio,
                              SlotContext context) {
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
