package com.meteorite.unsuspiciousblock.journal;

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
import net.minecraft.client.Minecraft;
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

public final class ArchaeologyJournalCatalog {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FileToIdConverter LOOT_TABLES = FileToIdConverter.json("loot_table");
    private static final ResourceLocation BOOK_ID = ResourceLocation.fromNamespaceAndPath("minecraft", "book");
    private static final String RANDOM_HINT_KEY = "screen.unsuspiciousblock.archaeology_journal.item_hint.enchanted_random";
    private static final String LEVEL_HINT_KEY = "screen.unsuspiciousblock.archaeology_journal.item_hint.enchanted_level";
    private static final String ENCHANTED_HINT_KEY = "screen.unsuspiciousblock.archaeology_journal.item_hint.enchanted";
    private static final String APPROXIMATE_HINT_KEY = "screen.unsuspiciousblock.archaeology_journal.item_hint.approximate";
    private static final String HINT_WRAPPER_KEY = "screen.unsuspiciousblock.archaeology_journal.item_hint.wrapper";

    private ArchaeologyJournalCatalog() {
    }

    // 从客户端 Minecraft 实例加载目录（委托到 load(ResourceManager)）
    public static Map<ResourceLocation, TableDefinition> load(Minecraft minecraft) {
        return load(minecraft.getResourceManager());
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
            LootTableNames.ensureRegistered(entry.getKey());
            try (BufferedReader reader = entry.getValue().openAsReader()) {
                JsonElement element = JsonParser.parseReader(reader);
                TableDefinition definition = parseTable(entry.getKey(), element);
                if (!definition.items().isEmpty()) {
                    tables.put(entry.getKey(), definition);
                }
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Failed to read archaeology loot table {}", entry.getKey(), exception);
            }
        }

        return tables;
    }

    private static TableDefinition parseTable(ResourceLocation tableId, JsonElement element) {
        LinkedHashMap<String, ItemDefinitionBuilder> items = new LinkedHashMap<>();
        boolean[] approximate = new boolean[1];
        parseNode(element, 1.0D, items, approximate);

        List<ItemDefinition> definitions = new ArrayList<>();
        for (ItemDefinitionBuilder builder : items.values()) {
            definitions.add(builder.build());
        }
        definitions.sort(Comparator
                .comparing((ItemDefinition definition) -> definition.id().toString())
                .thenComparing(definition -> definition.signature().toStoredKey()));

        double totalWeight = definitions.stream().mapToDouble(ItemDefinition::weight).sum();
        return new TableDefinition(tableId, resolveTableName(tableId), definitions, totalWeight, approximate[0]);
    }

    private static void parseNode(JsonElement element, double weightMultiplier, Map<String, ItemDefinitionBuilder> items, boolean[] approximate) {
        if (element == null || element.isJsonNull()) {
            return;
        }

        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                parseNode(child, weightMultiplier, items, approximate);
            }
            return;
        }

        if (!element.isJsonObject()) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        if (object.has("pools") && parseArrayIfPresent(object, "pools", weightMultiplier, items, approximate)) {
            return;
        }

        if (object.has("entries") && parseArrayIfPresent(object, "entries", weightMultiplier, items, approximate)) {
            return;
        }

        parseEntry(object, weightMultiplier, items, approximate);
    }

    private static void parseArray(JsonArray array, double weightMultiplier, Map<String, ItemDefinitionBuilder> items, boolean[] approximate) {
        for (JsonElement child : array) {
            parseNode(child, weightMultiplier, items, approximate);
        }
    }

    private static boolean parseArrayIfPresent(JsonObject object, String key, double weightMultiplier,
                                               Map<String, ItemDefinitionBuilder> items, boolean[] approximate) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            return false;
        }

        parseArray(object.getAsJsonArray(key), weightMultiplier, items, approximate);
        return true;
    }

    private static void parseEntry(JsonObject object, double weightMultiplier,
                                   Map<String, ItemDefinitionBuilder> items, boolean[] approximate) {
        String type = getString(object, "type", "");
        double effectiveWeight = Math.max(0, getInt(object, "weight", 1)) * Math.max(0.0D, weightMultiplier);
        boolean complex = object.has("conditions") || object.has("bonus_rolls") || object.has("rolls");

        switch (normalizeType(type)) {
            case "item" -> {
                if (complex) {
                    approximate[0] = true;
                }
                parseItem(object, effectiveWeight, items, approximate);
            }
            case "tag" -> {
                if (complex) {
                    approximate[0] = true;
                }
                parseTag(object, effectiveWeight, items, approximate);
            }
            case "group", "alternatives", "sequence" -> {
                approximate[0] = true;
                parseArrayIfPresent(object, "children", effectiveWeight, items, approximate);
            }
            default -> {
                approximate[0] = true;
                if (!parseArrayIfPresent(object, "children", effectiveWeight, items, approximate)) {
                    parseArrayIfPresent(object, "entries", effectiveWeight, items, approximate);
                }
            }
        }
    }

    private static void parseItem(JsonObject object, double weight, Map<String, ItemDefinitionBuilder> items, boolean[] approximate) {
        ResourceLocation itemId = ResourceLocation.tryParse(getString(object, "name", ""));
        if (itemId == null || BuiltInRegistries.ITEM.get(itemId) == Items.AIR) {
            return;
        }

        ResolvedEntry resolved = resolveEntry(itemId, object, approximate);
        ItemDefinitionBuilder builder = items.computeIfAbsent(resolved.signature().toStoredKey(),
                ignored -> createBuilder(resolved));
        builder.mergeResolved(resolved);
        builder.addWeight(weight);
    }

    private static void parseTag(JsonObject object, double weight, Map<String, ItemDefinitionBuilder> items, boolean[] approximate) {
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

        double memberWeight = expand ? weight : weight / itemIds.size();
        for (ResourceLocation itemId : itemIds) {
            ResolvedEntry resolved = resolveEntry(itemId, object, approximate);
            ItemDefinitionBuilder builder = items.computeIfAbsent(resolved.signature().toStoredKey(),
                    ignored -> createBuilder(resolved));
            builder.mergeResolved(resolved);
            builder.addWeight(memberWeight);
        }
    }

    private static ResolvedEntry resolveEntry(ResourceLocation baseItemId, JsonObject object, boolean[] approximate) {
        ItemStack previewStack = new ItemStack(BuiltInRegistries.ITEM.get(baseItemId));
        LootResultSignature signature = LootResultSignature.plain(baseItemId);
        @Nullable Component hint = null;
        boolean entryApproximate = false;

        if (object.has("functions") && object.get("functions").isJsonArray()) {
            for (JsonElement functionElement : object.getAsJsonArray("functions")) {
                if (!functionElement.isJsonObject()) {
                    entryApproximate = true;
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
                            entryApproximate = true;
                        }
                    }
                    case "enchant_randomly" -> {
                        previewStack = promoteBookPreviewIfNeeded(previewStack);
                        signature = LootResultSignature.enchantedApprox(currentItemId(previewStack));
                        hint = Component.translatable(RANDOM_HINT_KEY);
                    }
                    case "enchant_with_levels" -> {
                        previewStack = promoteBookPreviewIfNeeded(previewStack);
                        String levelInfo = parseLevelInfo(functionObject.get("levels"));
                        signature = LootResultSignature.enchantedApprox(currentItemId(previewStack));
                        hint = levelInfo != null
                                ? Component.translatable(LEVEL_HINT_KEY, levelInfo)
                                : Component.translatable(RANDOM_HINT_KEY);
                    }
                    case "set_enchantments" -> {
                        ItemStack resolvedStack = applyFixedEnchantments(previewStack, functionObject);
                        if (resolvedStack != null) {
                            previewStack = resolvedStack;
                            signature = LootResultSignature.plain(currentItemId(previewStack));
                        } else {
                            previewStack = promoteBookPreviewIfNeeded(previewStack);
                            signature = LootResultSignature.enchantedApprox(currentItemId(previewStack));
                            if (hint == null) {
                                hint = Component.translatable(ENCHANTED_HINT_KEY);
                            }
                            entryApproximate = true;
                        }
                    }
                    case "set_components" -> {
                        if (!applyExactComponents(previewStack, functionObject.get("components"))) {
                            entryApproximate = true;
                        }
                    }
                    case "set_custom_data" -> {
                        if (!applyCustomData(previewStack, functionObject.get("tag"))) {
                            entryApproximate = true;
                        }
                    }
                    case "set_name" -> {
                        if (!applySetName(previewStack, functionObject)) {
                            entryApproximate = true;
                        }
                    }
                    default -> {
                        if (isEnchantLikeFunction(function)) {
                            previewStack = promoteBookPreviewIfNeeded(previewStack);
                            signature = LootResultSignature.enchantedApprox(currentItemId(previewStack));
                            if (hint == null) {
                                hint = Component.translatable(ENCHANTED_HINT_KEY);
                            }
                            entryApproximate = true;
                        } else if (affectsDisplayedResult(function)) {
                            entryApproximate = true;
                        }
                    }
                }
            }
        }

        if (entryApproximate) {
            approximate[0] = true;
        }
        if (!entryApproximate && signature.type() == LootResultSignature.SignatureType.PLAIN
                && !previewStack.getComponentsPatch().isEmpty()) {
            signature = LootResultSignature.componentExact(previewStack);
        } else if (entryApproximate && signature.type() == LootResultSignature.SignatureType.PLAIN) {
            signature = LootResultSignature.approximateItemOnly(currentItemId(previewStack), "function");
        }

        Component displayName = resolveItemDisplayName(previewStack, hint, entryApproximate && hint == null);
        return new ResolvedEntry(currentItemId(previewStack), displayName, signature);
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
    private static ItemStack applyFixedEnchantments(ItemStack previewStack, JsonObject functionObject) {
        // 附魔注册表属于动态注册表，这里在缺少注册表上下文时不做伪精确推导。
        // 后续若目录加载链路补入 RegistryAccess，可在此恢复显式附魔的严格静态展开。
        return null;
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

    @Nullable
    private static JsonObject getJsonObject(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(key);
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

    private static ItemDefinitionBuilder createBuilder(ResolvedEntry resolved) {
        return new ItemDefinitionBuilder(resolved.itemId(), resolved.displayName(), resolved.signature());
    }

    private static Component resolveMergedDisplayName(ResourceLocation itemId, LootResultSignature signature) {
        ItemStack previewStack = signature.createPreviewStack();
        if (previewStack.isEmpty()) {
            previewStack = new ItemStack(BuiltInRegistries.ITEM.get(itemId));
        }
        if (signature.isEnchantedVariant()) {
            return resolveItemDisplayName(previewStack, Component.translatable(ENCHANTED_HINT_KEY), false);
        }
        return resolveItemDisplayName(previewStack, null,
                signature.type() == LootResultSignature.SignatureType.APPROX_ITEM_ONLY);
    }

    private static Component resolveItemDisplayName(ItemStack previewStack, @Nullable Component hint, boolean showApproximate) {
        Component baseName = previewStack.isEmpty()
                ? Component.translatable("screen.unsuspiciousblock.archaeology_journal.unknown_entry")
                : previewStack.getHoverName().copy();
        if (hint != null) {
            return baseName.copy().append(Component.literal(" ")).append(Component.translatable(HINT_WRAPPER_KEY, hint));
        }
        if (showApproximate) {
            return baseName.copy().append(Component.literal(" "))
                    .append(Component.translatable(HINT_WRAPPER_KEY, Component.translatable(APPROXIMATE_HINT_KEY)));
        }
        return baseName;
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

    private record ResolvedEntry(ResourceLocation itemId, Component displayName, LootResultSignature signature) {
    }

    private static final class ItemDefinitionBuilder {
        private final ResourceLocation id;
        private Component displayName;
        private final LootResultSignature signature;
        private double weight;

        private ItemDefinitionBuilder(ResourceLocation id, Component displayName, LootResultSignature signature) {
            this.id = id;
            this.displayName = displayName;
            this.signature = signature;
        }

        private void mergeResolved(ResolvedEntry resolved) {
            if (this.displayName.getString().equals(resolved.displayName().getString())) {
                return;
            }
            this.displayName = resolveMergedDisplayName(this.id, this.signature);
        }

        private void addWeight(double weight) {
            this.weight += weight;
        }

        private ItemDefinition build() {
            return new ItemDefinition(this.id, this.displayName, this.weight, this.signature);
        }
    }
}
