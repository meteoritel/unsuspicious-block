package com.meteorite.unsuspiciousblock.journal.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.LootTableNames;
import com.meteorite.unsuspiciousblock.loottable.LootResultSignature;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
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

/**
 * 考古战利品表目录——客户端侧 JSON 解析器。
 * 从资源包加载所有匹配考古路径前缀的战利品表 JSON，
 * 解析为 TableDefinition 与 ItemDefinition，供考古笔记 UI 渲染目录树。
 */
public final class ArchaeologyJournalCatalog {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FileToIdConverter LOOT_TABLES = FileToIdConverter.json("loot_table");
    private static final ResourceLocation BOOK_ID = ResourceLocation.fromNamespaceAndPath("minecraft", "book");
    private static final String RANDOM_HINT_KEY = "screen.unsuspiciousblock.archaeology_journal.item_hint.enchanted_random";
    private static final String LEVEL_HINT_KEY = "screen.unsuspiciousblock.archaeology_journal.item_hint.enchanted_level";
    private static final String ENCHANTED_HINT_KEY = "screen.unsuspiciousblock.archaeology_journal.item_hint.enchanted";
    private static final String APPROXIMATE_HINT_KEY = "screen.unsuspiciousblock.archaeology_journal.item_hint.approximate";

    private ArchaeologyJournalCatalog() {
    }

    /**
     * 判断 ItemDefinition 在解析阶段是否标记为含条件（影响模拟结果置信度）。
     * 标记为 hasConditions 的条目如果在模拟中零出现，概率显示为 "?"。
     */
    public static boolean hasConditions(ItemDefinition definition) {
        // tooltipHint 以 approximate_hint 为条件的条目被视为含条件
        // 但 enchanted_random / enchanted_level 虽有随机性，模拟抽取能覆盖，不算条件限制
        return definition.tooltipHint() != null
                && definition.tooltipHint().getString().equals(
                Component.translatable(APPROXIMATE_HINT_KEY).getString());
    }

    // 从任意 ResourceManager 加载目录（客户端或服务端均可使用）
    public static Map<ResourceLocation, TableDefinition> load(ResourceManager resourceManager) {
        Map<ResourceLocation, Resource> allResources = LOOT_TABLES.listMatchingResources(resourceManager);
        List<Map.Entry<ResourceLocation, Resource>> orderedResources = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Resource> entry : allResources.entrySet()) {
            ResourceLocation tableId = LOOT_TABLES.fileToId(entry.getKey());
            if (LootTableNames.isArchaeologyLootTable(tableId)) {
                orderedResources.add(Map.entry(tableId, entry.getValue()));
            }
        }
        orderedResources.sort(Comparator.comparing(entry -> entry.getKey().toString()));

        LinkedHashMap<ResourceLocation, TableDefinition> tables = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Resource> entry : orderedResources) {
            ResourceLocation tableId = entry.getKey();
            LootTableNames.ensureRegistered(tableId);
            // 提前触发缺失 key 导出，与 JSON 解析解耦——翻译 key 仅由 tableId 派生，
            // 即使后续 JSON 解析失败（模组表常见自定义 entry/condition），缺失 key 仍能被记录
            LootTableNames.resolveDisplayName(tableId);
            try (BufferedReader reader = entry.getValue().openAsReader()) {
                JsonElement element = JsonParser.parseReader(reader);
                TableDefinition definition = parseTable(entry.getKey(), element, resourceManager, new LinkedHashSet<>());
                if (!definition.items().isEmpty()) {
                    tables.put(entry.getKey(), definition);
                }
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Failed to read archaeology loot table {}", entry.getKey(), exception);
            }
        }

        return tables;
    }

    /**
     * 为模拟期发现的"注入条目"（GLM / LootTableEvents.MODIFY 注入，JSON 中不存在）构建 ItemDefinition。
     * 显示名与提示从签名预览栈推导，概率由调用方传入（模拟统计或缓存恢复值）。
     */
    public static ItemDefinition buildDiscoveredDefinition(LootResultSignature signature, String probability) {
        ResourceLocation itemId = signature.itemId();
        Component displayName = resolveMergedDisplayName(itemId, signature);
        Component tooltipHint = resolveMergedTooltipHint(signature);
        return new ItemDefinition(itemId, displayName, tooltipHint, probability, signature);
    }

    private static TableDefinition parseTable(ResourceLocation tableId, JsonElement element,
                                              ResourceManager resourceManager, Set<ResourceLocation> expandingStack) {
        LinkedHashMap<String, ItemDefinitionBuilder> items = new LinkedHashMap<>();
        boolean[] hasConditions = new boolean[1];
        parseNode(element, items, hasConditions, resourceManager, expandingStack, null);

        List<ItemDefinition> definitions = new ArrayList<>();
        for (ItemDefinitionBuilder builder : items.values()) {
            definitions.add(builder.build());
        }
        definitions.sort(Comparator
                .comparing((ItemDefinition definition) -> definition.id().toString())
                .thenComparing(definition -> definition.signature().toStoredKey()));

        // 读取战利品表声明的 type（如 minecraft:archaeology / minecraft:fishing / minecraft:generic），
        // 用于目录排序时按类型聚类；缺失时视为空串，排序时排在最后
        String type = "";
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("type") && object.get("type").isJsonPrimitive()) {
                type = object.get("type").getAsString();
            }
        }

        return new TableDefinition(tableId, resolveTableName(tableId), type, definitions, 0);
    }

    // sourceChildTable：当前解析路径所属的子表 ID（null=根表直接产出）；递归展开 loot_table 引用时透传给 builder
    private static void parseNode(JsonElement element, Map<String, ItemDefinitionBuilder> items, boolean[] hasConditions,
                                  ResourceManager resourceManager, Set<ResourceLocation> expandingStack,
                                  @Nullable ResourceLocation sourceChildTable) {
        if (element == null || element.isJsonNull()) {
            return;
        }

        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                parseNode(child, items, hasConditions, resourceManager, expandingStack, sourceChildTable);
            }
            return;
        }

        if (!element.isJsonObject()) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        if (object.has("pools") && parseArrayIfPresent(object, "pools", items, hasConditions, resourceManager, expandingStack, sourceChildTable)) {
            return;
        }

        if (object.has("entries") && parseArrayIfPresent(object, "entries", items, hasConditions, resourceManager, expandingStack, sourceChildTable)) {
            return;
        }

        parseEntry(object, items, hasConditions, resourceManager, expandingStack, sourceChildTable);
    }

    private static void parseArray(JsonArray array, Map<String, ItemDefinitionBuilder> items, boolean[] hasConditions,
                                   ResourceManager resourceManager, Set<ResourceLocation> expandingStack,
                                   @Nullable ResourceLocation sourceChildTable) {
        for (JsonElement child : array) {
            parseNode(child, items, hasConditions, resourceManager, expandingStack, sourceChildTable);
        }
    }

    private static boolean parseArrayIfPresent(JsonObject object, String key,
                                               Map<String, ItemDefinitionBuilder> items, boolean[] hasConditions,
                                               ResourceManager resourceManager, Set<ResourceLocation> expandingStack,
                                               @Nullable ResourceLocation sourceChildTable) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            return false;
        }

        parseArray(object.getAsJsonArray(key), items, hasConditions, resourceManager, expandingStack, sourceChildTable);
        return true;
    }

    private static void parseEntry(JsonObject object, Map<String, ItemDefinitionBuilder> items, boolean[] hasConditions,
                                   ResourceManager resourceManager, Set<ResourceLocation> expandingStack,
                                   @Nullable ResourceLocation sourceChildTable) {
        String type = getString(object, "type", "");
        boolean complex = object.has("conditions") || object.has("bonus_rolls") || object.has("rolls");

        switch (normalizeType(type)) {
            case "item" -> {
                if (complex) {
                    hasConditions[0] = true;
                }
                parseItem(object, items, hasConditions, sourceChildTable);
            }
            case "tag" -> {
                if (complex) {
                    hasConditions[0] = true;
                }
                parseTag(object, items, hasConditions, sourceChildTable);
            }
            case "loot_table" -> {
                hasConditions[0] = true;
                expandLootTableReference(object, items, hasConditions, resourceManager, expandingStack, sourceChildTable);
            }
            case "group", "alternatives", "sequence" -> {
                hasConditions[0] = true;
                parseArrayIfPresent(object, "children", items, hasConditions, resourceManager, expandingStack, sourceChildTable);
            }
            default -> {
                hasConditions[0] = true;
                if (!parseArrayIfPresent(object, "children", items, hasConditions, resourceManager, expandingStack, sourceChildTable)) {
                    parseArrayIfPresent(object, "entries", items, hasConditions, resourceManager, expandingStack, sourceChildTable);
                }
            }
        }
    }

    // 展开 minecraft:loot_table 类型 entry 引用的表，将其条目合并进当前目录。
    // 1.21.1 使用 "value" 字段；兼容旧格式 "name"。
    // 展开后的条目 sourceChildTable 为 referencedId（子表 ID），标识物品来自该子表。
    private static void expandLootTableReference(JsonObject object, Map<String, ItemDefinitionBuilder> items,
                                                 boolean[] hasConditions, ResourceManager resourceManager,
                                                 Set<ResourceLocation> expandingStack,
                                                 @Nullable ResourceLocation sourceChildTable) {
        String rawId = object.has("value") ? object.get("value").getAsString() : getString(object, "name", "");
        ResourceLocation referencedId = ResourceLocation.tryParse(rawId);
        if (referencedId == null) {
            LOGGER.warn("loot_table 引用缺少 value/name 字段，跳过展开");
            return;
        }
        if (expandingStack.contains(referencedId)) {
            LOGGER.warn("检测到 loot_table 循环引用 {}，跳过展开", referencedId);
            return;
        }

        ResourceLocation filePath = LOOT_TABLES.idToFile(referencedId);
        try (BufferedReader reader = resourceManager.openAsReader(filePath)) {
            JsonElement referencedElement = JsonParser.parseReader(reader);
            expandingStack.add(referencedId);
            // 展开子表时，sourceChildTable 更新为 referencedId，标识子表条目
            parseNode(referencedElement, items, hasConditions, resourceManager, expandingStack, referencedId);
            expandingStack.remove(referencedId);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("展开 loot_table 引用 {} 失败", referencedId, exception);
        }
    }

    private static void parseItem(JsonObject object, Map<String, ItemDefinitionBuilder> items, boolean[] hasConditions,
                                  @Nullable ResourceLocation sourceChildTable) {
        ResourceLocation itemId = ResourceLocation.tryParse(getString(object, "name", ""));
        if (itemId == null || BuiltInRegistries.ITEM.get(itemId) == Items.AIR) {
            return;
        }

        ResolvedEntry resolved = resolveEntry(itemId, object, hasConditions);
        ItemDefinitionBuilder builder = items.computeIfAbsent(resolved.signature().toStoredKey(),
                ignored -> createBuilder(resolved, sourceChildTable));
        builder.mergeResolved(resolved);
    }

    private static void parseTag(JsonObject object, Map<String, ItemDefinitionBuilder> items, boolean[] hasConditions,
                                 @Nullable ResourceLocation sourceChildTable) {
        ResourceLocation tagId = ResourceLocation.tryParse(getString(object, "name", ""));
        if (tagId == null) {
            return;
        }

        boolean expand = getBoolean(object, "expand", false);
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
            ResolvedEntry resolved = resolveEntry(itemId, object, hasConditions);
            ItemDefinitionBuilder builder = items.computeIfAbsent(resolved.signature().toStoredKey(),
                    ignored -> createBuilder(resolved, sourceChildTable));
            builder.mergeResolved(resolved);
        }
    }

    private static ResolvedEntry resolveEntry(ResourceLocation baseItemId, JsonObject object, boolean[] hasConditions) {
        ItemStack previewStack = new ItemStack(BuiltInRegistries.ITEM.get(baseItemId));
        LootResultSignature signature = LootResultSignature.plain(baseItemId);
        @Nullable Component hint = null;
        boolean entryHasConditions = false;

        if (object.has("functions") && object.get("functions").isJsonArray()) {
            for (JsonElement functionElement : object.getAsJsonArray("functions")) {
                if (!functionElement.isJsonObject()) {
                    entryHasConditions = true;
                    continue;
                }

                JsonObject functionObject = functionElement.getAsJsonObject();
                String function = normalizeType(getString(functionObject, "function", ""));
                switch (function) {
                    case "set_item" -> {
                        ResourceLocation functionItemId = parseFunctionItemId(functionObject);
                        if (functionItemId != null && BuiltInRegistries.ITEM.get(functionItemId) != Items.AIR) {
                            previewStack = previewStack.transmuteCopy(BuiltInRegistries.ITEM.get(functionItemId));
                            signature = LootResultSignature.plain(currentItemId(previewStack));
                        } else {
                            entryHasConditions = true;
                        }
                    }
                    case "enchant_randomly" -> {
                        previewStack = promoteBookPreviewIfNeeded(previewStack);
                        signature = LootResultSignature.enchantedApprox(currentItemId(previewStack));
                        hint = Component.translatable(ENCHANTED_HINT_KEY);
                    }
                    case "enchant_with_levels" -> {
                        previewStack = promoteBookPreviewIfNeeded(previewStack);
                        signature = LootResultSignature.enchantedApprox(currentItemId(previewStack));
                        hint = Component.translatable(ENCHANTED_HINT_KEY);
                    }
                    case "set_enchantments" -> {
                        previewStack = promoteBookPreviewIfNeeded(previewStack);
                        signature = LootResultSignature.enchantedApprox(currentItemId(previewStack));
                        if (hint == null) {
                            hint = Component.translatable(ENCHANTED_HINT_KEY);
                        }
                        entryHasConditions = true;
                    }
                    case "set_components" -> {
                        if (!applyExactComponents(previewStack, functionObject.get("components"))) {
                            entryHasConditions = true;
                        }
                    }
                    case "set_custom_data" -> {
                        if (!applyCustomData(previewStack, functionObject.get("tag"))) {
                            entryHasConditions = true;
                        }
                    }
                    case "set_name" -> {
                        if (!applySetName(previewStack, functionObject)) {
                            entryHasConditions = true;
                        }
                    }
                    default -> {
                        if (isEnchantLikeFunction(function)) {
                            previewStack = promoteBookPreviewIfNeeded(previewStack);
                            signature = LootResultSignature.enchantedApprox(currentItemId(previewStack));
                            if (hint == null) {
                                hint = Component.translatable(ENCHANTED_HINT_KEY);
                            }
                            entryHasConditions = true;
                        } else if (affectsDisplayedResult(function)) {
                            entryHasConditions = true;
                        }
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

        Component displayName = resolveItemDisplayName(previewStack);
        Component tooltipHint = resolveItemTooltipHint(hint, entryHasConditions && hint == null);
        return new ResolvedEntry(currentItemId(previewStack), displayName, tooltipHint, signature);
    }

    private static ResourceLocation currentItemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    private static ItemStack promoteBookPreviewIfNeeded(ItemStack stack) {
        return BOOK_ID.equals(currentItemId(stack)) ? stack.transmuteCopy(Items.ENCHANTED_BOOK) : stack;
    }

    private static boolean applyExactComponents(ItemStack previewStack, @Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return true;
        }

        DataComponentPatch patch = DataComponentPatch.CODEC.parse(JsonOps.INSTANCE, element)
                .result()
                .orElse(null);
        if (patch == null) {
            return false;
        }

        try {
            previewStack.applyComponentsAndValidate(patch);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean applyCustomData(ItemStack previewStack, @Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return true;
        }

        CompoundTag tag = TagParser.LENIENT_CODEC.parse(JsonOps.INSTANCE, element)
                .result()
                .orElse(null);
        if (tag == null) {
            return false;
        }

        CustomData.update(DataComponents.CUSTOM_DATA, previewStack, data -> data.merge(tag));
        return true;
    }

    private static boolean applySetName(ItemStack previewStack, JsonObject functionObject) {
        if (functionObject.has("entity")) {
            return false;
        }

        JsonElement nameElement = functionObject.get("name");
        if (nameElement == null || nameElement.isJsonNull()) {
            return true;
        }

        Component component = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, nameElement)
                .result()
                .orElse(null);
        if (component == null) {
            return false;
        }

        return switch (normalizeType(getString(functionObject, "target", "custom_name"))) {
            case "custom_name" -> {
                previewStack.set(DataComponents.CUSTOM_NAME, component);
                yield true;
            }
            case "item_name" -> {
                previewStack.set(DataComponents.ITEM_NAME, component);
                yield true;
            }
            default -> false;
        };
    }


    @Nullable
    private static ResourceLocation parseFunctionItemId(JsonObject functionObject) {
        ResourceLocation itemId = ResourceLocation.tryParse(getString(functionObject, "item", ""));
        if (itemId != null) {
            return itemId;
        }
        return ResourceLocation.tryParse(getString(functionObject, "name", ""));
    }

    @Nullable
    private static String parseLevelInfo(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (!element.isJsonObject()) {
            return null;
        }

        JsonObject object = element.getAsJsonObject();
        String min = parseProviderEndpoint(object, "min");
        String max = parseProviderEndpoint(object, "max");
        if (min == null && max == null) {
            Integer exactValue = parseExactIntValue(element);
            return exactValue != null ? exactValue.toString() : null;
        }
        if (min == null) {
            return max;
        }
        if (max == null || min.equals(max)) {
            return min;
        }
        return min + "-" + max;
    }

    @Nullable
    private static String parseProviderEndpoint(JsonObject object, String key) {
        if (!object.has(key)) {
            return null;
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (!element.isJsonObject()) {
            return null;
        }

        JsonObject provider = element.getAsJsonObject();
        if (provider.has("value")) {
            return provider.get("value").getAsString();
        }
        if (provider.has("min") && provider.has("max")
                && provider.get("min").isJsonPrimitive() && provider.get("max").isJsonPrimitive()) {
            String min = provider.get("min").getAsString();
            String max = provider.get("max").getAsString();
            return min.equals(max) ? min : min + "-" + max;
        }
        return null;
    }

    @Nullable
    private static Integer parseExactIntValue(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsInt();
        }
        if (!element.isJsonObject()) {
            return null;
        }

        JsonObject object = element.getAsJsonObject();
        if (object.has("value")) {
            return parseExactIntValue(object.get("value"));
        }
        if (object.has("type") && normalizeType(getString(object, "type", "")).equals("constant") && object.has("value")) {
            return parseExactIntValue(object.get("value"));
        }
        if (object.has("min") && object.has("max")) {
            Integer min = parseExactIntValue(object.get("min"));
            Integer max = parseExactIntValue(object.get("max"));
            if (min != null && min.equals(max)) {
                return min;
            }
        }
        return null;
    }


    private static boolean isEnchantLikeFunction(String function) {
        return function.contains("enchant");
    }

    private static boolean affectsDisplayedResult(String function) {
        return switch (function) {
            case "copy_components", "set_lore", "set_nbt", "copy_custom_data", "set_damage",
                    "set_potion", "set_instrument", "set_fireworks", "set_firework_explosion",
                    "set_banner_pattern", "set_book_cover", "set_written_book_pages",
                    "set_writable_book_pages", "toggle_tooltips", "set_custom_model_data" -> true;
            default -> false;
        };
    }

    private static ItemDefinitionBuilder createBuilder(ResolvedEntry resolved,
                                                       @Nullable ResourceLocation sourceChildTable) {
        return new ItemDefinitionBuilder(resolved.itemId(), resolved.displayName(), resolved.tooltipHint(),
                resolved.signature(), sourceChildTable);
    }

    private static Component resolveMergedDisplayName(ResourceLocation itemId, LootResultSignature signature) {
        ItemStack previewStack = signature.createPreviewStack();
        if (previewStack.isEmpty()) {
            previewStack = new ItemStack(BuiltInRegistries.ITEM.get(itemId));
        }
        return resolveItemDisplayName(previewStack);
    }

    @Nullable
    private static Component resolveMergedTooltipHint(LootResultSignature signature) {
        if (signature.isEnchantedVariant()) {
            return Component.translatable(ENCHANTED_HINT_KEY);
        }
        if (signature.type() == LootResultSignature.SignatureType.APPROX_ITEM_ONLY) {
            return Component.translatable(APPROXIMATE_HINT_KEY);
        }
        return null;
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

    private static Component resolveTableName(ResourceLocation tableId) {
        return LootTableNames.resolveDisplayName(tableId);
    }

    private static String normalizeType(String type) {
        if (type == null || type.isEmpty()) {
            return "";
        }

        int colonIndex = type.indexOf(':');
        return colonIndex >= 0 ? type.substring(colonIndex + 1) : type;
    }

    private static String getString(JsonObject object, String key, String fallback) {
        return object.has(key) ? object.get(key).getAsString() : fallback;
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsInt() : fallback;
    }

    private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsBoolean() : fallback;
    }

    private record ResolvedEntry(ResourceLocation itemId, Component displayName,
                                 @Nullable Component tooltipHint, LootResultSignature signature) {
    }

    private static final class ItemDefinitionBuilder {
        private final ResourceLocation id;
        private Component displayName;
        @Nullable
        private Component tooltipHint;
        private final LootResultSignature signature;
        @Nullable
        private final ResourceLocation sourceChildTable;

        private ItemDefinitionBuilder(ResourceLocation id, Component displayName,
                                      @Nullable Component tooltipHint, LootResultSignature signature,
                                      @Nullable ResourceLocation sourceChildTable) {
            this.id = id;
            this.displayName = displayName;
            this.tooltipHint = tooltipHint;
            this.signature = signature;
            this.sourceChildTable = sourceChildTable;
        }

        private void mergeResolved(ResolvedEntry resolved) {
            if (!this.displayName.getString().equals(resolved.displayName().getString())) {
                this.displayName = resolveMergedDisplayName(this.id, this.signature);
            }
            if (this.tooltipHint == null ? resolved.tooltipHint() == null
                    : this.tooltipHint.getString().equals(resolved.tooltipHint() != null ? resolved.tooltipHint().getString() : null)) {
                return;
            }
            this.tooltipHint = resolveMergedTooltipHint(this.signature);
        }

        // 概率占位符 "?"，将在服务端模拟后替换为真实值
        private ItemDefinition build() {
            return new ItemDefinition(this.id, this.displayName, this.tooltipHint, "?", this.signature, this.sourceChildTable);
        }
    }
}
