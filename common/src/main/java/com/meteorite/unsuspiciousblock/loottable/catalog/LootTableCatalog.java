package com.meteorite.unsuspiciousblock.loottable.catalog;

import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandler;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.analysis.LuckGate;
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
                                  List<ResourceLocation> childTables,
                                  List<ChildTableProbability> childTableProbabilities) {
        public TableDefinition {
            items = List.copyOf(items);
            childTables = List.copyOf(childTables);
            childTableProbabilities = List.copyOf(childTableProbabilities);
        }


        public TableDefinition(ResourceLocation id, Component displayName, String type,
                               List<ItemDefinition> items, int simulationCount,
                               List<ResourceLocation> childTables) {
            this(id, displayName, type, items, simulationCount, childTables,
                    childTables.stream().map(ChildTableProbability::pending).toList());
        }

        public TableDefinition withChildTables(List<ResourceLocation> children) {
            return new TableDefinition(this.id, this.displayName, this.type, this.items,
                    this.simulationCount, children,
                    children.stream().map(ChildTableProbability::pending).toList());
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
     * inheritedConditions 来自 pool、组合 entry 或战利品表引用；functionUncertainty 单独描述函数求值的不确定性。
     * <p>
     * luckGate 是这条路径的**逐路径最小幸运门槛**（由 {@code LuckGateAnalysis} 在投影期从条目与池的
     * weight/quality/rolls/bonus_rolls 推出）。它按路径分别保留而不是取多路径的最小值：合并后的数字
     * 无法指回是哪条路径需要它（决策 34）。为 {@code null} 表示该路径与幸运无关。
     */
    public record LootAcquisitionPath(@Nullable ResourceLocation sourceChildTable,
                                      @Nullable ResourceLocation sourceItemTag,
                                      List<LootConditionInfo> entryConditions,
                                      List<LootConditionInfo> inheritedConditions,
                                      LootConditionHandler.UncertaintyLevel functionUncertainty,
                                      boolean luckAffected,
                                      @Nullable LuckGate luckGate) {
        public LootAcquisitionPath {
            entryConditions = List.copyOf(entryConditions);
            inheritedConditions = List.copyOf(inheritedConditions);
        }

        /** 未经投影器分析的路径保守标记；仅在条目签名近似时使用该兜底。 */
        public LootAcquisitionPath(@Nullable ResourceLocation sourceChildTable,
                                   @Nullable ResourceLocation sourceItemTag,
                                   List<LootConditionInfo> entryConditions,
                                   List<LootConditionInfo> inheritedConditions,
                                   LootConditionHandler.UncertaintyLevel functionUncertainty,
                                   boolean luckAffected) {
            this(sourceChildTable, sourceItemTag, entryConditions, inheritedConditions,
                    functionUncertainty, luckAffected, null);
        }

        public LootAcquisitionPath(@Nullable ResourceLocation sourceChildTable,
                                   @Nullable ResourceLocation sourceItemTag,
                                   List<LootConditionInfo> entryConditions,
                                   List<LootConditionInfo> inheritedConditions) {
            this(sourceChildTable, sourceItemTag, entryConditions, inheritedConditions,
                    LootConditionHandler.UncertaintyLevel.RUNTIME, false);
        }

        public LootAcquisitionPath(@Nullable ResourceLocation sourceChildTable,
                                   List<LootConditionInfo> entryConditions,
                                   List<LootConditionInfo> inheritedConditions) {
            this(sourceChildTable, null, entryConditions, inheritedConditions);
        }

        public boolean hasConditions() {
            return !this.entryConditions.isEmpty() || !this.inheritedConditions.isEmpty();
        }

        /** 该路径是否被静态证明在任何可表示的幸运下都拿不到。 */
        public boolean luckImpossible() {
            return this.luckGate != null && this.luckGate.impossible();
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

    /** 单个自洽模拟场景下，物品在一次抽取中至少出现一次的概率。 */
    public record ScenarioProbability(String scenarioKey, Probability probability,
                                      List<LootConditionInfo> conditions) {
        public ScenarioProbability {
            conditions = List.copyOf(conditions);
        }
    }

    /**
     * 父表一次抽取中，直接引用的子表至少产出一个物品的概率。
     *
     * @param conditions 该入口的**条件树**：通往它的各条路径共同成立的静态条件，加上注入边门槛
     *                   （后者不在任何 JSON 里，只能由服务端给出）。客户端只渲染这一份，
     *                   不在本地重新推导语义
     */
    public record ChildTableProbability(ResourceLocation tableId, Probability probability,
                                        List<ScenarioProbability> scenarioProbabilities,
                                        List<LootConditionInfo> conditions) {
        public ChildTableProbability {
            scenarioProbabilities = List.copyOf(scenarioProbabilities);
            conditions = List.copyOf(conditions);
        }

        public static ChildTableProbability pending(ResourceLocation tableId) {
            return new ChildTableProbability(tableId,
                    Probability.unknown(UnknownReason.UNCOVERED), List.of(), List.of());
        }
    }

    /** 战利品表物品条目定义 */
    public record ItemDefinition(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                                 Probability probability, LootResultSignature signature,
                                 List<LootAcquisitionPath> acquisitionPaths,
                                 boolean injected,
                                 List<ScenarioProbability> scenarioProbabilities) {
        public ItemDefinition {
            acquisitionPaths = List.copyOf(acquisitionPaths);
            scenarioProbabilities = List.copyOf(scenarioProbabilities);
        }

        public ItemDefinition(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                              Probability probability, LootResultSignature signature,
                              List<LootAcquisitionPath> acquisitionPaths, boolean injected) {
            this(id, displayName, tooltipHint, probability, signature,
                    acquisitionPaths, injected, List.of());
        }





        public ItemDefinition(ResourceLocation id, Component displayName, @Nullable Component tooltipHint,
                              Probability probability, LootResultSignature signature,
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
         * 判断该条目是否附带条件（含近似签名兜底）。
         * <p>
         * 注意它**不再**决定零命中的显示口径：抽样零命中一律是 {@code Measured(0.0)}（展示为
         * 「未命中」），因为条目在当前输入下已被静态判定可达，剩下的零出现就是真实的抽样事实
         * （决策 40）。展示状态本身由服务端按"可适用性 × 计算状态"两轴派生，见
         * {@code Probability} 与 {@code PathHintAnalyzer}。
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

        /**
         * 该条目的不确定性等级——与 {@link #hasConditions()} **同源同数据**（全部获取路径的条件树 +
         * 近似签名及各路径的函数分级），供 UI 着色与文本使用。近似签名不再直接等同于运行时条件。
         * <p>
         * 两者共用一份判定是刻意的：过去 UI 在客户端另起一套规则（只看直接路径、并用 tooltip 文案
         * 比较来识别近似条目），会出现"颜色说不确定、概率却从不显示 {@code ?}"或反过来的错配。
         * 关系是单向蕴含——等级非 {@code NONE} 必然说明条目带条件或签名近似（即可显示 {@code ?}），
         * 但只有可静态求值的条件（如 {@code match_tool}）时等级为 {@code NONE} 而概率仍可能为 {@code ?}，
         * 此时 UI 按"未知"着色而不是按等级着色。
         */
        public LootConditionHandler.UncertaintyLevel uncertaintyLevel() {
            if (this.signature.type() == LootResultSignature.SignatureType.APPROX_ITEM_ONLY
                    && this.acquisitionPaths.isEmpty()) {
                return LootConditionHandler.UncertaintyLevel.RUNTIME;
            }
            LootConditionHandler.UncertaintyLevel level = LootConditionHandler.UncertaintyLevel.NONE;
            for (LootAcquisitionPath path : this.acquisitionPaths) {
                LootConditionHandler.UncertaintyLevel candidate =
                        LootConditionHandlers.computeUncertaintyLevel(path.allConditions(), false);
                if (candidate.ordinal() > level.ordinal()) {
                    level = candidate;
                }
                if (this.signature.type() == LootResultSignature.SignatureType.APPROX_ITEM_ONLY
                        && path.functionUncertainty().ordinal() > level.ordinal()) {
                    level = path.functionUncertainty();
                }
            }
            return level;
        }
    }

    // ==================== 工具方法 ====================


    private static final String ENCHANTED_HINT_KEY = "screen.unsuspiciousblock.archaeology_journal.item_hint.enchanted";
    private static final String APPROXIMATE_HINT_KEY = "screen.unsuspiciousblock.archaeology_journal.item_hint.approximate";

    /** 为带多场景概率的运行时注入条目构建目录定义。 */
    public static ItemDefinition buildDiscoveredDefinition(LootResultSignature signature, Probability probability,
                                                            boolean injected,
                                                            List<ScenarioProbability> scenarioProbabilities) {
        return buildDiscoveredDefinition(signature, probability, injected, scenarioProbabilities,
                signature.createPreviewStack());
    }

    // 已有预览的调用方可复用解码结果；传入独占副本，避免物品名称扩展逻辑修改共享匹配预览。
    public static ItemDefinition buildDiscoveredDefinition(LootResultSignature signature, Probability probability,
                                                            boolean injected,
                                                            List<ScenarioProbability> scenarioProbabilities,
                                                            ItemStack previewStack) {
        ResourceLocation itemId = signature.itemId();
        Component displayName = resolveMergedDisplayName(itemId, previewStack);
        Component tooltipHint = resolveMergedTooltipHint(signature);
        return new ItemDefinition(itemId, displayName, tooltipHint, probability, signature,
                List.of(), injected, scenarioProbabilities);
    }

    // 根据签名解析合并后的展示名（取预览栈的 hoverName）
    public static Component resolveMergedDisplayName(ResourceLocation itemId, LootResultSignature signature) {
        return resolveMergedDisplayName(itemId, signature.createPreviewStack());
    }

    // 使用同一空栈回退与名称复制规则，供新建预览和已有预览共用。
    private static Component resolveMergedDisplayName(ResourceLocation itemId, ItemStack previewStack) {
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
