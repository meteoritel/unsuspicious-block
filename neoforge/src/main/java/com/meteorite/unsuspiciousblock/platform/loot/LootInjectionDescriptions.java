package com.meteorite.unsuspiciousblock.platform.loot;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.loottable.analysis.LootConditionHandlers;
import com.meteorite.unsuspiciousblock.core.loottable.injection.DeclaredLootInjection;
import com.meteorite.unsuspiciousblock.platform.mixin.loot.LootModifierManagerAccess;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.LootTableIdCondition;
import java.util.ArrayList;
import java.util.List;

/** 从 Loader 启用的修改器读取本模组可声明的掉落注入。 */
public final class LootInjectionDescriptions {
    private LootInjectionDescriptions() {}

    /** 返回指定表在当前启用修改器中声明的物品注入。 */
    public static List<DeclaredLootInjection> activeFor(ResourceLocation table) {
        List<DeclaredLootInjection> injections = new ArrayList<>();
        for (var modifier : LootModifierManagerAccess.unsuspiciousblock$getLootModifierManager().getAllLootMods()) {
            if (modifier instanceof InjectItemLootModifier inject) {
                injections.addAll(inject.describe(table));
            } else if (modifier instanceof AddItemLootModifier add) {
                injections.addAll(add.describe(table));
            }
        }
        return List.copyOf(injections);
    }

    static List<DeclaredLootInjection> describe(ResourceLocation table, Item item, float chance,
                                                String mode, LootItemCondition[] conditions) {
        if (chance == 0.0F) {
            return List.of();
        }
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
