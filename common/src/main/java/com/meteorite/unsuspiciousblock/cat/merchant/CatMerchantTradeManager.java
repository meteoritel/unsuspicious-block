package com.meteorite.unsuspiciousblock.cat.merchant;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * 猫猫商人交易加载与抽取器——每次生成都从当前 Datapack 资源重新读取，天然响应 `/reload`。
 */
public final class CatMerchantTradeManager {
    private static final String DIRECTORY = "cat_merchant_trades";

    private CatMerchantTradeManager() {
    }

    public static MerchantOffers createOffers(ServerLevel level, RandomSource random, int catBond) {
        Map<CatMerchantTradePool, List<CatMerchantTradeDefinition>> pools = load(level.getServer().getResourceManager());
        MerchantOffers offers = new MerchantOffers();
        addWeightedOffers(level, random, filter(level, catBond, pools.get(CatMerchantTradePool.EARNING)), 1, offers);
        addWeightedOffers(level, random, filter(level, catBond, pools.get(CatMerchantTradePool.REGULAR)), 3, offers);
        addWeightedOffers(level, random, filter(level, catBond, pools.get(CatMerchantTradePool.RARE)), 2, offers);
        return offers;
    }

    private static List<CatMerchantTradeDefinition> filter(ServerLevel level, int catBond,
                                                           List<CatMerchantTradeDefinition> entries) {
        if (entries == null) {
            return List.of();
        }
        ResourceLocation dimension = level.dimension().location();
        return entries.stream()
                .filter(entry -> entry.conditions().matches(catBond, dimension))
                .toList();
    }

    private static Map<CatMerchantTradePool, List<CatMerchantTradeDefinition>> load(ResourceManager manager) {
        Map<CatMerchantTradePool, List<CatMerchantTradeDefinition>> result = new EnumMap<>(CatMerchantTradePool.class);
        for (CatMerchantTradePool pool : CatMerchantTradePool.values()) {
            result.put(pool, new ArrayList<>());
        }
        Map<ResourceLocation, Resource> resources = manager.listResources(
                DIRECTORY, id -> id.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonElement root = JsonParser.parseReader(reader);
                if (!root.isJsonArray()) {
                    throw new IllegalArgumentException("根节点必须是数组");
                }
                for (JsonElement element : root.getAsJsonArray()) {
                    CatMerchantTradeDefinition definition = parseDefinition(element.getAsJsonObject());
                    result.get(definition.pool()).add(definition);
                }
            } catch (Exception exception) {
                Constants.LOG.error("无法加载猫猫商人交易资源 {}", entry.getKey(), exception);
            }
        }
        return result;
    }

    private static CatMerchantTradeDefinition parseDefinition(JsonObject object) {
        CatMerchantTradePool pool = CatMerchantTradePool.parse(object.get("pool").getAsString());
        int weight = Math.max(1, object.has("weight") ? object.get("weight").getAsInt() : 1);
        int maxUses = Math.max(1, object.has("max_uses") ? object.get("max_uses").getAsInt() : 1);
        return new CatMerchantTradeDefinition(
                pool,
                weight,
                maxUses,
                parseIngredient(object.getAsJsonObject("buy")),
                parseIngredient(object.getAsJsonObject("sell")),
                parseConditions(object.has("conditions")
                        ? object.getAsJsonObject("conditions") : null));
    }

    private static CatMerchantTradeDefinition.TradeConditions parseConditions(JsonObject object) {
        if (object == null) {
            return new CatMerchantTradeDefinition.TradeConditions(0, 100, Set.of());
        }
        int minimumBond = Math.max(0, object.has("min_cat_bond")
                ? object.get("min_cat_bond").getAsInt() : 0);
        int maximumBond = Math.min(100, object.has("max_cat_bond")
                ? object.get("max_cat_bond").getAsInt() : 100);
        Set<ResourceLocation> dimensions = new HashSet<>();
        if (object.has("dimensions")) {
            for (JsonElement element : object.getAsJsonArray("dimensions")) {
                ResourceLocation dimension = ResourceLocation.tryParse(element.getAsString());
                if (dimension == null) {
                    throw new IllegalArgumentException("无效维度 ID: " + element.getAsString());
                }
                dimensions.add(dimension);
            }
        }
        if (minimumBond > maximumBond) {
            throw new IllegalArgumentException("min_cat_bond 不能大于 max_cat_bond");
        }
        return new CatMerchantTradeDefinition.TradeConditions(
                minimumBond, maximumBond, Set.copyOf(dimensions));
    }

    private static CatMerchantTradeDefinition.TradeIngredient parseIngredient(JsonObject object) {
        boolean tag = object.has("tag");
        String rawId = tag ? object.get("tag").getAsString() : object.get("item").getAsString();
        ResourceLocation id = ResourceLocation.tryParse(rawId);
        if (id == null) {
            throw new IllegalArgumentException("无效物品或 Tag ID: " + rawId);
        }
        int count = Math.max(1, object.has("count") ? object.get("count").getAsInt() : 1);
        return new CatMerchantTradeDefinition.TradeIngredient(id, count, tag);
    }

    private static void addWeightedOffers(ServerLevel level, RandomSource random,
                                          List<CatMerchantTradeDefinition> source, int count,
                                          MerchantOffers output) {
        if (source == null || source.isEmpty()) {
            return;
        }
        List<CatMerchantTradeDefinition> available = new ArrayList<>(source);
        for (int i = 0; i < count && !available.isEmpty(); i++) {
            CatMerchantTradeDefinition selected = removeWeighted(available, random);
            MerchantOffer offer = createOffer(level, random, selected);
            if (offer != null) {
                output.add(offer);
            }
        }
    }

    private static CatMerchantTradeDefinition removeWeighted(List<CatMerchantTradeDefinition> entries,
                                                              RandomSource random) {
        int totalWeight = entries.stream().mapToInt(CatMerchantTradeDefinition::weight).sum();
        int roll = random.nextInt(totalWeight);
        for (int i = 0; i < entries.size(); i++) {
            CatMerchantTradeDefinition entry = entries.get(i);
            roll -= entry.weight();
            if (roll < 0) {
                entries.remove(i);
                return entry;
            }
        }
        return entries.removeLast();
    }

    private static MerchantOffer createOffer(ServerLevel level, RandomSource random,
                                             CatMerchantTradeDefinition definition) {
        Registry<Item> items = level.registryAccess().registryOrThrow(Registries.ITEM);
        ResolvedIngredient buy = resolve(items, definition.buy(), random);
        ResolvedIngredient sell = resolve(items, definition.sell(), random);
        if (buy == null || sell == null) {
            return null;
        }
        ItemCost displayedCost = new ItemCost(buy.item(), definition.buy().count());
        ItemStack result = new ItemStack(sell.item(), definition.sell().count());
        if (definition.buy().tag()) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, definition.buy().id());
            return new TaggedMerchantOffer(
                    displayedCost, result, definition.maxUses(), tag, definition.buy().count());
        }
        return new MerchantOffer(displayedCost, result, definition.maxUses(), 0, 0.05F);
    }

    private static ResolvedIngredient resolve(Registry<Item> items,
                                              CatMerchantTradeDefinition.TradeIngredient ingredient,
                                              RandomSource random) {
        if (!ingredient.tag()) {
            Item item = items.get(ingredient.id());
            return item == null ? null : new ResolvedIngredient(item);
        }
        TagKey<Item> tag = TagKey.create(Registries.ITEM, ingredient.id());
        List<Holder<Item>> values = items.getTag(tag)
                .map(named -> named.stream().toList())
                .orElseGet(List::of);
        if (values.isEmpty()) {
            Constants.LOG.warn("猫猫商人交易引用了空物品 Tag: {}", ingredient.id());
            return null;
        }
        return new ResolvedIngredient(values.get(random.nextInt(values.size())).value());
    }

    private record ResolvedIngredient(Item item) {
    }
}
