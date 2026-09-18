package com.meteorite.unsuspiciousblock.loottable.catalog;

import com.google.gson.JsonElement;
import com.meteorite.unsuspiciousblock.loottable.analysis.CompiledLootTable;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionInfo;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionHandler;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootFunctionHandlers;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootParseUtil;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.graph.LootTableReferenceGraph;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctions;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 战利品表投影器——把引用图与 {@link CompiledLootTable} 链接成 {@link StaticTableProjection}。
 * <p>
 * 链接期必须复现三条既有语义（见规划 §3.8 不变量）：
 * <ol>
 *   <li><b>首跳子表归属</b>：{@code sourceChildTable} 只在第一层引用位置设置，
 *       孙表条件因此汇总回直接子表，父表页签的归属不会错位；</li>
 *   <li><b>条件继承顺序</b>：继承条件链自外向内追加为
 *       {@code 上层传入 ++ 本表内已继承 ++ 本事件自身条件}，{@code allConditions()} 的展示顺序依赖它；</li>
 *   <li><b>函数继承与降级</b>：函数链为 {@code 本事件自身 ++ 本表内已继承 ++ 上层传入}，
 *       无法静态求值的函数按既有规则降级为 {@code APPROX_ITEM_ONLY} 近似签名。</li>
 * </ol>
 * 引用位置按编译产物的出现顺序逐个进入，不去重也不合并——同一子表在两处被引用且条件不同时，
 * 两条获取路径都要保留。运行时注入边不参与静态链接，因此本类不消费它。
 */
public final class LootTableProjector {
    private static final String ENCHANTED_HINT_KEY = "screen.unsuspiciousblock.archaeology_journal.item_hint.enchanted";
    private static final String APPROXIMATE_HINT_KEY = "screen.unsuspiciousblock.archaeology_journal.item_hint.approximate";

    private static final Logger LOGGER = LogUtils.getLogger();

    private final LootTableReferenceGraph referenceGraph;
    private final Map<ResourceLocation, CompiledLootTable> compiledTables;
    private final Set<ResourceLocation> excludedReferences;
    private final DynamicOps<JsonElement> lootOps;

    /** 单表展平结果缓存；同一轮重载内多次查询只算一次。 */
    private final Map<ResourceLocation, List<ItemDefinition>> itemsCache = new HashMap<>();
    private final Map<ResourceLocation, StaticTableProjection> projectionCache = new HashMap<>();

    /**
     * @param referenceGraph     引用关系权威（子表集合与拓扑）
     * @param compiledTables     编译产物
     * @param excludedReferences 需排除出收录闭包的环上表
     */
    public LootTableProjector(LootTableReferenceGraph referenceGraph,
                              Map<ResourceLocation, CompiledLootTable> compiledTables,
                              Set<ResourceLocation> excludedReferences,
                              HolderLookup.Provider registries) {
        this.referenceGraph = referenceGraph;
        this.compiledTables = Map.copyOf(compiledTables);
        this.excludedReferences = Set.copyOf(excludedReferences);
        this.lootOps = RegistryOps.create(JsonOps.INSTANCE, registries);
    }

    /** 取该表的静态投影；表不在编译产物中时返回 {@code null}。 */
    @Nullable
    public StaticTableProjection project(ResourceLocation tableId) {
        CompiledLootTable compiled = this.compiledTables.get(tableId);
        if (compiled == null) {
            return null;
        }
        StaticTableProjection cached = this.projectionCache.get(tableId);
        if (cached != null) {
            return cached;
        }
        List<ItemDefinition> items = itemsOf(tableId);
        // 子表入口只收录真正产出物品的表：图是纯拓扑，无物品的表不进目录也不作为入口
        List<ResourceLocation> childTables = this.referenceGraph.directChildren(tableId).stream()
                .filter(child -> !itemsOf(child).isEmpty())
                .toList();
        StaticTableProjection projection =
                new StaticTableProjection(tableId, compiled.declaredType(), items, childTables);
        this.projectionCache.put(tableId, projection);
        return projection;
    }

    // 以该表为根展平出物品条目；结果只取决于该表自身，可安全缓存。
    // 未被编译的表（资源缺失、或被环排除集剔出闭包）返回空结果——子表入口据此把它们过滤掉。
    private List<ItemDefinition> itemsOf(ResourceLocation rootId) {
        List<ItemDefinition> cached = this.itemsCache.get(rootId);
        if (cached != null) {
            return cached;
        }

        List<ItemDefinition> result = List.of();
        if (this.compiledTables.containsKey(rootId)) {
            LinkedHashMap<String, ItemDefinitionAccumulator> items = new LinkedHashMap<>();
            expand(this.compiledTables.get(rootId), List.of(), List.of(), null, new LinkedHashSet<>(), items);

            List<ItemDefinition> definitions = new ArrayList<>(items.size());
            for (ItemDefinitionAccumulator accumulator : items.values()) {
                definitions.add(accumulator.build());
            }
            definitions.sort(Comparator
                    .comparing((ItemDefinition definition) -> definition.id().toString())
                    .thenComparing(definition -> definition.signature().toStoredKey()));
            result = List.copyOf(definitions);
        }
        this.itemsCache.put(rootId, result);
        return result;
    }

    // 按编译产物的事件顺序展开：物品条目就地解析，引用位置递归进入子表
    private void expand(CompiledLootTable table, List<LootConditionInfo> incomingConditions,
                        List<JsonElement> incomingFunctions, @Nullable ResourceLocation sourceChildTable,
                        LinkedHashSet<ResourceLocation> expandingStack,
                        Map<String, ItemDefinitionAccumulator> items) {
        for (CompiledLootTable.Event event : table.events()) {
            switch (event) {
                case CompiledLootTable.ItemPath itemPath ->
                        resolveItem(itemPath, incomingConditions, incomingFunctions, sourceChildTable, items);
                case CompiledLootTable.ReferenceSite site ->
                        expandReferenceSite(site, incomingConditions, incomingFunctions,
                                sourceChildTable, expandingStack, items);
            }
        }
    }

    // 引用位置：排除环成员、防重复进入、目标缺失即告警跳过，其余递归进入子表
    private void expandReferenceSite(CompiledLootTable.ReferenceSite site,
                                     List<LootConditionInfo> incomingConditions,
                                     List<JsonElement> incomingFunctions,
                                     @Nullable ResourceLocation sourceChildTable,
                                     LinkedHashSet<ResourceLocation> expandingStack,
                                     Map<String, ItemDefinitionAccumulator> items) {
        ResourceLocation target = site.target();
        if (this.excludedReferences.contains(target)) {
            return;
        }
        if (expandingStack.contains(target)) {
            LOGGER.warn("检测到 loot_table 循环引用 {}，跳过展开", target);
            return;
        }
        if (!this.compiledTables.containsKey(target)) {
            LOGGER.warn("展开 loot_table 引用 {} 失败：有效资源缺失或 JSON 无法解析", target);
            return;
        }

        LinkedHashSet<ResourceLocation> nextStack = new LinkedHashSet<>(expandingStack);
        nextStack.add(target);
        expand(this.compiledTables.get(target),
                LootParseUtil.appendConditions(
                        LootParseUtil.appendConditions(incomingConditions, site.inheritedConditions()),
                        site.siteConditions()),
                concatFunctions(site.siteFunctions(), site.inheritedFunctions(), incomingFunctions),
                sourceChildTable != null ? sourceChildTable : target,
                nextStack, items);
    }

    // ==================== 单条物品路径的解析 ====================

    // 函数链拼接顺序与条件继承顺序是签名兼容的组成部分，本方法的求值次序需与解析路径逐条一致
    private void resolveItem(CompiledLootTable.ItemPath path, List<LootConditionInfo> incomingConditions,
                             List<JsonElement> incomingFunctions, @Nullable ResourceLocation sourceChildTable,
                             Map<String, ItemDefinitionAccumulator> items) {
        List<LootConditionInfo> entryConditions = path.entryConditions();
        List<JsonElement> functions = concatFunctions(path.entryFunctions(), path.inheritedFunctions(),
                incomingFunctions);

        ItemStack previewStack = new ItemStack(BuiltInRegistries.ITEM.get(path.itemId()));
        LootResultSignature signature = LootResultSignature.plain(path.itemId());
        boolean entryHasConditions = !entryConditions.isEmpty();
        List<LootConditionInfo> resolvedConditions = new ArrayList<>(entryConditions);
        List<Component> functionHints = new ArrayList<>();

        for (JsonElement functionElement : functions) {
            if (!functionElement.isJsonObject()) {
                entryHasConditions = true;
                continue;
            }

            List<LootConditionInfo> functionConditions =
                    LootParseUtil.parseConditions(functionElement.getAsJsonObject(), this.lootOps);
            if (!functionConditions.isEmpty()) {
                entryHasConditions = true;
                resolvedConditions.addAll(functionConditions);
            }

            // 使用原版 Codec 解析 function
            var decodeResult = LootItemFunctions.TYPED_CODEC.parse(this.lootOps, functionElement);
            LootItemFunction function = decodeResult.result().orElse(null);
            if (function == null) {
                entryHasConditions = true;
                functionHints.add(unknownFunctionHint(LootParseUtil.extractTypeId(functionElement, "function")));
                LOGGER.warn("解析战利品函数失败: function={}, error={}",
                        LootParseUtil.extractTypeId(functionElement, "function"),
                        decodeResult.error().map(Object::toString).orElse("unknown"));
                continue;
            }

            ResourceLocation functionId = BuiltInRegistries.LOOT_FUNCTION_TYPE.getKey(function.getType());
            LootFunctionHandler handler = LootFunctionHandlers.get(functionId);

            if (handler == null) {
                // 未知 function：标记为条件 + 近似签名
                entryHasConditions = true;
                functionHints.add(unknownFunctionHint(functionId));
                if (signature.type() == LootResultSignature.SignatureType.PLAIN) {
                    String functionName = functionId != null ? functionId.toString() : "unknown";
                    signature = LootResultSignature.approximateItemOnly(
                            currentItemId(previewStack), "unknown_function:" + functionName);
                }
                continue;
            }

            try {
                if (!functionConditions.isEmpty()) {
                    Component hint = handler.describeHint(function, functionElement.getAsJsonObject());
                    if (hint != null) {
                        functionHints.add(hint);
                    }
                    continue;
                }
                ItemStack candidateStack = previewStack.copy();
                ItemStack result = handler.apply(candidateStack, function);
                if (result != null) {
                    previewStack = result;
                } else {
                    entryHasConditions = true;
                    Component hint = handler.describeHint(function, functionElement.getAsJsonObject());
                    if (hint != null) {
                        functionHints.add(hint);
                    }
                }
                LootResultSignature derived = handler.deriveSignature(
                        previewStack, currentItemId(previewStack));
                if (derived != null) {
                    signature = derived;
                }
                if (handler.addsRandomness()) {
                    entryHasConditions = true;
                }
            } catch (RuntimeException exception) {
                entryHasConditions = true;
                functionHints.add(unknownFunctionHint(functionId));
                if (signature.type() == LootResultSignature.SignatureType.PLAIN) {
                    String functionName = functionId != null ? functionId.toString() : "unknown";
                    signature = LootResultSignature.approximateItemOnly(
                            currentItemId(previewStack), "handler_error:" + functionName);
                }
                LOGGER.warn("应用战利品函数处理器失败: function={}", functionId, exception);
            }
        }

        if (!entryHasConditions && signature.type() == LootResultSignature.SignatureType.PLAIN
                && !previewStack.getComponentsPatch().isEmpty()) {
            signature = LootResultSignature.componentExact(previewStack);
        } else if (entryHasConditions && signature.type() == LootResultSignature.SignatureType.PLAIN) {
            signature = LootResultSignature.approximateItemOnly(currentItemId(previewStack), "function");
        }

        Component displayName = resolveItemDisplayName(previewStack);
        Component tooltipHint;
        if (signature.isEnchantedVariant()) {
            tooltipHint = Component.translatable(ENCHANTED_HINT_KEY);
        } else if (!functionHints.isEmpty()) {
            tooltipHint = joinFunctionHints(functionHints);
        } else {
            tooltipHint = resolveItemTooltipHint(entryHasConditions);
        }

        List<LootConditionInfo> inheritedConditions =
                LootParseUtil.appendConditions(incomingConditions, path.inheritedConditions());
        ResolvedEntry resolved = new ResolvedEntry(currentItemId(previewStack), displayName, tooltipHint,
                signature, List.copyOf(resolvedConditions));
        ItemDefinitionAccumulator accumulator = items.computeIfAbsent(resolved.signature().toStoredKey(),
                ignored -> new ItemDefinitionAccumulator(resolved.itemId(), resolved.displayName(),
                        resolved.tooltipHint(), resolved.signature()));
        accumulator.merge(resolved.displayName(), resolved.tooltipHint(), new LootAcquisitionPath(
                sourceChildTable, path.sourceItemTag(), resolved.conditions(), inheritedConditions));
    }

    /** 单条物品路径的静态求值结果——打包成不可变记录，便于在累加器 lambda 中安全引用。 */
    private record ResolvedEntry(ResourceLocation itemId, Component displayName,
                                 @Nullable Component tooltipHint, LootResultSignature signature,
                                 List<LootConditionInfo> conditions) {
    }

    // ==================== 工具方法 ====================

    // 按"本事件自身 → 本表内已继承 → 上层传入"的顺序拼接函数链；全空时直接返回上层列表
    private static List<JsonElement> concatFunctions(List<JsonElement> own, List<JsonElement> localInherited,
                                                     List<JsonElement> incoming) {
        if (own.isEmpty() && localInherited.isEmpty()) {
            return incoming;
        }
        if (incoming.isEmpty() && localInherited.isEmpty()) {
            return own;
        }
        if (incoming.isEmpty() && own.isEmpty()) {
            return localInherited;
        }
        List<JsonElement> functions = new ArrayList<>(own.size() + localInherited.size() + incoming.size());
        functions.addAll(own);
        functions.addAll(localInherited);
        functions.addAll(incoming);
        return List.copyOf(functions);
    }

    private static ResourceLocation currentItemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    private static Component resolveItemDisplayName(ItemStack previewStack) {
        return previewStack.isEmpty()
                ? Component.translatable("screen.unsuspiciousblock.archaeology_journal.unknown_entry")
                : previewStack.getHoverName().copy();
    }

    @Nullable
    private static Component resolveItemTooltipHint(boolean showApproximate) {
        return showApproximate ? Component.translatable(APPROXIMATE_HINT_KEY) : null;
    }

    // 将多个 function hint 拼接为单个 Component，用中文逗号分隔
    private static Component joinFunctionHints(List<Component> hints) {
        if (hints.isEmpty()) return Component.empty();
        Component result = hints.getFirst();
        for (int i = 1; i < hints.size(); i++) {
            result = Component.literal("").append(result)
                    .append(Component.translatable(
                            "screen.unsuspiciousblock.archaeology_journal.item_hint.separator"))
                    .append(hints.get(i));
        }
        return result;
    }

    private static Component unknownFunctionHint(@Nullable ResourceLocation functionId) {
        return Component.translatable(
                "screen.unsuspiciousblock.archaeology_journal.item_hint.unknown_function",
                functionId != null ? functionId.toString() : "?");
    }
}
