package com.meteorite.unsuspiciousblock.loot;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.loottable.injection.DeclaredLootInjection;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.LootTableIdCondition;
import java.util.ArrayList;
import java.util.List;

/** 仅记录本模组修改器执行到的实际字段；不反射 GLM 私有管理器或第三方实现。 */
public final class LootInjectionDescriptions {
    /** 每轮重载独立持有观测映射，旧线程只能写旧映射。 */
    private record Epoch(long id, java.util.Map<ResourceLocation, java.util.Map<Object, DeclaredLootInjection>> rules) {}
    private static final java.util.concurrent.atomic.AtomicLong NEXT_EPOCH = new java.util.concurrent.atomic.AtomicLong();
    private static volatile Epoch current = new Epoch(0, new java.util.concurrent.ConcurrentHashMap<>());
    private LootInjectionDescriptions() {}

    public static long epoch() { return current.id(); }
    public static synchronized void beginReload() { current = new Epoch(NEXT_EPOCH.incrementAndGet(), new java.util.concurrent.ConcurrentHashMap<>()); }
    public static List<DeclaredLootInjection> observed(ResourceLocation table) {
        var rules = current.rules().get(table);
        return rules == null ? List.of() : List.copyOf(rules.values());
    }
    public static void record(long ruleEpoch, Object owner, ResourceLocation table, Item item, float chance,
                              String mode, LootItemCondition[] conditions) {
        Epoch snapshot = current;
        if (ruleEpoch != snapshot.id()) return;
        snapshot.rules().computeIfAbsent(table, ignored -> new java.util.concurrent.ConcurrentHashMap<>())
                .computeIfAbsent(owner, ignored -> {
                    var rules = describe(table, item, chance, mode, conditions);
                    return rules.isEmpty() ? null : rules.getFirst();
                });
    }

    public static List<DeclaredLootInjection> describe(ResourceLocation table, Item item, float chance,
                                                       String mode, LootItemCondition[] conditions) {
        boolean matches = false;
        List<LootItemCondition> extras = new ArrayList<>();
        for (var condition : conditions) {
            if (condition instanceof LootTableIdCondition tableCondition) {
                var encoded = LootTableIdCondition.CODEC.codec().encodeStart(JsonOps.INSTANCE, tableCondition).result();
                if (encoded.isEmpty() || !encoded.get().isJsonObject()) return List.of();
                var id = encoded.get().getAsJsonObject().get("loot_table_id");
                if (id == null || !table.toString().equals(id.getAsString())) return List.of();
                matches = true;
            } else extras.add(condition);
        }
        if (!matches) return List.of();
        return List.of(new DeclaredLootInjection(BuiltInRegistries.ITEM.getKey(item), Constants.MOD_ID, mode,
                chance, LootConditionHandlers.analyzeAll(extras)));
    }
}
