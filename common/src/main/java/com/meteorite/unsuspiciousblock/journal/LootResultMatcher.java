package com.meteorite.unsuspiciousblock.journal;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 根据运行时最终产物在候选签名中选择最合适的条目。
 */
public final class LootResultMatcher {
    private LootResultMatcher() {
    }

    // 在候选签名中解析当前 stack 应命中的条目
    @Nullable
    public static LootResultSignature resolve(ItemStack stack, Iterable<LootResultSignature> candidates) {
        if (stack.isEmpty()) {
            return null;
        }

        // 同优先级出现多个候选时，宁可保守降级，也不要把掉落错误归到具体条目上。
        Map<Integer, List<LootResultSignature>> matchesByPriority = new TreeMap<>(Comparator.reverseOrder());
        for (LootResultSignature candidate : candidates) {
            if (!matches(stack, candidate)) {
                continue;
            }

            matchesByPriority.computeIfAbsent(priority(candidate), ignored -> new ArrayList<>()).add(candidate);
        }
        if (matchesByPriority.isEmpty()) {
            return null;
        }

        int minimumFallbackPriority = Integer.MIN_VALUE;
        for (Map.Entry<Integer, List<LootResultSignature>> entry : matchesByPriority.entrySet()) {
            int priority = entry.getKey();
            List<LootResultSignature> matches = distinctSignatures(entry.getValue());
            if (matches.size() == 1) {
                return priority >= minimumFallbackPriority ? matches.get(0) : null;
            }

            int fallbackPriority = fallbackPriority(priority);
            if (fallbackPriority == Integer.MIN_VALUE) {
                return null;
            }
            minimumFallbackPriority = Math.max(minimumFallbackPriority, fallbackPriority);
        }
        return null;
    }

    // 判断 stack 是否满足某个签名的最小匹配条件
    public static boolean matches(ItemStack stack, LootResultSignature signature) {
        if (stack.isEmpty()) {
            return false;
        }

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!signature.itemId().equals(itemId)) {
            return false;
        }

        return switch (signature.type()) {
            case COMPONENT_EXACT -> matchesExactComponents(stack, signature);
            case ENCHANTED_RANDOM, ENCHANTED_LEVEL, ENCHANTED_APPROX -> stack.has(DataComponents.ENCHANTMENTS)
                    || stack.has(DataComponents.STORED_ENCHANTMENTS)
                    || stack.isEnchanted();
            case PLAIN, APPROX_ITEM_ONLY -> true;
        };
    }

    private static boolean matchesExactComponents(ItemStack stack, LootResultSignature signature) {
        ItemStack preview = signature.createPreviewStack();
        return !preview.isEmpty() && ItemStack.isSameItemSameComponents(stack, preview);
    }

    private static List<LootResultSignature> distinctSignatures(List<LootResultSignature> matches) {
        return new ArrayList<>(new LinkedHashSet<>(matches));
    }

    private static int fallbackPriority(int priority) {
        return Integer.MIN_VALUE;
    }

    private static int priority(LootResultSignature signature) {
        return switch (signature.type()) {
            case COMPONENT_EXACT -> 50;
            case ENCHANTED_RANDOM, ENCHANTED_LEVEL, ENCHANTED_APPROX -> 30;
            case PLAIN -> 20;
            case APPROX_ITEM_ONLY -> 10;
        };
    }
}
