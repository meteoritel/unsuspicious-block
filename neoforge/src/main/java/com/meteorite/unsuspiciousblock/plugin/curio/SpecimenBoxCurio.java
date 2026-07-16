package com.meteorite.unsuspiciousblock.plugin.curio;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.storage.loot.LootContext;
import org.jetbrains.annotations.NotNull;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.ISlotType;
import top.theillusivec4.curios.api.type.capability.ICurio;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 标本箱 Curios 代理——将标本箱放入饰品栏时，模拟内部物品为独立饰品。
 * <p>
 * 核心机制：标本箱实现 ICurioItem，在 Curios 回调中遍历内部物品，
 * 通过 CuriosApi.getItemStackSlots() 查询每个内部物品的注册槽位类型，
 * 合成正确的 SlotContext 后转发所有 API 调用。
 * <p>
 * 仅在 Curios 已安装时通过 CuriosApi.registerCurio() 注册。
 */
public class SpecimenBoxCurio implements ICurioItem {

    // 提取标本箱内所有非空物品，对每个内部物品获取其 ICurio 并合成正确 SlotContext 后执行回调
    private static void forEachInnerCurio(ItemStack boxStack, LivingEntity entity,
                                           BiConsumer<ICurio, SlotContext> action) {
        ItemContainerContents contents = boxStack.getOrDefault(DataComponents.CONTAINER,
                ItemContainerContents.EMPTY);
        for (ItemStack inner : contents.nonEmptyStream().toList()) {
            Map<String, ISlotType> validSlots = CuriosApi.getItemStackSlots(inner, entity);
            String slotId = validSlots.isEmpty() ? "curio" : validSlots.keySet().iterator().next();
            SlotContext synthetic = new SlotContext(slotId, entity, 0, false, true);
            CuriosApi.getCurio(inner).ifPresent(curio -> action.accept(curio, synthetic));
        }
    }

    // 提取标本箱内所有非空物品，对每个内部物品获取其 ICurio 并合成正确 SlotContext 后执行 int 查询，取最大值
    private static int forEachInnerCurioMaxInt(ItemStack boxStack, LivingEntity entity,
                                                ToIntBiFunction<ICurio, SlotContext> query) {
        ItemContainerContents contents = boxStack.getOrDefault(DataComponents.CONTAINER,
                ItemContainerContents.EMPTY);
        return contents.nonEmptyStream()
                .mapToInt(inner -> {
                    Map<String, ISlotType> validSlots = CuriosApi.getItemStackSlots(inner, entity);
                    if (validSlots.isEmpty()) return 0;
                    String slotId = validSlots.keySet().iterator().next();
                    SlotContext synthetic = new SlotContext(slotId, entity, 0, false, true);
                    return CuriosApi.getCurio(inner)
                            .map(curio -> query.apply(curio, synthetic))
                            .orElse(0);
                })
                .max()
                .orElse(0);
    }

    // 提取标本箱内所有非空物品，对每个内部物品获取其 ICurio 并合成正确 SlotContext 后执行 boolean 查询，任一为 true 则返回 true
    private static boolean forEachInnerCurioAnyMatch(ItemStack boxStack, LivingEntity entity,
                                                      BooleanBiFunction<ICurio, SlotContext> query) {
        ItemContainerContents contents = boxStack.getOrDefault(DataComponents.CONTAINER,
                ItemContainerContents.EMPTY);
        return contents.nonEmptyStream().anyMatch(inner -> {
            Map<String, ISlotType> validSlots = CuriosApi.getItemStackSlots(inner, entity);
            if (validSlots.isEmpty()) return false;
            String slotId = validSlots.keySet().iterator().next();
            SlotContext synthetic = new SlotContext(slotId, entity, 0, false, true);
            return CuriosApi.getCurio(inner)
                    .map(curio -> query.apply(curio, synthetic))
                    .orElse(false);
        });
    }

    // ========== ICurioItem 回调代理 ==========

    @Override
    public void curioTick(SlotContext slotContext, ItemStack stack) {
        forEachInnerCurio(stack, slotContext.entity(),
                ICurio::curioTick);
    }

    @Override
    public void onEquip(SlotContext slotContext, ItemStack prevStack, ItemStack stack) {
        forEachInnerCurio(stack, slotContext.entity(),
                (curio, ctx) -> curio.onEquip(ctx, ItemStack.EMPTY));
    }

    @Override
    public void onUnequip(SlotContext slotContext, ItemStack newStack, ItemStack stack) {
        forEachInnerCurio(stack, slotContext.entity(),
                (curio, ctx) -> curio.onUnequip(ctx, ItemStack.EMPTY));
    }

    @Override
    public boolean canEquip(SlotContext slotContext, ItemStack stack) {
        // 标本箱本身始终可装备
        return true;
    }

    @Override
    public boolean canUnequip(SlotContext slotContext, ItemStack stack) {
        // 标本箱本身始终可卸下
        return true;
    }

    @Override
    public Multimap<Holder<Attribute>, AttributeModifier> getAttributeModifiers(
            SlotContext slotContext, ResourceLocation id, ItemStack stack) {
        Multimap<Holder<Attribute>, AttributeModifier> result = LinkedHashMultimap.create();
        ItemContainerContents contents = stack.getOrDefault(DataComponents.CONTAINER,
                ItemContainerContents.EMPTY);
        for (ItemStack inner : contents.nonEmptyStream().toList()) {
            LivingEntity entity = slotContext.entity();
            Map<String, ISlotType> validSlots = CuriosApi.getItemStackSlots(inner, entity);
            String slotId = validSlots.isEmpty() ? "curio" : validSlots.keySet().iterator().next();
            SlotContext synthetic = new SlotContext(slotId, entity, 0, false, true);
            // 使用 CuriosApi 静态方法以同时处理数据组件和 ICurio 两种来源
            result.putAll(CuriosApi.getAttributeModifiers(synthetic,
                    CuriosApi.getSlotId(synthetic), inner));
        }
        return result;
    }

    @Override
    public void onEquipFromUse(SlotContext slotContext, ItemStack stack) {
        forEachInnerCurio(stack, slotContext.entity(),
                ICurio::onEquipFromUse);
    }

    @Override
    public boolean canEquipFromUse(SlotContext slotContext, ItemStack stack) {
        // 标本箱本身始终允许右键装备
        return true;
    }

//    @Override
//    public ICurio.SoundInfo getEquipSound(SlotContext slotContext, ItemStack stack) {
//        // 返回内部物品中第一个非默认的装备音效，否则返回空
//        ItemContainerContents contents = stack.getOrDefault(DataComponents.CONTAINER,
//                ItemContainerContents.EMPTY);
//        for (ItemStack inner : contents.nonEmptyStream().toList()) {
//            LivingEntity entity = slotContext.entity();
//            Map<String, ISlotType> validSlots = CuriosApi.getItemStackSlots(inner, entity);
//            if (validSlots.isEmpty()) continue;
//            String slotId = validSlots.keySet().iterator().next();
//            SlotContext synthetic = new SlotContext(slotId, entity, 0, false, true);
//            var sound = CuriosApi.getCurio(inner)
//                    .map(curio -> curio.getEquipSound(synthetic));
//            if (sound.isPresent()) {
//                return sound.get();
//            }
//        }
//        return ICurioItem.super.getEquipSound(slotContext, stack);
//    }

    @Override
    public void curioBreak(SlotContext slotContext, ItemStack stack) {
        forEachInnerCurio(stack, slotContext.entity(),
                ICurio::curioBreak);
    }

    @Override
    public boolean canSync(SlotContext slotContext, ItemStack stack) {
        // 内部物品数据已通过 CONTAINER 组件同步，无需额外同步
        return false;
    }

    @Override
    public ICurio.@NotNull DropRule getDropRule(SlotContext slotContext, DamageSource source,
                                                boolean recentlyHit, ItemStack stack) {
        // 取最严格的掉落规则：任一内部物品要求保留则保留
        ItemContainerContents contents = stack.getOrDefault(DataComponents.CONTAINER,
                ItemContainerContents.EMPTY);
        for (ItemStack inner : contents.nonEmptyStream().toList()) {
            LivingEntity entity = slotContext.entity();
            Map<String, ISlotType> validSlots = CuriosApi.getItemStackSlots(inner, entity);
            if (validSlots.isEmpty()) continue;
            String slotId = validSlots.keySet().iterator().next();
            SlotContext synthetic = new SlotContext(slotId, entity, 0, false, true);
            ICurio.DropRule rule = CuriosApi.getCurio(inner)
                    .map(curio -> curio.getDropRule(synthetic, source, recentlyHit))
                    .orElse(ICurio.DropRule.DEFAULT);
            if (rule == ICurio.DropRule.ALWAYS_KEEP) {
                return ICurio.DropRule.ALWAYS_KEEP;
            }
        }
        return ICurio.DropRule.DEFAULT;
    }

    @Override
    public List<Component> getSlotsTooltip(List<Component> tooltips, TooltipContext context,
                                            ItemStack stack) {
        // 聚合所有内部物品的槽位提示
        List<Component> result = tooltips;
        for (ItemStack inner : stack.getOrDefault(DataComponents.CONTAINER,
                ItemContainerContents.EMPTY).nonEmptyStream().toList()) {
            List<Component> finalResult = result;
            result = CuriosApi.getCurio(inner)
                    .map(curio -> curio.getSlotsTooltip(finalResult, context))
                    .orElse(finalResult);
        }
        return result;
    }

    @Override
    public List<Component> getAttributesTooltip(List<Component> tooltips, TooltipContext context,
                                                 ItemStack stack) {
        // 聚合所有内部物品的属性提示
        List<Component> result = tooltips;
        for (ItemStack inner : stack.getOrDefault(DataComponents.CONTAINER,
                ItemContainerContents.EMPTY).nonEmptyStream().toList()) {
            List<Component> finalResult = result;
            result = CuriosApi.getCurio(inner)
                    .map(curio -> curio.getAttributesTooltip(finalResult, context))
                    .orElse(finalResult);
        }
        return result;
    }

    @Override
    public int getFortuneLevel(SlotContext slotContext, @Nullable LootContext lootContext,
                                ItemStack stack) {
        return forEachInnerCurioMaxInt(stack, slotContext.entity(),
                (curio, ctx) -> curio.getFortuneLevel(ctx, lootContext));
    }

    @Override
    public int getLootingLevel(SlotContext slotContext, @Nullable LootContext lootContext,
                                ItemStack stack) {
        return forEachInnerCurioMaxInt(stack, slotContext.entity(),
                (curio, ctx) -> curio.getLootingLevel(ctx, lootContext));
    }

    @Override
    public boolean makesPiglinsNeutral(SlotContext slotContext, ItemStack stack) {
        return forEachInnerCurioAnyMatch(stack, slotContext.entity(),
                ICurio::makesPiglinsNeutral);
    }

    @Override
    public boolean canWalkOnPowderedSnow(SlotContext slotContext, ItemStack stack) {
        return forEachInnerCurioAnyMatch(stack, slotContext.entity(),
                ICurio::canWalkOnPowderedSnow);
    }

    @Override
    public boolean isEnderMask(SlotContext slotContext, EnderMan enderMan, ItemStack stack) {
        return forEachInnerCurioAnyMatch(stack, slotContext.entity(),
                (curio, ctx) -> curio.isEnderMask(ctx, enderMan));
    }

    // ========== 函数式接口 ==========

    @FunctionalInterface
    private interface ToIntBiFunction<T, U> {
        int apply(T t, U u);
    }

    @FunctionalInterface
    private interface BooleanBiFunction<T, U> {
        boolean apply(T t, U u);
    }
}