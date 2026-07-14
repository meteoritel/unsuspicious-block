package com.meteorite.unsuspiciousblock.loottable.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
    public Map<ResourceLocation, TableDefinition> load(ResourceManager resourceManager) {
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
                TableDefinition definition = parseTable(tableId, element, resourceManager, new LinkedHashSet<>());
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
                                       ResourceManager resourceManager, Set<ResourceLocation> expandingStack) {
        LinkedHashMap<String, ItemDefinitionBuilder> items = new LinkedHashMap<>();
        boolean[] hasConditions = new boolean[1];
        ParseContext ctx = new ParseContext(items, hasConditions, resourceManager, expandingStack, null);
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
        if (object.has("pools") && parseArrayIfPresent(object, "pools", ctx)) {
            return;
        }

        if (object.has("entries") && parseArrayIfPresent(object, "entries", ctx)) {
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

        // 分析 conditions 数组
        List<LootConditionInfo> entryConditions = List.of();
        if (object.has("conditions") && object.get("conditions").isJsonArray()) {
            entryConditions = LootConditionHandlers.analyzeAll(object.getAsJsonArray("conditions"));
        }

        // 判断是否引入不确定性
        boolean hasUncertainty = LootConditionHandlers.hasAnyUncertainty(entryConditions)
                || object.has("bonus_rolls")
                || (object.has("rolls") && !isFixedRolls(object));

        switch (LootParseUtil.normalizeType(type)) {
            case "item" -> {
                if (hasUncertainty) {
                    ctx.hasConditions[0] = true;
                }
                parseItem(object, ctx, entryConditions);
            }
            case "tag" -> {
                if (hasUncertainty) {
                    ctx.hasConditions[0] = true;
                }
                parseTag(object, ctx, entryConditions);
            }
            case "loot_table" -> {
                ctx.hasConditions[0] = true;
                expandLootTableReference(object, ctx);
            }
            case "group", "alternatives", "sequence" -> {
                ctx.hasConditions[0] = true;
                parseArrayIfPresent(object, "children", ctx);
            }
            default -> {
                ctx.hasConditions[0] = true;
                if (!parseArrayIfPresent(object, "children", ctx)) {
                    parseArrayIfPresent(object, "entries", ctx);
                }
            }
        }
    }

    // 判断 rolls 是否为固定值（非范围/非随机）
    private static boolean isFixedRolls(JsonObject object) {
        JsonElement rollsElement = object.get("rolls");
        if (rollsElement == null) {
            return false;
        }
        if (rollsElement.isJsonPrimitive() && rollsElement.getAsJsonPrimitive().isNumber()) {
            return true;
        }
        // 若为对象（如 {"min": 1, "max": 1}），检查 min==max
        if (rollsElement.isJsonObject()) {
            JsonObject rollsObj = rollsElement.getAsJsonObject();
            if (rollsObj.has("min") && rollsObj.has("max")
                    && rollsObj.get("min").isJsonPrimitive()
                    && rollsObj.get("max").isJsonPrimitive()) {
                return rollsObj.get("min").getAsInt() == rollsObj.get("max").getAsInt();
            }
        }
        return false;
    }

    private void expandLootTableReference(JsonObject object, ParseContext ctx) {
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
            ctx.sourceChildTable = referencedId;
            parseNode(referencedElement, ctx);
            ctx.sourceChildTable = previousSource;
            ctx.expandingStack.remove(referencedId);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("展开 loot_table 引用 {} 失败", referencedId, exception);
        }
    }

    private void parseItem(JsonObject object, ParseContext ctx, List<LootConditionInfo> entryConditions) {
        ResourceLocation itemId = ResourceLocation.tryParse(LootParseUtil.getString(object, "name", ""));
        if (itemId == null || BuiltInRegistries.ITEM.get(itemId) == Items.AIR) {
            return;
        }

        ResolvedEntry resolved = resolveEntry(itemId, object, ctx.hasConditions, entryConditions);
        ItemDefinitionBuilder builder = ctx.items.computeIfAbsent(resolved.signature().toStoredKey(),
                ignored -> createBuilder(resolved, ctx.sourceChildTable));
        builder.mergeResolved(resolved);
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
            ResolvedEntry resolved = resolveEntry(itemId, object, ctx.hasConditions, entryConditions);
            ItemDefinitionBuilder builder = ctx.items.computeIfAbsent(resolved.signature().toStoredKey(),
                    ignored -> createBuilder(resolved, ctx.sourceChildTable));
            builder.mergeResolved(resolved);
        }
    }

    // ==================== Function 解析 ====================

    private ResolvedEntry resolveEntry(ResourceLocation baseItemId, JsonObject object, boolean[] hasConditions,
                                       List<LootConditionInfo> entryConditions) {
        ItemStack previewStack = new ItemStack(BuiltInRegistries.ITEM.get(baseItemId));
        LootResultSignature signature = LootResultSignature.plain(baseItemId);
        boolean entryHasConditions = !entryConditions.isEmpty();

        if (object.has("functions") && object.get("functions").isJsonArray()) {
            for (JsonElement functionElement : object.getAsJsonArray("functions")) {
                if (!functionElement.isJsonObject()) {
                    entryHasConditions = true;
                    continue;
                }

                JsonObject functionObject = functionElement.getAsJsonObject();
                String functionName = LootParseUtil.normalizeType(LootParseUtil.getString(functionObject, "function", ""));
                LootFunctionHandler handler = LootFunctionHandlers.get(functionName);

                if (handler != null) {
                    ItemStack result = handler.apply(previewStack, functionObject);
                    if (result != null) {
                        previewStack = result;
                    } else {
                        entryHasConditions = true;
                    }
                    LootResultSignature derived = handler.deriveSignature(previewStack, currentItemId(previewStack));
                    if (derived != null) {
                        signature = derived;
                    }
                    if (handler.addsRandomness()) {
                        entryHasConditions = true;
                    }
                } else {
                    entryHasConditions = true;
                    if (signature.type() == LootResultSignature.SignatureType.PLAIN) {
                        signature = LootResultSignature.approximateItemOnly(
                                currentItemId(previewStack), "unknown_function:" + functionName);
                    }
                }
            }
        }

        if (entryHasConditions) {
            hasConditions[0] = true;
        }
        if (!entryHasConditions && signature.type() == LootResultSignature.SignatureType.PLAIN
                && !previewStack.getComponentsPatch().isEmpty()) {
            signature = LootResultSignature.componentExact(previewStack);
        } else if (entryHasConditions && signature.type() == LootResultSignature.SignatureType.PLAIN) {
            signature = LootResultSignature.approximateItemOnly(currentItemId(previewStack), "function");
        }

        @Nullable Component hint = null;
        if (signature.isEnchantedVariant()) {
            hint = Component.translatable(ENCHANTED_HINT_KEY);
        }

        Component displayName = resolveItemDisplayName(previewStack);
        Component tooltipHint = resolveItemTooltipHint(hint, entryHasConditions && hint == null);
        return new ResolvedEntry(currentItemId(previewStack), displayName, tooltipHint, signature, entryConditions);
    }

    // ==================== 工具方法 ====================

    private static ResourceLocation currentItemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    private static ItemDefinitionBuilder createBuilder(ResolvedEntry resolved,
                                                       @Nullable ResourceLocation sourceChildTable) {
        return new ItemDefinitionBuilder(resolved.itemId(), resolved.displayName(), resolved.tooltipHint(),
                resolved.signature(), sourceChildTable, resolved.conditions());
    }

    private static Component resolveItemDisplayName(ItemStack previewStack) {
        return previewStack.isEmpty()
                ? Component.translatable("screen.unsuspiciousblock.archaeology_journal.unknown_entry")
                : previewStack.getHoverName().copy();
    }

    @Nullable
    private static Component resolveItemTooltipHint(@Nullable Component hint, boolean showApproximate) {
        if (hint != null) {
            return hint;
        }
        return showApproximate ? Component.translatable(APPROXIMATE_HINT_KEY) : null;
    }

    // ==================== 内部类型 ====================

    /** 解析上下文——将原本分散传递的 5 个可变参数聚合为单一对象，改善方法签名可读性 */
    private static final class ParseContext {
        final Map<String, ItemDefinitionBuilder> items;
        final boolean[] hasConditions;
        final ResourceManager resourceManager;
        final Set<ResourceLocation> expandingStack;
        @Nullable
        ResourceLocation sourceChildTable;

        ParseContext(Map<String, ItemDefinitionBuilder> items, boolean[] hasConditions,
                     ResourceManager resourceManager, Set<ResourceLocation> expandingStack,
                     @Nullable ResourceLocation sourceChildTable) {
            this.items = items;
            this.hasConditions = hasConditions;
            this.resourceManager = resourceManager;
            this.expandingStack = expandingStack;
            this.sourceChildTable = sourceChildTable;
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
        @Nullable
        private final ResourceLocation sourceChildTable;
        private final List<LootConditionInfo> conditions;

        private ItemDefinitionBuilder(ResourceLocation id, Component displayName,
                                      @Nullable Component tooltipHint, LootResultSignature signature,
                                      @Nullable ResourceLocation sourceChildTable,
                                      List<LootConditionInfo> conditions) {
            this.id = id;
            this.displayName = displayName;
            this.tooltipHint = tooltipHint;
            this.signature = signature;
            this.sourceChildTable = sourceChildTable;
            this.conditions = List.copyOf(conditions);
        }

        private void mergeResolved(ResolvedEntry resolved) {
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
                    this.signature, this.sourceChildTable, this.conditions);
        }
    }
}