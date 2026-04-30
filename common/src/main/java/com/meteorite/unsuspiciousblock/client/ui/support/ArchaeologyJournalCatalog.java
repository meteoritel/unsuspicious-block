package com.meteorite.unsuspiciousblock.client.ui.support;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ArchaeologyJournalCatalog {
    private static final Logger LOGGER = LogUtils.getLogger();
    // 以 "loot_table" 为前缀（Minecraft 数据包目录使用单数形式），通过 fileToId 转换为标准战利品表 ID
    private static final FileToIdConverter LOOT_TABLES = FileToIdConverter.json("loot_table");

    private ArchaeologyJournalCatalog() {
    }

    // 从客户端 Minecraft 实例加载目录（委托到 load(ResourceManager)）
    public static Map<ResourceLocation, TableDefinition> load(Minecraft minecraft) {
        return load(minecraft.getResourceManager());
    }

    // 从任意 ResourceManager 加载目录（客户端或服务端均可使用）
    public static Map<ResourceLocation, TableDefinition> load(ResourceManager resourceManager) {
        Map<ResourceLocation, Resource> allResources = LOOT_TABLES.listMatchingResources(resourceManager);
        // 转换为标准战利品表 ID（去掉 "loot_table/" 前缀和 ".json" 后缀），并仅保留 archaeology 子目录
        List<Map.Entry<ResourceLocation, Resource>> orderedResources = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Resource> entry : allResources.entrySet()) {
            ResourceLocation tableId = LOOT_TABLES.fileToId(entry.getKey());
            if (tableId.getPath().startsWith("archaeology/")) {
                orderedResources.add(Map.entry(tableId, entry.getValue()));
            }
        }
        orderedResources.sort(Comparator.comparing(entry -> entry.getKey().toString()));

        LinkedHashMap<ResourceLocation, TableDefinition> tables = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Resource> entry : orderedResources) {
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
        LinkedHashMap<ResourceLocation, ItemDefinitionBuilder> items = new LinkedHashMap<>();
        boolean[] approximate = new boolean[1];
        parseNode(element, 1.0D, items, approximate);

        List<ItemDefinition> definitions = new ArrayList<>();
        for (ItemDefinitionBuilder builder : items.values()) {
            definitions.add(builder.build());
        }
        definitions.sort(Comparator.comparing(definition -> definition.id().toString()));

        double totalWeight = definitions.stream().mapToDouble(ItemDefinition::weight).sum();
        return new TableDefinition(tableId, resolveTableName(tableId), definitions, totalWeight, approximate[0]);
    }

    private static void parseNode(JsonElement element, double weightMultiplier, Map<ResourceLocation, ItemDefinitionBuilder> items, boolean[] approximate) {
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
        if (object.has("pools")) {
            approximate[0] = true;
            if (parseArrayIfPresent(object, "pools", weightMultiplier, items, approximate)) {
                return;
            }
        }

        if (object.has("entries")) {
            approximate[0] = true;
            if (parseArrayIfPresent(object, "entries", weightMultiplier, items, approximate)) {
                return;
            }
        }

        parseEntry(object, weightMultiplier, items, approximate);
    }

    private static void parseArray(JsonArray array, double weightMultiplier, Map<ResourceLocation, ItemDefinitionBuilder> items, boolean[] approximate) {
        for (JsonElement child : array) {
            parseNode(child, weightMultiplier, items, approximate);
        }
    }

    private static boolean parseArrayIfPresent(JsonObject object, String key, double weightMultiplier, Map<ResourceLocation, ItemDefinitionBuilder> items, boolean[] approximate) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            return false;
        }

        parseArray(object.getAsJsonArray(key), weightMultiplier, items, approximate);
        return true;
    }

    private static void parseEntry(JsonObject object, double weightMultiplier, Map<ResourceLocation, ItemDefinitionBuilder> items, boolean[] approximate) {
        String type = getString(object, "type", "");
        double effectiveWeight = Math.max(0, getInt(object, "weight", 1)) * Math.max(0.0D, weightMultiplier);
        boolean complex = object.has("conditions") || object.has("bonus_rolls") || object.has("rolls");

        switch (normalizeType(type)) {
            case "item" -> {
                if (complex) {
                    approximate[0] = true;
                }
                parseItem(object, effectiveWeight, items);
            }
            case "tag" -> {
                if (complex) {
                    approximate[0] = true;
                }
                parseTag(object, effectiveWeight, items);
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

    private static void parseItem(JsonObject object, double weight, Map<ResourceLocation, ItemDefinitionBuilder> items) {
        ResourceLocation itemId = ResourceLocation.tryParse(getString(object, "name", ""));
        if (itemId == null) {
            return;
        }

        if (BuiltInRegistries.ITEM.get(itemId) == Items.AIR) {
            return;
        }

        ItemDefinitionBuilder builder = items.computeIfAbsent(itemId, ArchaeologyJournalCatalog::createBuilder);
        builder.addWeight(weight);
    }

    private static void parseTag(JsonObject object, double weight, Map<ResourceLocation, ItemDefinitionBuilder> items) {
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

            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);

            itemIds.add(itemId);
        }

        if (itemIds.isEmpty()) {
            return;
        }

        double memberWeight = expand ? weight : weight / itemIds.size();
        for (ResourceLocation itemId : itemIds) {
            ItemDefinitionBuilder builder = items.computeIfAbsent(itemId, ArchaeologyJournalCatalog::createBuilder);
            builder.addWeight(memberWeight);
        }
    }

    private static ItemDefinitionBuilder createBuilder(ResourceLocation itemId) {
        Item item = BuiltInRegistries.ITEM.get(itemId);
        return new ItemDefinitionBuilder(itemId, new ItemStack(item).getHoverName().copy());
    }

    private static Component resolveTableName(ResourceLocation tableId) {
        String key = tableNameKey(tableId);
        if (key != null) {
            return Component.translatable(key);
        }

        return Component.literal(humanize(tableId.getPath()));
    }

    private static String tableNameKey(ResourceLocation tableId) {
        return switch (tableId.toString()) {
            case "minecraft:archaeology/desert_pyramid" -> "screen.unsuspiciousblock.archaeology_journal.table.desert_pyramid";
            case "minecraft:archaeology/desert_well" -> "screen.unsuspiciousblock.archaeology_journal.table.desert_well";
            case "minecraft:archaeology/ocean_ruin_cold" -> "screen.unsuspiciousblock.archaeology_journal.table.ocean_ruin_cold";
            case "minecraft:archaeology/ocean_ruin_warm" -> "screen.unsuspiciousblock.archaeology_journal.table.ocean_ruin_warm";
            case "minecraft:archaeology/trail_ruins_common" -> "screen.unsuspiciousblock.archaeology_journal.table.trail_ruins_common";
            case "minecraft:archaeology/trail_ruins_rare" -> "screen.unsuspiciousblock.archaeology_journal.table.trail_ruins_rare";
            default -> null;
        };
    }

    private static String humanize(String path) {
        String normalized = path.replace('/', ' ').replace('_', ' ').trim();
        if (normalized.isEmpty()) {
            return path;
        }

        StringBuilder builder = new StringBuilder();
        for (String part : normalized.split("\\s+")) {
            if (part.isEmpty()) {
                continue;
            }

            if (!builder.isEmpty()) {
                builder.append(' ');
            }

            if (part.length() == 1) {
                builder.append(part.toUpperCase(Locale.ROOT));
            } else {
                builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return builder.toString();
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
        return object.has(key) ? object.get(key).getAsBoolean() : fallback;
    }

    public record TableDefinition(ResourceLocation id, Component displayName, List<ItemDefinition> items, double totalWeight, boolean approximate) {
    }

    public record ItemDefinition(ResourceLocation id, Component displayName, double weight) {
    }

    private static final class ItemDefinitionBuilder {
        private final ResourceLocation id;
        private final Component displayName;
        private double weight;

        private ItemDefinitionBuilder(ResourceLocation id, Component displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        private void addWeight(double weight) {
            this.weight += weight;
        }

        private ItemDefinition build() {
            return new ItemDefinition(this.id, this.displayName, this.weight);
        }
    }
}
