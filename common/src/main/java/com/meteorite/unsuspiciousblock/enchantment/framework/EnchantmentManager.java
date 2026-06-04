package com.meteorite.unsuspiciousblock.enchantment.framework;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.enchantment.framework.adapter.IEnchantmentEventAdapter;
import com.meteorite.unsuspiciousblock.enchantment.framework.effect.EffectContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.effect.EnchantmentEffect;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerContext;
import com.meteorite.unsuspiciousblock.enchantment.framework.trigger.TriggerType;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 附魔效果统一调度器——接收来自 Platform Adapter 的触发事件，查找已注册的效果组件并执行 */
public final class EnchantmentManager {

    private static final Map<TriggerType, List<Entry>> REGISTRY = new EnumMap<>(TriggerType.class);

    private EnchantmentManager() {}

    // 初始化：注入平台适配器并注册事件监听
    public static void init(IEnchantmentEventAdapter adapter) {
        adapter.registerListeners();
        Constants.LOG.info("EnchantmentManager 已初始化，适配器: {}", adapter.getClass().getSimpleName());
    }

    // 注册附魔效果组件
    public static void register(TriggerType triggerType, ResourceKey<Enchantment> enchantmentKey,
                                EnchantmentEffect effect) {
        REGISTRY.computeIfAbsent(triggerType, k -> new ArrayList<>())
                .add(new Entry(enchantmentKey, effect));
    }

    // 事件入口——由 Platform Adapter 调用
    public static void dispatch(TriggerType triggerType, TriggerContext ctx) {
        List<Entry> entries = REGISTRY.get(triggerType);
        if (entries == null || entries.isEmpty()) return;

        RegistryAccess registryAccess = ctx.player.registryAccess();
        for (Entry entry : entries) {
            Holder<Enchantment> holder = registryAccess.lookupOrThrow(Registries.ENCHANTMENT)
                    .get(entry.enchantmentKey)
                    .orElse(null);
            if (holder == null) continue;

            int level = findEnchantmentLevel(ctx.player, holder);
            if (level <= 0) continue;

            ItemStack enchantedItem = findEnchantedItem(ctx.player, holder);
            EffectContext effectCtx = new EffectContext(
                    ctx.player, ctx.level, ctx.pos,
                    enchantedItem, level, entry.enchantmentKey, ctx);
            entry.effect.apply(effectCtx);
        }
    }

    // 从玩家装备栏（主手、副手、盔甲）中查找最高附魔等级
    private static int findEnchantmentLevel(ServerPlayer player, Holder<Enchantment> enchantment) {
        int maxLevel = 0;
        for (ItemStack stack : iterableEquipment(player)) {
            int level = EnchantmentHelper.getItemEnchantmentLevel(enchantment, stack);
            if (level > maxLevel) maxLevel = level;
        }
        return maxLevel;
    }

    // 查找带有指定附魔的装备物品
    private static ItemStack findEnchantedItem(ServerPlayer player, Holder<Enchantment> enchantment) {
        for (ItemStack stack : iterableEquipment(player)) {
            if (EnchantmentHelper.getItemEnchantmentLevel(enchantment, stack) > 0) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static Iterable<ItemStack> iterableEquipment(ServerPlayer player) {
        List<ItemStack> equipment = new ArrayList<>(6);
        equipment.add(player.getMainHandItem());
        equipment.add(player.getOffhandItem());
        for (ItemStack armor : player.getInventory().armor) {
            equipment.add(armor);
        }
        return equipment;
    }

    private record Entry(ResourceKey<Enchantment> enchantmentKey, EnchantmentEffect effect) {}
}
