package com.meteorite.unsuspiciousblock.loottable.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.LootAcquisitionPath;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctions;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 通用战利品表 JSON 解析器——从资源包加载战利品表 JSON，
 * 解析为 {@link TableDefinition} 与 {@link ItemDefinition}。
 * <p>
 * 通过 {@link Predicate} 过滤器决定哪些表纳入解析，
 * 通过 {@link Function} 解析器决定表展示名，实现与具体业务（考古、钓鱼等）解耦。
 * <p>
 * 解析期间自动分析 entry 的 conditions 数组，通过 {@link LootConditionHandlers}
 * 静态评估条件并生成人类可读描述，供 UI 展示条件触发信息。
 */
public final class LootTableJsonParser {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FileToIdConverter LOOT_TABLES = FileToIdConverter.json("loot_table");
    private static final String ENCHANTED_HINT_KEY = "screen.unsuspiciousblock.archaeology_journal.item_hint.enchanted";
    private static final String APPROXIMATE_HINT_KEY = "screen.unsuspiciousblock.archaeology_journal.item_hint.approximate";

    private final Predicate<ResourceLocation> tableFilter;
    private final Function<ResourceLocation, Component> tableNameResolver;

    /**
     * @param tableFilter       战利品表过滤器，返回 true 的表纳入解析
     * @param tableNameResolver 表展示名解析器
     */
    public LootTableJsonParser(Predicate<ResourceLocation> tableFilter,
                               Function<ResourceLocation, Component> tableNameResolver) {
        this.tableFilter = tableFilter;
        this.tableNameResolver = tableNameResolver;
    }

    /**
     * 从 ResourceManager 加载所有匹配 filter 的战利品表。
     */
    public Map<ResourceLocation, TableDefinition> load(ResourceManager resourceManager,
                                                       HolderLookup.Provider registries) {
        DynamicOps<JsonElement> lootOps = RegistryOps.create(JsonOps.INSTANCE, registries);
        Map<ResourceLocation, Resource> allResources = LOOT_TABLES.listMatchingResources(resourceManager);
        List<Map.Entry<ResourceLocation, Resource>> orderedResources = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Resource> entry : allResources.entrySet()) {
            ResourceLocation tableId = LOOT_TABLES.fileToId(entry.getKey());
            if (tableFilter.test(tableId)) {
                orderedResources.add(Map.entry(tableId, entry.getValue()));
            }
        }
        orderedResources.sort(Comparator.comparing(entry -> entry.getKey().toString()));

        LinkedHashMap<ResourceLocation, TableDefinition> tables = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Resource> entry : orderedResources) {
            ResourceLocation tableId = entry.getKey();
            try (BufferedReader reader = entry.getValue().openAsReader()) {
                JsonElement element = JsonParser.parseReader(reader);
                TableDefinition definition = parseTable(tableId, element, resourceManager,
                        new LinkedHashSet<>(), lootOps);
                if (!definition.items().isEmpty()) {
                    tables.put(tableId, definition);
                }
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Failed to read loot table {}", entry.getKey(), exception);
            }
        }

        return tables;
    }

    // ==================== 解析核心 ====================

    private TableDefinition parseTable(ResourceLocation tableId, JsonElement element,
                                       ResourceManager resourceManager, Set<ResourceLocation> expandingStack,
                                       DynamicOps<JsonElement> lootOps) {
        LinkedHashMap<String, ItemDefinitionBuilder> items = new LinkedHashMap<>();
        ParseContext ctx = new ParseContext(items, resourceManager, expandingStack,
                lootOps, null, List.of(), List.of());
        parseNode(element, ctx);

        List<ItemDefinition> definitions = new ArrayList<>();
        for (ItemDefinitionBuilder builder : items.values()) {
            definitions.add(builder.build());
        }
        definitions.sort(Comparator
                .comparing((ItemDefinition definition) -> definition.id().toString())
                .thenComparing(definition -> definition.signature().toStoredKey()));

        String type = "";
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("type") && object.get("type").isJsonPrimitive()) {
                type = object.get("type").getAsString();
            }
        }

        return new TableDefinition(tableId, tableNameResolver.apply(tableId), type, definitions, 0);
    }

    private void parseNode(JsonElement element, ParseContext ctx) {
        if (element == null || element.isJsonNull()) {
            return;
        }

        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                parseNode(child, ctx);
            }
            return;
        }

        if (!element.isJsonObject()) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        if (object.has("pools") && object.get("pools").isJsonArray()) {
            List<JsonElement> previousFunctions = ctx.inheritedFunctions;
            try {
                ctx.inheritedFunctions = prependFunctions(previousFunctions, object);
                parseArray(object.getAsJsonArray("pools"), ctx);
            } finally {
                ctx.inheritedFunctions = previousFunctions;
            }
            return;
        }

        // 处理 entries：先捕获 pool 级别的 conditions，临时合并到 parentTableConditions
        if (object.has("entries") && object.get("entries").isJsonArray()) {
            List<LootConditionInfo> poolConditions = parseConditions(object, ctx.lootOps);
            List<LootConditionInfo> previousParentConditions = ctx.parentTableConditions;
            List<JsonElement> previousFunctions = ctx.inheritedFunctions;
            try {
                pushInheritedConditions(ctx, poolConditions);
                ctx.inheritedFunctions = prependFunctions(previousFunctions, object);
                parseArray(object.getAsJsonArray("entries"), ctx);
            } finally {
                ctx.parentTableConditions = previousParentConditions;
                ctx.inheritedFunctions = previousFunctions;
            }
            return;
        }

        parseEntry(object, ctx);
    }

    private void parseArray(JsonArray array, ParseContext ctx) {
        for (JsonElement child : array) {
            parseNode(child, ctx);
        }
    }

    private boolean parseArrayIfPresent(JsonObject object, String key, ParseContext ctx) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            return false;
        }

        parseArray(object.getAsJsonArray(key), ctx);
        return true;
    }

    private void parseEntry(JsonObject object, ParseContext ctx) {
        String type = LootParseUtil.getString(object, "type", "");

        // 分析 conditions 数组——使用原版 Codec 解析为类型化对象
        List<LootConditionInfo> entryConditions = parseConditions(object, ctx.lootOps);

        switch (LootParseUtil.normalizeType(type)) {
            case "item" -> parseItem(object, ctx, entryConditions);
            case "tag" -> parseTag(object, ctx, entryConditions);
            case "loot_table" -> {
                expandLootTableReference(object, ctx, entryConditions);
            }
            case "group", "alternatives", "sequence" -> {
                parseChildrenWithInheritedConditions(object, ctx, entryConditions);
            }
            default -> {
                List<LootConditionInfo> previousParentConditions = pushInheritedConditions(ctx, entryConditions);
                try {
                    if (!parseArrayIfPresent(object, "children", ctx)) {
                        parseArrayIfPresent(object, "entries", ctx);
                    }
                } finally {
                    ctx.parentTableConditions = previousParentConditions;
                }
            }
        }
    }

    private void parseChildrenWithInheritedConditions(JsonObject object, ParseContext ctx,
                                                       List<LootConditionInfo> entryConditions) {
        List<LootConditionInfo> previousParentConditions = pushInheritedConditions(ctx, entryConditions);
        try {
            parseArrayIfPresent(object, "children", ctx);
        } finally {
            ctx.parentTableConditions = previousParentConditions;
        }
    }

    // 从 JSON 对象中解析 conditions 数组，返回可读的条件信息列表
    private static List<LootConditionInfo> parseConditions(JsonObject object, DynamicOps<JsonElement> lootOps) {
        if (!object.has("conditions") || !object.get("conditions").isJsonArray()) {
            return List.of();
        }
        List<LootItemCondition> parsedConditions = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray("conditions")) {
            var result = LootItemCondition.DIRECT_CODEC.parse(lootOps, element);
            result.result().ifPresentOrElse(
                    parsedConditions::add,
                    () -> {
                        ResourceLocation conditionId = extractTypeId(element, "condition");
                        String conditionType = conditionId != null ? conditionId.toString() : "<unknown>";
                        LOGGER.warn("解析战利品条件失败: condition={}, error={}",
                                conditionType, result.error().map(Object::toString).orElse("unknown"));
                        parsedConditions.add(null);
                    });
        }
        List<LootConditionInfo> conditions = new ArrayList<>();
        int parsedIndex = 0;
        for (JsonElement element : object.getAsJsonArray("conditions")) {
            LootItemCondition condition = parsedConditions.get(parsedIndex++);
            if (condition != null) {
                conditions.addAll(LootConditionHandlers.analyzeAll(List.of(condition)));
            } else {
                conditions.add(LootConditionHandlers.fallbackInfo(extractTypeId(element, "condition")));
            }
        }
        return conditions;
    }

    @Nullable
    private static ResourceLocation extractTypeId(JsonElement element, String fieldName) {
        if (!element.isJsonObject()) {
            return null;
        }
        JsonElement typeElement = element.getAsJsonObject().get(fieldName);
        if (typeElement == null || !typeElement.isJsonPrimitive()
                || !typeElement.getAsJsonPrimitive().isString()) {
            return null;
        }
        return ResourceLocation.tryParse(typeElement.getAsString());
    }

    private static List<LootConditionInfo> pushInheritedConditions(ParseContext ctx,
                                                                    List<LootConditionInfo> conditions) {
        List<LootConditionInfo> previousConditions = ctx.parentTableConditions;
        if (!conditions.isEmpty()) {
            List<LootConditionInfo> merged = new ArrayList<>(previousConditions);
            merged.addAll(conditions);
            ctx.parentTableConditions = List.copyOf(merged);
        }
        return previousConditions;
    }

    private static List<JsonElement> prependFunctions(List<JsonElement> inheritedFunctions, JsonObject object) {
        if (!object.has("functions") || !object.get("functions").isJsonArray()) {
            return inheritedFunctions;
        }
        List<JsonElement> functions = new ArrayList<>(
                inheritedFunctions.size() + object.getAsJsonArray("functions").size());
        object.getAsJsonArray("functions").forEach(functions::add);
        functions.addAll(inheritedFunctions);
        return List.copyOf(functions);
    }

    private void expandLootTableReference(JsonObject object, ParseContext ctx,
                                           List<LootConditionInfo> entryConditions) {
        String rawId = object.has("value") ? object.get("value").getAsString() : LootParseUtil.getString(object, "name", "");
        ResourceLocation referencedId = ResourceLocation.tryParse(rawId);
        if (referencedId == null) {
            LOGGER.warn("loot_table 引用缺少 value/name 字段，跳过展开");
            return;
        }
        if (ctx.expandingStack.contains(referencedId)) {
            LOGGER.warn("检测到 loot_table 循环引用 {}，跳过展开", referencedId);
            return;
        }

        ResourceLocation filePath = LOOT_TABLES.idToFile(referencedId);
        try (BufferedReader reader = ctx.resourceManager.openAsReader(filePath)) {
            JsonElement referencedElement = JsonParser.parseReader(reader);
            ctx.expandingStack.add(referencedId);
            ResourceLocation previousSource = ctx.sourceChildTable;
            List<LootConditionInfo> previousParentConditions = ctx.parentTableConditions;
            List<JsonElement> previousFunctions = ctx.inheritedFunctions;
            try {
                ctx.sourceChildTable = referencedId;
                pushInheritedConditions(ctx, entryConditions);
                ctx.inheritedFunctions = prependFunctions(previousFunctions, object);
                parseNode(referencedElement, ctx);
            } finally {
                ctx.parentTableConditions = previousParentConditions;
                ctx.inheritedFunctions = previousFunctions;
                ctx.sourceChildTable = previousSource;
                ctx.expandingStack.remove(referencedId);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("展开 loot_table 引用 {} 失败", referencedId, exception);
        }
    }

    private void parseItem(JsonObject object, ParseContext ctx, List<LootConditionInfo> entryConditions) {
        ResourceLocation itemId = ResourceLocation.tryParse(LootParseUtil.getString(object, "name", ""));
        if (itemId == null || BuiltInRegistries.ITEM.get(itemId) == Items.AIR) {
            return;
        }

        ResolvedEntry resolved = resolveEntry(itemId, object, ctx, entryConditions);
        ItemDefinitionBuilder builder = ctx.items.computeIfAbsent(resolved.signature().toStoredKey(),
                ignored -> createBuilder(resolved));
        builder.mergeResolved(resolved, createPath(resolved, ctx, null));
    }

    private void parseTag(JsonObject object, ParseContext ctx, List<LootConditionInfo> entryConditions) {
        ResourceLocation tagId = ResourceLocation.tryParse(LootParseUtil.getString(object, "name", ""));
        if (tagId == null) {
            return;
        }

        LinkedHashSet<ResourceLocation> itemIds = new LinkedHashSet<>();
        TagKey<Item> tagKey = TagKey.create(Registries.ITEM, tagId);
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tagKey)) {
            Item item = holder.value();
            if (item == Items.AIR) {
                continue;
            }
            itemIds.add(BuiltInRegistries.ITEM.getKey(item));
        }

        if (itemIds.isEmpty()) {
            return;
        }

        for (ResourceLocation itemId : itemIds) {
            ResolvedEntry resolved = resolveEntry(itemId, object, ctx, entryConditions);
            ItemDefinitionBuilder builder = ctx.items.computeIfAbsent(resolved.signature().toStoredKey(),
                    ignored -> createBuilder(resolved));
            builder.mergeResolved(resolved, createPath(resolved, ctx, tagId));
        }
    }

    // ==================== Function 解析 ====================

    private ResolvedEntry resolveEntry(ResourceLocation baseItemId, JsonObject object, ParseContext ctx,
                                       List<LootConditionInfo> entryConditions) {
        ItemStack previewStack = new ItemStack(BuiltInRegistries.ITEM.get(baseItemId));
        LootResultSignature signature = LootResultSignature.plain(baseItemId);
        boolean entryHasConditions = !entryConditions.isEmpty();
        List<LootConditionInfo> resolvedConditions = new ArrayList<>(entryConditions);
        List<Component> functionHints = new ArrayList<>();

        List<JsonElement> functions = prependFunctions(ctx.inheritedFunctions, object);
        if (!functions.isEmpty()) {
            for (JsonElement functionElement : functions) {
                if (!functionElement.isJsonObject()) {
                    entryHasConditions = true;
                    continue;
                }

                List<LootConditionInfo> functionConditions = parseConditions(
                        functionElement.getAsJsonObject(), ctx.lootOps);
                if (!functionConditions.isEmpty()) {
                    entryHasConditions = true;
                    resolvedConditions.addAll(functionConditions);
                }

                // 使用原版 Codec 解析 function
                var decodeResult = LootItemFunctions.TYPED_CODEC.parse(ctx.lootOps, functionElement);
                LootItemFunction function = decodeResult.result().orElse(null);
                if (function == null) {
                    entryHasConditions = true;
                    functionHints.add(unknownFunctionHint(extractTypeId(functionElement, "function")));
                    LOGGER.warn("解析战利品函数失败: function={}, error={}",
                            String.valueOf(extractTypeId(functionElement, "function")),
                            decodeResult.error().map(Object::toString).orElse("unknown"));
                    continue;
                }

                ResourceLocation functionId = BuiltInRegistries.LOOT_FUNCTION_TYPE.getKey(function.getType());
                LootFunctionHandler handler = LootFunctionHandlers.get(functionId);

                if (handler != null) {
                    try {
                        if (!functionConditions.isEmpty()) {
                            Component hint = handler.describeHint(function);
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
                            Component hint = handler.describeHint(function);
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
                } else {
                    // 未知 function：标记为条件 + 近似签名
                    entryHasConditions = true;
                    functionHints.add(unknownFunctionHint(functionId));
                    if (signature.type() == LootResultSignature.SignatureType.PLAIN) {
                        String functionName = functionId != null ? functionId.toString() : "unknown";
                        signature = LootResultSignature.approximateItemOnly(
                                currentItemId(previewStack), "unknown_function:" + functionName);
                    }
                }
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
        return new ResolvedEntry(currentItemId(previewStack), displayName, tooltipHint, signature,
                List.copyOf(resolvedConditions));
    }

    // ==================== 工具方法 ====================

    private static ResourceLocation currentItemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    private static ItemDefinitionBuilder createBuilder(ResolvedEntry resolved) {
        return new ItemDefinitionBuilder(resolved.itemId(), resolved.displayName(), resolved.tooltipHint(),
                resolved.signature());
    }

    private static LootAcquisitionPath createPath(ResolvedEntry resolved, ParseContext ctx,
                                                  @Nullable ResourceLocation sourceItemTag) {
        return new LootAcquisitionPath(ctx.sourceChildTable, sourceItemTag,
                resolved.conditions(), ctx.parentTableConditions);
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

    // ==================== 内部类型 ====================

    /** 解析上下文——将原本分散传递的 5 个可变参数聚合为单一对象，改善方法签名可读性 */
    private static final class ParseContext {
        final Map<String, ItemDefinitionBuilder> items;
        final ResourceManager resourceManager;
        final Set<ResourceLocation> expandingStack;
        final DynamicOps<JsonElement> lootOps;
        @Nullable
        ResourceLocation sourceChildTable;
        List<LootConditionInfo> parentTableConditions;
        List<JsonElement> inheritedFunctions;

        ParseContext(Map<String, ItemDefinitionBuilder> items, ResourceManager resourceManager,
                     Set<ResourceLocation> expandingStack,
                     DynamicOps<JsonElement> lootOps,
                     @Nullable ResourceLocation sourceChildTable,
                     List<LootConditionInfo> parentTableConditions,
                     List<JsonElement> inheritedFunctions) {
            this.items = items;
            this.resourceManager = resourceManager;
            this.expandingStack = expandingStack;
            this.lootOps = lootOps;
            this.sourceChildTable = sourceChildTable;
            this.parentTableConditions = parentTableConditions;
            this.inheritedFunctions = inheritedFunctions;
        }
    }

    private record ResolvedEntry(ResourceLocation itemId, Component displayName,
                                 @Nullable Component tooltipHint, LootResultSignature signature,
                                 List<LootConditionInfo> conditions) {
    }

    private static final class ItemDefinitionBuilder {
        private final ResourceLocation id;
        private Component displayName;
        @Nullable
        private Component tooltipHint;
        private final LootResultSignature signature;
        private final List<LootAcquisitionPath> acquisitionPaths = new ArrayList<>();

        private ItemDefinitionBuilder(ResourceLocation id, Component displayName,
                                      @Nullable Component tooltipHint, LootResultSignature signature) {
            this.id = id;
            this.displayName = displayName;
            this.tooltipHint = tooltipHint;
            this.signature = signature;
        }

        private void mergeResolved(ResolvedEntry resolved, LootAcquisitionPath acquisitionPath) {
            if (!this.acquisitionPaths.contains(acquisitionPath)) {
                this.acquisitionPaths.add(acquisitionPath);
            }
            if (!this.displayName.getString().equals(resolved.displayName().getString())) {
                this.displayName = LootTableCatalog.resolveMergedDisplayName(this.id, this.signature);
            }
            if (this.tooltipHint == null ? resolved.tooltipHint() == null
                    : this.tooltipHint.getString().equals(resolved.tooltipHint() != null ? resolved.tooltipHint().getString() : null)) {
                return;
            }
            this.tooltipHint = LootTableCatalog.resolveMergedTooltipHint(this.signature);
        }

        private ItemDefinition build() {
            return new ItemDefinition(this.id, this.displayName, this.tooltipHint, "?",
                    this.signature, this.acquisitionPaths, false);
        }
    }
}
