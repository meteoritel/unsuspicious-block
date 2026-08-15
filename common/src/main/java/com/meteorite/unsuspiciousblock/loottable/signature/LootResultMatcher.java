package com.meteorite.unsuspiciousblock.loottable.signature;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

/**
 * 根据运行时最终产物在候选签名中选择最合适的条目。
 */
public final class LootResultMatcher {
    private LootResultMatcher() {
    }

    // 在候选签名中解析当前 stack 应命中的条目
    // 同优先级出现多个候选时，宁可保守返回 null，也不要把掉落错误归到具体条目上。
    @Nullable
    public static LootResultSignature resolve(ItemStack stack, List<LootResultSignature> candidates) {
        return resolve(stack, candidates, LootResultSignature::createPreviewStack);
    }

    // 带预览栈提供者的解析入口：COMPONENT_EXACT 匹配需构建预览栈（base64 + JSON 解码），
    // 高频调用方（如概率模拟）可传入缓存函数避免每次匹配重复解析
    @Nullable
    public static LootResultSignature resolve(ItemStack stack, List<LootResultSignature> candidates,
                                               Function<LootResultSignature, ItemStack> previewStackProvider) {
        if (stack.isEmpty()) {
            return null;
        }

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        LootResultSignature best = null;
        int bestPriority = Integer.MIN_VALUE;
        boolean ambiguous = false;
        for (int index = 0; index < candidates.size(); index++) {
            LootResultSignature candidate = candidates.get(index);
            if (!matches(stack, itemId, candidate, previewStackProvider)) {
                continue;
            }

            int candidatePriority = priority(candidate);
            if (candidatePriority > bestPriority) {
                best = candidate;
                bestPriority = candidatePriority;
                ambiguous = false;
            } else if (candidatePriority == bestPriority && !candidate.equals(best)) {
                ambiguous = true;
            }
        }
        // 取最高优先级候选；同优先级出现不同签名时视为歧义。
        return best == null || ambiguous ? null : best;
    }

    // 判断 stack 是否满足某个签名的最小匹配条件
    public static boolean matches(ItemStack stack, LootResultSignature signature) {
        return matches(stack, signature, LootResultSignature::createPreviewStack);
    }

    private static boolean matches(ItemStack stack, LootResultSignature signature,
                                   Function<LootResultSignature, ItemStack> previewStackProvider) {
        if (stack.isEmpty()) {
            return false;
        }

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return matches(stack, itemId, signature, previewStackProvider);
    }

    private static boolean matches(ItemStack stack, ResourceLocation itemId,
                                   LootResultSignature signature,
                                   Function<LootResultSignature, ItemStack> previewStackProvider) {
        if (!signature.itemId().equals(itemId)) {
            return false;
        }

        return switch (signature.type()) {
            case COMPONENT_EXACT -> matchesExactComponents(stack, signature, previewStackProvider);
            case ENCHANTED_RANDOM, ENCHANTED_LEVEL, ENCHANTED_APPROX -> LootResultSignature.isActuallyEnchanted(stack);
            case PLAIN, APPROX_ITEM_ONLY -> true;
        };
    }

    private static boolean matchesExactComponents(ItemStack stack, LootResultSignature signature,
                                                  Function<LootResultSignature, ItemStack> previewStackProvider) {
        ItemStack preview = previewStackProvider.apply(signature);
        return !preview.isEmpty() && ItemStack.isSameItemSameComponents(stack, preview);
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
