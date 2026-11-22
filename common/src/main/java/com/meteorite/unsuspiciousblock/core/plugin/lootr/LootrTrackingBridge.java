package com.meteorite.unsuspiciousblock.core.plugin.lootr;

import com.meteorite.unsuspiciousblock.core.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.core.journal.tracking.GeneratedLootInventory;
import com.meteorite.unsuspiciousblock.core.journal.tracking.LootCapture;
import com.meteorite.unsuspiciousblock.core.journal.tracking.LootCapture.CaptureHandle;
import com.meteorite.unsuspiciousblock.core.journal.tracking.settlement.LootSettlementTarget.Deferred;
import com.meteorite.unsuspiciousblock.core.journal.tracking.settlement.PendingTarget;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import noobanidus.mods.lootr.common.api.data.ILootrInfoProvider;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** 接入 Lootr 的一次实际抽取，并以最终库存完成笔记发现。 */
@SuppressWarnings("UnstableApiUsage")
public final class LootrTrackingBridge {
    private LootrTrackingBridge() {
    }

    /** 引用库存等非抽取路径由 Lootr 直接处理。 */
    public static @Nullable CaptureHandle prepare(ILootrInfoProvider provider, Player player,
                                                  ResourceKey<LootTable> table, LootSourceType source) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(provider.getInfoLevel() instanceof ServerLevel level)
                || provider.isInfoReferenceInventory()) {
            return null;
        }
        Object host = provider instanceof BlockEntity ? provider : provider.getInfoContainer();
        ResourceLocation block = host instanceof BlockEntity blockEntity
                ? BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock()) : null;
        return LootCapture.beginIndependent(new LootCapture.Source(
                serverPlayer, table.location(), source, level, provider.getInfoPos(), block));
    }

    /** 读取 Lootr 已经完成筛选和写槽的实际物品。 */
    public static void complete(@Nullable CaptureHandle capture, Container inventory) {
        if (capture == null || capture.context() == null || !(inventory instanceof GeneratedLootInventory tracked)) {
            return;
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) stacks.add(stack);
        }
        capture.complete(stacks, new Deferred(new PendingTarget.Inventory(tracked.unsuspiciousblock$inventoryLoot())));
    }
}
