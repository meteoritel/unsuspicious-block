package com.meteorite.unsuspiciousblock.loot;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.meteorite.unsuspiciousblock.loottable.injection.DeclaredLootInjection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Fabric 自有注入声明；内置表修改只在 MODIFY 确实执行时登记，每次重载覆盖。 */
public final class FabricLootInjectionDescriptions {
    private static final Map<ResourceLocation, DeclaredLootInjection> BUILTIN = new ConcurrentHashMap<>();
    private FabricLootInjectionDescriptions() {}

    public static void builtin(ResourceLocation table, Item item, float chance, boolean enabled) {
        if (enabled) BUILTIN.put(table, declaration(item, chance, "append"));
        else BUILTIN.remove(table);
    }

    private static DeclaredLootInjection declaration(Item item, float chance, String mode) {
        return new DeclaredLootInjection(BuiltInRegistries.ITEM.getKey(item), Constants.MOD_ID, mode, chance, List.of());
    }

    public static List<DeclaredLootInjection> describe(ResourceLocation table) {
        if (table.equals(LootInjection.TRAIL_RUINS_COMMON_ID))
            return List.of(declaration(ModItems.ANCIENT_COIN, LootInjection.ANCIENT_COIN_CHANCE, "replace"));
        if (table.equals(LootInjection.TRAIL_RUINS_RARE_ID))
            return List.of(declaration(ModItems.LOST_PAGE, LootInjection.LOST_PAGE_CHANCE, "replace"));
        var builtin = BUILTIN.get(table);
        return builtin == null ? List.of() : List.of(builtin);
    }
}
