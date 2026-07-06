package com.meteorite.unsuspiciousblock.loot;

import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootInjector;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Fabric 平台考古战利品注入器实现——按 {@link LootInjection} 中定义的概率，
 * 将模组物品替换进古迹废墟战利品表生成结果。
 * <p>
 * 供 {@code BrushableBlockEntityMixin} 与概率模拟器共用，确保两条路径的注入行为一致。
 * NeoForge 端通过 GlobalLootModifier 实现等价语义，无需注册本接口。
 */
public final class FabricArchaeologyLootInjector implements ArchaeologyLootInjector {

    public static final FabricArchaeologyLootInjector INSTANCE = new FabricArchaeologyLootInjector();

    private FabricArchaeologyLootInjector() {
    }

    @Override
    public void maybeReplace(ResourceLocation tableId, List<ItemStack> drops, RandomSource random) {
        if (tableId.equals(LootInjection.TRAIL_RUINS_COMMON_ID)) {
            maybeReplace(drops, ModItems.ANCIENT_COIN, LootInjection.ANCIENT_COIN_CHANCE, random);
        } else if (tableId.equals(LootInjection.TRAIL_RUINS_RARE_ID)) {
            maybeReplace(drops, ModItems.LOST_PAGE, LootInjection.LOST_PAGE_CHANCE, random);
        }
    }

    // 考古表只允许 1 个物品：触发时清空原版结果后放入目标物品，避免被 BrushableBlockEntity 丢弃
    private static void maybeReplace(List<ItemStack> drops, Item item, float chance, RandomSource random) {
        if (random.nextFloat() < chance) {
            drops.clear();
            drops.add(new ItemStack(item));
        }
    }
}
