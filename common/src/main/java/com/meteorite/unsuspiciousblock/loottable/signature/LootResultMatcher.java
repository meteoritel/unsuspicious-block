package com.meteorite.unsuspiciousblock.loottable.signature;

import com.meteorite.unsuspiciousblock.loottable.diagnostics.LootSimulationMetrics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // 带预览栈提供者的解析入口：COMPONENT_EXACT 匹配需构建预览栈（JSON 解码），
    // 高频调用方（如概率模拟）可传入缓存函数避免每次匹配重复解析
    @Nullable
    public static LootResultSignature resolve(ItemStack stack, List<LootResultSignature> candidates,
                                               Function<LootResultSignature, ItemStack> previewStackProvider) {
        return resolveGroups(stack, candidates, List.of(), previewStackProvider);
    }

    // 索引仅缩小精确候选范围；哈希命中后仍调用原有完整组件比较，不以哈希相等判断匹配。
    @Nullable
    public static LootResultSignature resolve(ItemStack stack, CandidateIndex candidates,
                                               Function<LootResultSignature, ItemStack> previewStackProvider) {
        List<LootResultSignature> exact = stack.isEmpty() || candidates.exactByHash.isEmpty()
                ? List.of()
                : candidates.exactByHash.getOrDefault(ItemStack.hashItemAndComponents(stack), List.of());
        return resolveGroups(stack, candidates.fallback, exact, previewStackProvider);
    }

    // 两个候选组共用最高优先级与歧义状态，避免精确候选有歧义时错误回退到普通签名。
    @Nullable
    private static LootResultSignature resolveGroups(ItemStack stack, List<LootResultSignature> first,
                                                      List<LootResultSignature> second,
                                                      Function<LootResultSignature, ItemStack> previewStackProvider) {
        if (stack.isEmpty()) {
            return null;
        }

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        LootResultSignature best = null;
        int bestPriority = Integer.MIN_VALUE;
        boolean ambiguous = false;
        for (int group = 0; group < 2; group++) {
            List<LootResultSignature> candidates = group == 0 ? first : second;
            for (LootResultSignature candidate : candidates) {
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
        }
        // 本循环无提前返回，累计实际访问的候选数，包含未匹配的候选。
        LootSimulationMetrics metrics = LootSimulationMetrics.current();
        if (metrics != null) {
            metrics.add(LootSimulationMetrics.Count.SCANS, first.size() + second.size());
        }
        // 取最高优先级候选；同优先级出现不同签名时视为歧义。
        return best == null || ambiguous ? null : best;
    }

    // 判断 stack 是否满足某个签名的最小匹配条件
    public static boolean matches(ItemStack stack, LootResultSignature signature) {
        return matches(stack, signature, LootResultSignature::createPreviewStack);
    }

    // 带预览栈提供者的匹配入口；高频调用方应传入缓存函数（见 LootResultPreviewCache）
    public static boolean matches(ItemStack stack, LootResultSignature signature,
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

    /** 单物品、单场景的增量候选索引；只保存原签名，不生成或修改持久化键。 */
    public static final class CandidateIndex {
        private final Map<Integer, List<LootResultSignature>> exactByHash = new HashMap<>();
        private final List<LootResultSignature> fallback = new ArrayList<>();

        // 精确索引必须基于原匹配器实际使用的预览组件，保留 codec 解码/校验后的语义。
        // 同哈希候选全部保留，查询时逐个比较；提供者返回的缓存预览在本索引存活期内不得修改。
        public void add(LootResultSignature signature,
                        Function<LootResultSignature, ItemStack> previewStackProvider) {
            if (signature.type() == LootResultSignature.SignatureType.COMPONENT_EXACT) {
                ItemStack preview = previewStackProvider.apply(signature);
                if (!preview.isEmpty()) {
                    this.exactByHash.computeIfAbsent(ItemStack.hashItemAndComponents(preview),
                            ignored -> new ArrayList<>()).add(signature);
                }
            } else {
                this.fallback.add(signature);
            }
        }
    }
}
