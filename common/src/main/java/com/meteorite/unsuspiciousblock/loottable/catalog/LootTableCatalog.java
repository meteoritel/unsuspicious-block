package com.meteorite.unsuspiciousblock.loottable.catalog;

import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 战利品表目录数据记录——TableDefinition 与 ItemDefinition 为跨模块共享的基础类型，
 * 供 journal、network、client、command 等包统一引用。
 */
public final class LootTableCatalog {

    private LootTableCatalog() {
    }

    /** 战利品表定义 */
    public record TableDefinition(ResourceLocation id, Component displayName, String type,
                                  List<ItemDefinition> items, int simulationCount) {
    }

    /** 战利品表物品条目定义 */
    public record ItemDefinition(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                                 String probability, LootResultSignature signature,
                                 @Nullable ResourceLocation sourceChildTable,
                                 List<LootConditionInfo> conditions,
                                 boolean injected) {
        // 兼容旧调用方的便利构造器：sourceChildTable 默认 null，conditions 默认空，injected 默认 false
        public ItemDefinition(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                              String probability, LootResultSignature signature) {
            this(id, displayName, tooltipHint, probability, signature, null, List.of(), false);
        }

        public ItemDefinition(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                              String probability, LootResultSignature signature,
                              @Nullable ResourceLocation sourceChildTable) {
            this(id, displayName, tooltipHint, probability, signature, sourceChildTable, List.of(), false);
        }

        public ItemDefinition(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                              String probability, LootResultSignature signature,
                              @Nullable ResourceLocation sourceChildTable,
                              List<LootConditionInfo> conditions) {
            this(id, displayName, tooltipHint, probability, signature, sourceChildTable, conditions, false);
        }

        /**
         * 判断该条目是否附带条件（影响模拟结果置信度）。
         * 条件包括：有静态条件分析结果、或 tooltipHint 为近似提示。
         */
        public boolean hasConditions() {
            if (!this.conditions.isEmpty()) {
                return true;
            }
            return this.tooltipHint != null
                    && this.tooltipHint.getString().equals(
                    Component.translatable("screen.unsuspiciousblock.archaeology_journal.item_hint.approximate").getString());
        }
    }

    // ==================== 工具方法 ====================

    private static final String ENCHANTED_HINT_KEY = "screen.unsuspiciousblock.archaeology_journal.item_hint.enchanted";
    private static final String APPROXIMATE_HINT_KEY = "screen.unsuspiciousblock.archaeology_journal.item_hint.approximate";

    /**
     * 为模拟期发现的"注入条目"（GLM / LootTableEvents.MODIFY 注入，JSON 中不存在）构建 ItemDefinition。
     */
    public static ItemDefinition buildDiscoveredDefinition(LootResultSignature signature, String probability,
                                                         boolean injected) {
        ResourceLocation itemId = signature.itemId();
        Component displayName = resolveMergedDisplayName(itemId, signature);
        Component tooltipHint = resolveMergedTooltipHint(signature);
        return new ItemDefinition(itemId, displayName, tooltipHint, probability, signature, null, List.of(), injected);
    }

    /**
     * 根据签名解析合并后的展示名（取预览栈的 hoverName）。
     */
    public static Component resolveMergedDisplayName(ResourceLocation itemId, LootResultSignature signature) {
        ItemStack previewStack = signature.createPreviewStack();
        if (previewStack.isEmpty()) {
            previewStack = new ItemStack(BuiltInRegistries.ITEM.get(itemId));
        }
        return previewStack.isEmpty()
                ? Component.translatable("screen.unsuspiciousblock.archaeology_journal.unknown_entry")
                : previewStack.getHoverName().copy();
    }

    /**
     * 根据签名解析 tooltip 提示文本。
     */
    @Nullable
    public static Component resolveMergedTooltipHint(LootResultSignature signature) {
        if (signature.isEnchantedVariant()) {
            return Component.translatable(ENCHANTED_HINT_KEY);
        }
        if (signature.type() == LootResultSignature.SignatureType.APPROX_ITEM_ONLY) {
            return Component.translatable(APPROXIMATE_HINT_KEY);
        }
        return null;
    }
}