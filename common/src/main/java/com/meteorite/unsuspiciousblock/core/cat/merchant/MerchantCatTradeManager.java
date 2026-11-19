package com.meteorite.unsuspiciousblock.core.cat.merchant;

import com.meteorite.unsuspiciousblock.core.cat.CatBondStage;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meteorite.unsuspiciousblock.Constants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 解析当前数据包的猫猫商人报价，并按权重无放回抽取有效候选。
 */
public final class MerchantCatTradeManager {
    private static final String DIRECTORY = "merchant_cat_trades";
    private static TradeCatalog catalog;

    private MerchantCatTradeManager() {
    }

    /** 按世界与羁绊条件为新商人生成报价。 */
    public static MerchantOffers createOffers(ServerLevel level, RandomSource random, int catBond) {
        Map<MerchantCatTradePool, List<PreparedTrade>> pools = currentPools(level);
        MerchantOffers offers = new MerchantOffers();
        ResourceLocation dimension = level.dimension().location();
        addWeightedOffers(random, candidates(pools.get(MerchantCatTradePool.EARNING), catBond, dimension), 1, offers);
        addWeightedOffers(random, candidates(pools.get(MerchantCatTradePool.REGULAR), catBond, dimension), 3, offers);
        addWeightedOffers(random, candidates(pools.get(MerchantCatTradePool.RARE), catBond, dimension), 2, offers);
        return offers;
    }

    /** 服务器结束时释放该次世界的资源与物品标签。 */
    public static void stop(MinecraftServer server) {
        catalog = null;
    }

    private static List<PreparedTrade> candidates(List<PreparedTrade> entries, int catBond, ResourceLocation dimension) {
        return entries.stream()
                .filter(entry -> entry.definition().conditions().matches(catBond, dimension))
                .toList();
    }

    private static Map<MerchantCatTradePool, List<PreparedTrade>> currentPools(ServerLevel level) {
        MinecraftServer server = level.getServer();
        ReloadableServerRegistries.Holder registries = server.reloadableRegistries();
        if (catalog == null || catalog.registries() != registries) {
            Registry<Item> items = level.registryAccess().registryOrThrow(Registries.ITEM);
            catalog = new TradeCatalog(registries, load(server.getResourceManager(), items));
        }
        return catalog.pools();
    }

    private static Map<MerchantCatTradePool, List<PreparedTrade>> load(ResourceManager manager, Registry<Item> items) {
        Map<MerchantCatTradePool, List<PreparedTrade>> result = new EnumMap<>(MerchantCatTradePool.class);
        for (MerchantCatTradePool pool : MerchantCatTradePool.values()) {
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
                    try {
                        MerchantCatTradeDefinition definition = parseDefinition(element.getAsJsonObject());
                        result.get(definition.pool()).add(new PreparedTrade(definition,
                                resolve(items, definition.buy()), resolve(items, definition.sell())));
                    } catch (RuntimeException exception) {
                        Constants.LOG.warn("{}忽略无效猫猫商人交易 {}: {}", Constants.LOG_TAG,
                                entry.getKey(), exception.getMessage());
                    }
                }
            } catch (IOException | RuntimeException exception) {
                Constants.LOG.error("{}无法加载猫猫商人交易资源 {}", Constants.LOG_TAG, entry.getKey(), exception);
            }
        }
        result.replaceAll((pool, entries) -> List.copyOf(entries));
        return Map.copyOf(result);
    }

    private static MerchantCatTradeDefinition parseDefinition(JsonObject object) {
        MerchantCatTradePool pool = MerchantCatTradePool.parse(object.get("pool").getAsString());
        int weight = Math.max(1, object.has("weight") ? object.get("weight").getAsBigDecimal().intValueExact() : 1);
        int maxUses = Math.max(1, object.has("max_uses") ? object.get("max_uses").getAsInt() : 1);
        return new MerchantCatTradeDefinition(
                pool,
                weight,
                maxUses,
                parseIngredient(object.getAsJsonObject("buy")),
                parseIngredient(object.getAsJsonObject("sell")),
                parseConditions(object.has("conditions")
                        ? object.getAsJsonObject("conditions") : null));
    }

    private static MerchantCatTradeDefinition.TradeConditions parseConditions(JsonObject object) {
        if (object == null) {
            return new MerchantCatTradeDefinition.TradeConditions(CatBondStage.ACQUAINTED.minimumBond(), CatBondStage.BEST_FRIEND.maximumBond(), Set.of());
        }
        int minimumBond = Math.max(0, object.has("min_cat_bond")
                ? object.get("min_cat_bond").getAsInt() : 0);
        int maximumBond = Math.min(CatBondStage.BEST_FRIEND.maximumBond(), object.has("max_cat_bond")
                ? object.get("max_cat_bond").getAsInt() : CatBondStage.BEST_FRIEND.maximumBond());
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
        return new MerchantCatTradeDefinition.TradeConditions(
                minimumBond, maximumBond, Set.copyOf(dimensions));
    }

    private static MerchantCatTradeDefinition.TradeIngredient parseIngredient(JsonObject object) {
        boolean tag = object.has("tag");
        String rawId = tag ? object.get("tag").getAsString() : object.get("item").getAsString();
        ResourceLocation id = ResourceLocation.tryParse(rawId);
        if (id == null) {
            throw new IllegalArgumentException("无效物品或 Tag ID: " + rawId);
        }
        int count = Math.max(1, object.has("count") ? object.get("count").getAsInt() : 1);
        return new MerchantCatTradeDefinition.TradeIngredient(id, count, tag);
    }

    private static void addWeightedOffers(RandomSource random, List<PreparedTrade> source,
                                          int count, MerchantOffers output) {
        List<PreparedTrade> available = new ArrayList<>(source);
        long totalWeight = available.stream().mapToLong(trade -> trade.definition().weight()).sum();
        for (int i = 0; i < count && !available.isEmpty(); i++) {
            long roll = rollWeight(random, totalWeight);
            for (int index = 0; index < available.size(); index++) {
                PreparedTrade trade = available.get(index);
                int weight = trade.definition().weight();
                roll -= weight;
                if (roll < 0) {
                    available.remove(index);
                    totalWeight -= weight;
                    output.add(createOffer(random, trade));
                    break;
                }
            }
        }
    }

    private static long rollWeight(RandomSource random, long totalWeight) {
        if (totalWeight <= Integer.MAX_VALUE) {
            return random.nextInt((int) totalWeight);
        }
        RandomGenerator sampler = random::nextLong;
        return sampler.nextLong(totalWeight);
    }

    private static MerchantOffer createOffer(RandomSource random, PreparedTrade trade) {
        MerchantCatTradeDefinition definition = trade.definition();
        Item buy = select(random, definition.buy(), trade.buy());
        Item sell = select(random, definition.sell(), trade.sell());
        ItemCost displayedCost = new ItemCost(buy, definition.buy().count());
        ItemStack result = new ItemStack(sell, definition.sell().count());
        if (definition.buy().tag()) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, definition.buy().id());
            return new TaggedMerchantOffer(displayedCost, result, definition.maxUses(), tag, definition.buy().count());
        }
        return new MerchantOffer(displayedCost, result, definition.maxUses(), 0, 0.05F);
    }

    private static Item select(RandomSource random, MerchantCatTradeDefinition.TradeIngredient ingredient,
                               List<Item> options) {
        return ingredient.tag() ? options.get(random.nextInt(options.size())) : options.getFirst();
    }

    private static List<Item> resolve(Registry<Item> items, MerchantCatTradeDefinition.TradeIngredient ingredient) {
        if (!ingredient.tag()) {
            Item item = items.getOptional(ingredient.id()).filter(value -> value != Items.AIR)
                    .orElseThrow(() -> new IllegalArgumentException("无效交易物品 " + ingredient.id()));
            return List.of(item);
        }
        TagKey<Item> tag = TagKey.create(Registries.ITEM, ingredient.id());
        List<Item> candidates = items.getTag(tag)
                .map(values -> values.stream().map(Holder::value).filter(item -> item != Items.AIR).toList())
                .orElseGet(List::of);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("空交易物品 Tag " + ingredient.id());
        }
        return candidates;
    }

    private record PreparedTrade(MerchantCatTradeDefinition definition, List<Item> buy, List<Item> sell) {}

    private record TradeCatalog(ReloadableServerRegistries.Holder registries,
                                Map<MerchantCatTradePool, List<PreparedTrade>> pools) {}
}
