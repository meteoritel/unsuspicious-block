package com.meteorite.unsuspiciousblock.loottable.catalog;

import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 战利品表目录数据记录——TableDefinition 与 ItemDefinition 为跨模块共享的基础类型，
 * 供 journal、network、client、command 等包统一引用。
 */
public final class LootTableCatalog {

    private LootTableCatalog() {
    }

    /** 战利品表定义 */
    public record TableDefinition(ResourceLocation id, Component displayName, String type,
                                  List<ItemDefinition> items, int simulationCount,
                                  List<ResourceLocation> childTables) {
        public TableDefinition {
            items = List.copyOf(items);
            childTables = List.copyOf(childTables);
        }

        public TableDefinition(ResourceLocation id, Component displayName, String type,
                               List<ItemDefinition> items, int simulationCount) {
            this(id, displayName, type, items, simulationCount, List.of());
        }

        public TableDefinition withChildTables(List<ResourceLocation> children) {
            return new TableDefinition(this.id, this.displayName, this.type, this.items,
                    this.simulationCount, children);
        }
    }

    /** 目录分类定义——由服务端 Data Pack 加载并同步给客户端。 */
    public record CatalogCategoryDefinition(ResourceLocation id, String translationKey, String fallbackName,
                                            String descriptionKey, String descriptionFallback,
                                            ResourceLocation iconItem, int order) {
    }

    /** 目录层级与分类元数据。 */
    public record CatalogStructure(List<CatalogCategoryDefinition> categories,
                                   Map<ResourceLocation, ResourceLocation> rootCategories) {
        public CatalogStructure {
            categories = List.copyOf(categories);
            rootCategories = Map.copyOf(rootCategories);
        }

        public static CatalogStructure empty() {
            return new CatalogStructure(List.of(), Map.of());
        }
    }

    /**
     * 单个物品结果的获取路径。
     * sourceItemTag 标识该路径是否由物品 tag 展开，entryConditions 属于物品条目本身，
     * inheritedConditions 来自 pool、组合 entry 或战利品表引用。
     */
    public record LootAcquisitionPath(@Nullable ResourceLocation sourceChildTable,
                                      @Nullable ResourceLocation sourceItemTag,
                                      List<LootConditionInfo> entryConditions,
                                      List<LootConditionInfo> inheritedConditions) {
        public LootAcquisitionPath {
            entryConditions = List.copyOf(entryConditions);
            inheritedConditions = List.copyOf(inheritedConditions);
        }

        public LootAcquisitionPath(@Nullable ResourceLocation sourceChildTable,
                                   List<LootConditionInfo> entryConditions,
                                   List<LootConditionInfo> inheritedConditions) {
            this(sourceChildTable, null, entryConditions, inheritedConditions);
        }

        public boolean hasConditions() {
            return !this.entryConditions.isEmpty() || !this.inheritedConditions.isEmpty();
        }

        public List<LootConditionInfo> allConditions() {
            if (this.entryConditions.isEmpty()) {
                return this.inheritedConditions;
            }
            if (this.inheritedConditions.isEmpty()) {
                return this.entryConditions;
            }
            List<LootConditionInfo> result = new ArrayList<>(
                    this.entryConditions.size() + this.inheritedConditions.size());
            result.addAll(this.inheritedConditions);
            result.addAll(this.entryConditions);
            return List.copyOf(result);
        }
    }

    /** 战利品表物品条目定义 */
    public record ItemDefinition(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                                 String probability, LootResultSignature signature,
                                 List<LootAcquisitionPath> acquisitionPaths,
                                 boolean injected) {
        public ItemDefinition {
            acquisitionPaths = List.copyOf(acquisitionPaths);
        }

        public ItemDefinition(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                               String probability, LootResultSignature signature) {
            this(id, displayName, tooltipHint, probability, signature,
                    List.of(new LootAcquisitionPath(null, List.of(), List.of())), false);
        }

        public ItemDefinition(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                               String probability, LootResultSignature signature,
                               @Nullable ResourceLocation sourceChildTable) {
            this(id, displayName, tooltipHint, probability, signature,
                    List.of(new LootAcquisitionPath(sourceChildTable, List.of(), List.of())), false);
        }

        public ItemDefinition(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                              String probability, LootResultSignature signature,
                               @Nullable ResourceLocation sourceChildTable,
                               List<LootConditionInfo> conditions) {
            this(id, displayName, tooltipHint, probability, signature,
                    List.of(new LootAcquisitionPath(sourceChildTable, conditions, List.of())), false);
        }

        public ItemDefinition(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                              String probability, LootResultSignature signature,
                               @Nullable ResourceLocation sourceChildTable,
                               List<LootConditionInfo> conditions,
                               boolean injected) {
            this(id, displayName, tooltipHint, probability, signature,
                    List.of(new LootAcquisitionPath(sourceChildTable, conditions, List.of())), injected);
        }

        public ItemDefinition(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                              String probability, LootResultSignature signature,
                              @Nullable ResourceLocation sourceChildTable,
                              List<LootConditionInfo> conditions,
                              List<LootConditionInfo> inheritedConditions,
                              boolean injected) {
            this(id, displayName, tooltipHint, probability, signature,
                    List.of(new LootAcquisitionPath(sourceChildTable, conditions, inheritedConditions)), injected);
        }

        @Nullable
        public ResourceLocation sourceChildTable() {
            if (this.acquisitionPaths.isEmpty()) {
                return null;
            }
            ResourceLocation source = this.acquisitionPaths.getFirst().sourceChildTable();
            for (LootAcquisitionPath path : this.acquisitionPaths) {
                if (!java.util.Objects.equals(source, path.sourceChildTable())) {
                    return null;
                }
            }
            return source;
        }

        public List<LootConditionInfo> conditions() {
            return this.acquisitionPaths.stream()
                    .flatMap(path -> path.entryConditions().stream())
                    .distinct()
                    .toList();
        }

        public List<LootConditionInfo> parentTableConditions() {
            return this.acquisitionPaths.stream()
                    .flatMap(path -> path.inheritedConditions().stream())
                    .distinct()
                    .toList();
        }

        /**
         * 判断该条目是否附带条件（影响模拟结果置信度，零出现时显示 {@code "?"} 而非 {@code "<0.01%"}）。
         * 条件包括：任一获取路径带静态条件分析结果，或签名为近似回退
         * （解析期函数无法静态求值 / 组件编码失败等，模拟覆盖度不可保证）。
         * 不依赖 tooltipHint 文本比较，避免服务端/客户端语言差异导致行为不一致。
         */
        public boolean hasConditions() {
            for (LootAcquisitionPath path : this.acquisitionPaths) {
                if (path.hasConditions()) {
                    return true;
                }
            }
            return this.signature.type() == LootResultSignature.SignatureType.APPROX_ITEM_ONLY;
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
        return new ItemDefinition(itemId, displayName, tooltipHint, probability, signature, List.of(), injected);
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
