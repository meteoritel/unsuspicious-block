package com.meteorite.unsuspiciousblock.platform;

import com.meteorite.unsuspiciousblock.platform.services.IAccessoryHelper;
import net.neoforged.fml.ModList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.stream.Stream;

/**
 * NeoForge 端饰品栏查询入口。Curios 缺失时保持为空实现，存在时再加载实际适配器。
 */
public final class NeoForgeAccessoryHelper implements IAccessoryHelper {
    private static final String CURIOS_HELPER =
            "com.meteorite.unsuspiciousblock.plugin.curio.CuriosAccessoryHelper";

    private final IAccessoryHelper delegate;

    public NeoForgeAccessoryHelper() {
        this.delegate = ModList.get().isLoaded("curios")
                ? OptionalModIntegration.instantiate(CURIOS_HELPER, IAccessoryHelper.class)
                : null;
    }

    @Override
    public boolean isJournalEquipped(Player player) {
        return delegate != null && delegate.isJournalEquipped(player);
    }

    @Override
    public boolean isPresent(Player player, Item item) {
        return delegate != null && delegate.isPresent(player, item);
    }

    @Override
    public Stream<ItemStack> streamEquippedStacks(Player player) {
        return delegate == null ? Stream.empty() : delegate.streamEquippedStacks(player);
    }
}
