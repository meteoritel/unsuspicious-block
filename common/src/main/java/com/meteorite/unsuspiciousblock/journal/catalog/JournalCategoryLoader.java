package com.meteorite.unsuspiciousblock.journal.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.CatalogCategoryDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 考古手册目录分类加载器——从 Data Pack 读取 type 映射与高优先级特殊判定。
 */
final class JournalCategoryLoader {
    private static final String DIRECTORY = "journal_categories";
    private static final ResourceLocation OTHER_ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "other");

    private JournalCategoryLoader() {
    }

    static CategorySet load(ResourceManager manager) {
        List<CategoryRule> loaded = new ArrayList<>();
        Map<ResourceLocation, Resource> resources = manager.listResources(
                DIRECTORY, id -> id.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation categoryId = fileToCategoryId(entry.getKey());
            try (Reader reader = entry.getValue().openAsReader()) {
                loaded.add(parse(categoryId, JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (Exception exception) {
                Constants.LOG.error("无法加载考古手册分类资源 {}", entry.getKey(), exception);
            }
        }
        loaded.sort(Comparator.comparingInt((CategoryRule value) -> value.definition().order())
                .thenComparing(value -> value.definition().id().toString()));
        if (loaded.stream().noneMatch(value -> value.definition().id().equals(OTHER_ID))) {
            Constants.LOG.warn("考古手册分类缺少兜底分类 {}", OTHER_ID);
        }
        return new CategorySet(List.copyOf(loaded));
    }

    private static ResourceLocation fileToCategoryId(ResourceLocation fileId) {
        String path = fileId.getPath();
        String prefix = DIRECTORY + "/";
        String value = path.startsWith(prefix) ? path.substring(prefix.length()) : path;
        if (value.endsWith(".json")) {
            value = value.substring(0, value.length() - 5);
        }
        return ResourceLocation.fromNamespaceAndPath(fileId.getNamespace(), value);
    }

    private static CategoryRule parse(ResourceLocation id, JsonObject object) {
        CatalogCategoryDefinition definition = new CatalogCategoryDefinition(
                id,
                string(object, "translation_key", ""),
                string(object, "fallback", id.getPath()),
                string(object, "description_key", ""),
                string(object, "description_fallback", ""),
                parseId(string(object, "icon", "minecraft:book"), "minecraft:book"),
                integer(object, "order", 0));
        Set<ResourceLocation> types = parseIds(object.getAsJsonArray("types"));
        List<SpecialRule> specials = new ArrayList<>();
        JsonArray array = object.getAsJsonArray("special_rules");
        if (array != null) {
            for (JsonElement element : array) {
                specials.add(parseSpecial(element.getAsJsonObject()));
            }
        }
        return new CategoryRule(definition, Set.copyOf(types), List.copyOf(specials),
                integer(object, "priority", 0));
    }

    private static SpecialRule parseSpecial(JsonObject object) {
        ResourceLocation type = object.has("type")
                ? ResourceLocation.tryParse(object.get("type").getAsString()) : null;
        return new SpecialRule(
                type,
                parseIds(object.getAsJsonArray("table_ids")),
                parseStrings(object.getAsJsonArray("namespaces")),
                parseStrings(object.getAsJsonArray("path_prefixes")),
                parseIds(object.getAsJsonArray("exclude_table_ids")),
                parseStrings(object.getAsJsonArray("exclude_path_prefixes")),
                integer(object, "priority", 0));
    }

    private static Set<ResourceLocation> parseIds(JsonArray array) {
        Set<ResourceLocation> result = new HashSet<>();
        if (array == null) return result;
        for (JsonElement element : array) {
            ResourceLocation id = ResourceLocation.tryParse(element.getAsString());
            if (id != null) result.add(id);
        }
        return result;
    }

    private static Set<String> parseStrings(JsonArray array) {
        Set<String> result = new HashSet<>();
        if (array != null) array.forEach(element -> result.add(element.getAsString()));
        return result;
    }

    private static ResourceLocation parseId(String value, String fallback) {
        ResourceLocation parsed = ResourceLocation.tryParse(value);
        return parsed != null ? parsed : ResourceLocation.parse(fallback);
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) ? object.get(key).getAsString() : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsInt() : fallback;
    }

    record CategorySet(List<CategoryRule> rules) {
        List<CatalogCategoryDefinition> definitions() {
            return this.rules.stream().map(CategoryRule::definition).toList();
        }

        ResourceLocation classify(ResourceLocation tableId, String declaredType) {
            ResourceLocation type = ResourceLocation.tryParse(declaredType);
            List<Match> specialMatches = new ArrayList<>();
            for (CategoryRule category : this.rules) {
                for (SpecialRule special : category.specials()) {
                    if (special.matches(tableId, type)) {
                        specialMatches.add(new Match(category.definition().id(), special.priority()));
                    }
                }
            }
            if (!specialMatches.isEmpty()) {
                specialMatches.sort(Comparator.comparingInt(Match::priority).reversed()
                        .thenComparing(value -> value.categoryId().toString()));
                warnTie(tableId, specialMatches);
                return specialMatches.getFirst().categoryId();
            }

            List<Match> typeMatches = this.rules.stream()
                    .filter(rule -> type != null && rule.types().contains(type))
                    .map(rule -> new Match(rule.definition().id(), rule.priority()))
                    .sorted(Comparator.comparingInt(Match::priority).reversed()
                            .thenComparing(value -> value.categoryId().toString()))
                    .toList();
            if (!typeMatches.isEmpty()) {
                if (typeMatches.size() > 1 && typeMatches.get(0).priority() == typeMatches.get(1).priority()) {
                    Constants.LOG.warn("战利品表 {} 的 type {} 同时匹配多个目录分类 {}，使用 {}",
                            tableId, type, typeMatches, typeMatches.getFirst().categoryId());
                }
                return typeMatches.getFirst().categoryId();
            }
            if (type == null) {
                Constants.LOG.warn("战利品表 {} 缺少或使用无效 type '{}'，归入其他", tableId, declaredType);
            }
            return OTHER_ID;
        }

        private static void warnTie(ResourceLocation tableId, List<Match> matches) {
            if (matches.size() > 1 && matches.get(0).priority() == matches.get(1).priority()) {
                Constants.LOG.warn("战利品表 {} 同时命中平级特殊分类规则 {}，使用 {}",
                        tableId, matches, matches.getFirst().categoryId());
            }
        }
    }

    private record CategoryRule(CatalogCategoryDefinition definition, Set<ResourceLocation> types,
                                List<SpecialRule> specials, int priority) {
    }

    private record SpecialRule(ResourceLocation type, Set<ResourceLocation> tableIds, Set<String> namespaces,
                               Set<String> pathPrefixes, Set<ResourceLocation> excludedTableIds,
                               Set<String> excludedPathPrefixes, int priority) {
        boolean matches(ResourceLocation tableId, ResourceLocation declaredType) {
            if (this.type != null && !this.type.equals(declaredType)) return false;
            if (this.excludedTableIds.contains(tableId)
                    || this.excludedPathPrefixes.stream().anyMatch(tableId.getPath()::startsWith)) return false;
            return (this.tableIds.isEmpty() || this.tableIds.contains(tableId))
                    && (this.namespaces.isEmpty() || this.namespaces.contains(tableId.getNamespace()))
                    && (this.pathPrefixes.isEmpty()
                    || this.pathPrefixes.stream().anyMatch(tableId.getPath()::startsWith));
        }
    }

    private record Match(ResourceLocation categoryId, int priority) {
    }
}
