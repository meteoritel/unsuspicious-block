package com.meteorite.unsuspiciousblock.plugin.lootr;

import com.meteorite.unsuspiciousblock.loottable.injection.ArchaeologyLootInjectors;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import noobanidus.mods.lootr.common.api.data.DefaultBrushableLootFiller;
import noobanidus.mods.lootr.common.api.data.LootFiller;
import noobanidus.mods.lootr.common.api.filter.ILootrFilter;
import noobanidus.mods.lootr.common.api.filter.ILootrFilterProvider;

import java.util.List;

/**
 * 通过 Lootr 原生 Filter API 将平台考古战利品注入器应用到每玩家可疑方块库存。
 */
public final class LootrArchaeologyFilterProvider implements ILootrFilterProvider {
    private static final ILootrFilter FILTER = new ArchaeologyFilter();

    @Override
    public List<ILootrFilter> getFilters() {
        return List.of(FILTER);
    }

    /**
     * 只处理 Lootr 的 DefaultBrushableLootFiller，不影响普通箱类容器。
     */
    private static final class ArchaeologyFilter implements ILootrFilter {
        @Override
        public int getPriority() {
            return 0;
        }

        @Override
        public String getName() {
            return "unsuspiciousblock:archaeology_injection";
        }

        @Override
        public boolean mutate(ObjectArrayList<ItemStack> items, LootFiller.LootFillerState state,
                              LootContext context, RandomSource random) {
            if (state == null
                    || state.provider().getDefaultFiller() != DefaultBrushableLootFiller.getInstance()) {
                return false;
            }

            ArchaeologyLootInjectors.get().maybeReplace(state.lootTableKey().location(), items, random);
            return false;
        }
    }
}
