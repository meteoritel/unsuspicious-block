package com.meteorite.unsuspiciousblock.specimen;

import com.meteorite.unsuspiciousblock.item.SpecimenBoxItem;
import com.meteorite.unsuspiciousblock.platform.Services;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gameevent.GameEvent;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 标本箱不死图腾代理：从玩家背包或饰品栏中的标本箱消费图腾，并复现原版保护结果。
 */
public final class SpecimenBoxTotemProxy {

    private SpecimenBoxTotemProxy() {
    }

    // 死亡事件确认原版手持图腾未生效后，尝试消费标本箱中的图腾。
    public static boolean tryActivate(LivingEntity entity, DamageSource source,
                                      Consumer<LivingEntity> clearEffects) {
        if (!(entity instanceof Player player)
                || entity.level().isClientSide()
                || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return false;
        }

        ItemStack usedTotem = consumeTotem(player);
        if (usedTotem.isEmpty()) {
            return false;
        }

        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.awardStat(Stats.ITEM_USED.get(Items.TOTEM_OF_UNDYING));
            CriteriaTriggers.USED_TOTEM.trigger(serverPlayer, usedTotem);
            player.gameEvent(GameEvent.ITEM_INTERACT_FINISH);
        }

        player.setHealth(1.0F);
        clearEffects.accept(player);
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1));
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1));
        player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0));
        player.level().broadcastEntityEvent(player, (byte) 35);
        return true;
    }

    // 仅代理标本箱内部图腾，不消耗玩家背包中的普通散装图腾。
    private static ItemStack consumeTotem(Player player) {
        AtomicReference<ItemStack> usedTotem = new AtomicReference<>(ItemStack.EMPTY);
        Inventory inventory = player.getInventory();
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            if (consumeFromBox(inventory.getItem(index), usedTotem)) {
                return usedTotem.get();
            }
        }
        for (ItemStack equipped : Services.ACCESSORY.streamEquippedStacks(player).toList()) {
            if (consumeFromBox(equipped, usedTotem)) {
                return usedTotem.get();
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean consumeFromBox(ItemStack boxStack, AtomicReference<ItemStack> usedTotem) {
        if (!(boxStack.getItem() instanceof SpecimenBoxItem box)) {
            return false;
        }
        return box.mutateFirst(boxStack, stack -> stack.is(Items.TOTEM_OF_UNDYING), stack -> {
            usedTotem.set(stack.copy());
            stack.shrink(1);
        });
    }
}
