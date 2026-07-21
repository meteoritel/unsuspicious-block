package com.meteorite.unsuspiciousblock.plugin.lootr;

import com.meteorite.unsuspiciousblock.blockentity.TrackedContainerLootState;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.state.LootSourceType;
import com.meteorite.unsuspiciousblock.journal.tracking.ContainerTrackingService;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContext;
import com.meteorite.unsuspiciousblock.journal.tracking.LootTrackingContextHolder;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.loottable.signature.LootResultSignature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import noobanidus.mods.lootr.common.api.data.ILootrInfoProvider;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Lootr 战利品生成流程与本模组容器追踪系统之间的共享桥接层。
 */
@SuppressWarnings("UnstableApiUsage")
public final class LootrTrackingBridge {
    private LootrTrackingBridge() {
    }

    // 创建并压入本次 Lootr 战利品生成上下文；不需要追踪时返回 null
    @Nullable
    public static Session prepare(ILootrInfoProvider provider, Player player, LootSourceType sourceType) {
        if (!(player instanceof ServerPlayer serverPlayer) || provider.isInfoReferenceInventory()) {
            return null;
        }

        ResourceKey<LootTable> lootTableKey = provider.getInfoLootTable();
        Level level = provider.getInfoLevel();
        if (lootTableKey == null || !(level instanceof ServerLevel serverLevel)) {
            return null;
        }

        ResourceLocation tableId = lootTableKey.location();
        if (!LootTableNames.isArchaeologyLootTable(tableId)) {
            return null;
        }

        BlockPos pos = provider.getInfoPos();
        ResourceLocation sourceBlockId = null;
        if (provider.getInfoContainer() instanceof BlockEntity blockEntity) {
            sourceBlockId = BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock());
        }

        LootTrackingContext context = LootTrackingContext.root(
                serverPlayer, tableId, sourceType,
                serverLevel.getGameTime(), serverLevel.getDayTime(), pos, sourceBlockId);
        return new Session(serverPlayer, tableId, pos, sourceBlockId, sourceType, context);
    }

    // 仅在真正执行 loot roll 时压入上下文
    public static void open(@Nullable Session session) {
        if (session != null) {
            LootTrackingContextHolder.push(session.context());
        }
    }

    // 弹出 open 压入的上下文；应在 finally 中调用
    public static void close(@Nullable Session session) {
        if (session != null) {
            LootTrackingContextHolder.pop();
        }
    }

    // 将 Lootr 实际生成的每玩家库存提交到容器追踪服务
    public static void commit(@Nullable Session session, Container inventory) {
        if (session == null || !(inventory instanceof TrackedContainerLootState trackedContainer)) {
            return;
        }

        List<LootResultSignature> candidates = collectCandidates(trackedContainer, session.tableId());
        ContainerTrackingService.onContainerLootResolved(
                session.player(), trackedContainer, session.tableId(),
                trackedContainer.unsuspiciousblock$collectContainerItemCounts(candidates),
                session.pos(), session.sourceBlockId(), session.sourceType());
    }

    // 优先使用目录中的完整签名；目录缺失时退回到库存内的普通物品签名
    private static List<LootResultSignature> collectCandidates(TrackedContainerLootState container,
                                                                ResourceLocation tableId) {
        TableDefinition table = ArchaeologyJournalServerCatalog.getCatalog().get(tableId);
        if (table != null && !table.items().isEmpty()) {
            List<LootResultSignature> candidates = new ArrayList<>();
            for (ItemDefinition item : table.items()) {
                candidates.add(item.signature());
            }
            return candidates;
        }

        LinkedHashSet<ResourceLocation> fallbackItems = new LinkedHashSet<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) {
                fallbackItems.add(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            }
        }

        List<LootResultSignature> candidates = new ArrayList<>();
        for (ResourceLocation itemId : fallbackItems) {
            candidates.add(LootResultSignature.plain(itemId));
        }
        return candidates;
    }

    /**
     * 单次 Lootr 战利品生成所需的不可变追踪信息。
     */
    public record Session(ServerPlayer player, ResourceLocation tableId, BlockPos pos,
                   @Nullable ResourceLocation sourceBlockId, LootSourceType sourceType,
                   LootTrackingContext context) {
    }
}
