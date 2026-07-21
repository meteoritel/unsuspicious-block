package com.meteorite.unsuspiciousblock.plugin.trinket;

import dev.emi.trinkets.api.SlotReference;
import dev.emi.trinkets.api.SlotType;
import dev.emi.trinkets.api.TrinketComponent;
import dev.emi.trinkets.api.TrinketInventory;
import dev.emi.trinkets.api.TrinketsApi;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;

/**
 * 根据 Trinkets slot validator 为标本箱内部物品解析目标槽位上下文。
 */
public final class TrinketSlotResolver {

    private TrinketSlotResolver() {
    }

    // 返回第一个通过 validator 的真实 slot type，并使用独立虚拟索引构造回调上下文。
    public static Optional<SlotReference> find(ItemStack stack, TrinketComponent component,
                                               LivingEntity entity, int virtualIndex) {
        return findTargetInventory(stack, component, entity)
                .map(target -> createVirtualReference(stack, target, component, virtualIndex));
    }

    // 仅判断物品是否存在有效目标槽位，不创建回调用 inventory。
    public static boolean isValid(ItemStack stack, TrinketComponent component,
                                  LivingEntity entity) {
        return findTargetInventory(stack, component, entity).isPresent();
    }

    private static Optional<TrinketInventory> findTargetInventory(
            ItemStack stack, TrinketComponent component, LivingEntity entity) {
        for (Map<String, TrinketInventory> group : component.getInventory().values()) {
            for (TrinketInventory inventory : group.values()) {
                if (inventory.getContainerSize() <= 0) {
                    continue;
                }
                SlotReference reference = new SlotReference(inventory, 0);
                if (TrinketsApi.evaluatePredicateSet(
                        inventory.getSlotType().getValidatorPredicates(), stack, reference, entity)) {
                    return Optional.of(inventory);
                }
            }
        }
        return Optional.empty();
    }

    private static SlotReference createVirtualReference(
            ItemStack stack, TrinketInventory target, TrinketComponent component,
            int virtualIndex) {
        SlotType targetType = target.getSlotType();
        SlotType virtualType = new SlotType(
                targetType.getGroup(),
                targetType.getName(),
                targetType.getOrder(),
                Math.max(targetType.getAmount(), virtualIndex + 1),
                targetType.getIcon(),
                targetType.getQuickMovePredicates(),
                targetType.getValidatorPredicates(),
                targetType.getTooltipPredicates(),
                targetType.getDropRule());
        TrinketInventory virtualInventory = new TrinketInventory(
                virtualType, component, ignored -> {
                });
        virtualInventory.setItem(virtualIndex, stack);
        return new SlotReference(virtualInventory, virtualIndex);
    }
}
