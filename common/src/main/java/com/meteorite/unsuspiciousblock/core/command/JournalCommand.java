package com.meteorite.unsuspiciousblock.core.command;

import com.meteorite.unsuspiciousblock.core.loottable.catalog.CatalogQueryIndex;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.LootProbabilitySimulationWorker;
import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.core.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalLogHandler;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalStateHandler;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 执行玩家的笔记进度操作，以及服务器共享目录子树。
 */
public final class JournalCommand {
    private static final String TABLE_ID_ARG = "table_id";
    private static final DynamicCommandExceptionType UNKNOWN_TABLE = new DynamicCommandExceptionType(
            tableId -> Component.translatable("command.unsuspiciousblock.usb.journal.error.unknown_table", tableId)
    );

    private JournalCommand() {
    }

    /**
     * 构建笔记子树，由 /usb 根节点统一检查权限。
     */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("journal")
                .then(buildResetSubcommand())
                .then(buildUnlockSubcommand())
                .then(JournalCatalogCommand.build());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildResetSubcommand() {
        return Commands.literal("reset")
                .then(Commands.literal("all").executes(context -> mutateCurrentPlayer(context,
                        ArchaeologyJournalState::clear,
                        JournalLogHandler::clearLogs,
                        Component.translatable("command.unsuspiciousblock.usb.journal.reset.success"))))
                .then(Commands.argument(TABLE_ID_ARG, ResourceLocationArgument.id())
                        .suggests((context, builder) -> suggestTableIds(context.getSource(), builder))
                        .executes(context -> {
                            ServerPlayer player = requirePlayer(context);
                            ResourceLocation tableId = ResourceLocationArgument.getId(context, TABLE_ID_ARG);
                            requireTable(context.getSource(), tableId);
                            return mutateAndSyncFull(context.getSource(), player,
                                    state -> state.removeTable(tableId),
                                    target -> JournalLogHandler.clearLogsForTable(target, tableId),
                                    Component.translatable("command.unsuspiciousblock.usb.journal.reset_table.success", tableId.toString()));
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildUnlockSubcommand() {
        return Commands.literal("unlock")
                .then(Commands.literal("tables")
                        .then(Commands.literal("all").executes(context -> {
                            ServerPlayer player = requirePlayer(context);
                            Map<ResourceLocation, TableDefinition> catalog = getRawCatalog(context.getSource());
                            return mutateAndSync(context.getSource(), player, state -> {
                                for (ResourceLocation tableId : catalog.keySet()) {
                                    state.unlockTable(tableId);
                                }
                            }, Component.translatable("command.unsuspiciousblock.usb.journal.unlock.tables.all.success", catalog.size()));
                        }))
                        .then(Commands.argument(TABLE_ID_ARG, ResourceLocationArgument.id())
                                .suggests((context, builder) -> suggestTableIds(context.getSource(), builder))
                                .executes(context -> {
                                    ServerPlayer player = requirePlayer(context);
                                    ResourceLocation tableId = ResourceLocationArgument.getId(context, TABLE_ID_ARG);
                                    requireTable(context.getSource(), tableId);
                                    return mutateAndSync(context.getSource(), player,
                                            state -> state.unlockTable(tableId),
                                            Component.translatable("command.unsuspiciousblock.usb.journal.unlock.tables.success", tableId.toString()));
                                })))
                .then(Commands.literal("items")
                        .then(Commands.literal("all").executes(context -> {
                            ServerPlayer player = requirePlayer(context);
                            if (rejectIncompleteSimulation(context.getSource())) {
                                return 0;
                            }
                            Map<ResourceLocation, TableDefinition> catalog = getSimulatedCatalog(context.getSource());
                            CatalogQueryIndex queryIndex = getQueryIndex(context.getSource());
                            int itemCount = countTotalItems(queryIndex, catalog.keySet());
                            return mutateAndSync(context.getSource(), player, state -> {
                                for (ResourceLocation tableId : catalog.keySet()) {
                                    unlockTableItems(state, tableId, queryIndex.subtreeItems(tableId));
                                }
                            }, Component.translatable("command.unsuspiciousblock.usb.journal.unlock.items.all.success", itemCount, catalog.size()));
                        }))
                        .then(Commands.argument(TABLE_ID_ARG, ResourceLocationArgument.id())
                                .suggests((context, builder) -> suggestTableIds(context.getSource(), builder))
                                .executes(context -> {
                                    ServerPlayer player = requirePlayer(context);
                                    if (rejectIncompleteSimulation(context.getSource())) {
                                        return 0;
                                    }
                                    ResourceLocation tableId = ResourceLocationArgument.getId(context, TABLE_ID_ARG);
                                    requireTable(context.getSource(), tableId);
                                    List<ItemDefinition> items = getQueryIndex(context.getSource()).subtreeItems(tableId);
                                    return mutateAndSync(context.getSource(), player,
                                            state -> unlockTableItems(state, tableId, items),
                                            Component.translatable("command.unsuspiciousblock.usb.journal.unlock.items.success", tableId.toString(), items.size()));
                                })));
    }

    private static void unlockTableItems(ArchaeologyJournalState state, ResourceLocation tableId,
                                         List<ItemDefinition> items) {
        if (items.isEmpty()) {
            state.unlockTable(tableId);
            return;
        }
        state.unlockItems(tableId, items.stream().map(ItemDefinition::signature).toList());
    }

    private static ServerPlayer requirePlayer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return context.getSource().getPlayerOrException();
    }

    private static int mutateCurrentPlayer(CommandContext<CommandSourceStack> context,
                                           Consumer<ArchaeologyJournalState> mutator,
                                           Consumer<ServerPlayer> afterSync,
                                           Component successMessage) throws CommandSyntaxException {
        ServerPlayer player = requirePlayer(context);
        return mutateAndSyncFull(context.getSource(), player, mutator, afterSync, successMessage);
    }

    // 变更后进行全量同步（用于破坏性操作如 clear/removeTable，确保客户端状态完全一致）
    private static int mutateAndSyncFull(CommandSourceStack source, ServerPlayer player,
                                         Consumer<ArchaeologyJournalState> mutator,
                                         Consumer<ServerPlayer> afterSync,
                                         Component successMessage) {
        if (!(player instanceof ArchaeologyJournalStateHolder holder)) {
            source.sendFailure(Component.translatable("command.unsuspiciousblock.usb.journal.error.state_unavailable"));
            return 0;
        }

        ArchaeologyJournalState state = holder.unsuspiciousblock$getArchaeologyJournalState();
        mutator.accept(state);
        JournalStateHandler.syncStateFull(player);
        if (afterSync != null) {
            afterSync.accept(player);
        }
        source.sendSuccess(() -> successMessage, false);
        return 1;
    }

    // 变更后进行增量同步（用于普通解锁操作，只发送变更的表）
    private static int mutateAndSync(CommandSourceStack source, ServerPlayer player,
                                     Consumer<ArchaeologyJournalState> mutator,
                                     Component successMessage) {
        if (!(player instanceof ArchaeologyJournalStateHolder holder)) {
            source.sendFailure(Component.translatable("command.unsuspiciousblock.usb.journal.error.state_unavailable"));
            return 0;
        }

        ArchaeologyJournalState state = holder.unsuspiciousblock$getArchaeologyJournalState();
        mutator.accept(state);
        JournalStateHandler.syncState(player);
        source.sendSuccess(() -> successMessage, false);
        return 1;
    }

    private static Map<ResourceLocation, TableDefinition> getRawCatalog(CommandSourceStack source) {
        ArchaeologyJournalServerCatalog.ensureLoaded(source.getServer());
        return ArchaeologyJournalServerCatalog.staticTables();
    }

    // 当代查询索引：子树物品聚合的唯一入口（模拟态优先、缺失处回退静态投影）
    private static CatalogQueryIndex getQueryIndex(CommandSourceStack source) {
        ArchaeologyJournalServerCatalog.ensureLoaded(source.getServer());
        return ArchaeologyJournalServerCatalog.queryIndex();
    }

    private static Map<ResourceLocation, TableDefinition> getSimulatedCatalog(CommandSourceStack source) {
        ArchaeologyJournalServerCatalog.ensureLoaded(source.getServer());
        return ArchaeologyJournalServerCatalog.simulatedTables();
    }

    private static void requireTable(CommandSourceStack source, ResourceLocation tableId) throws CommandSyntaxException {
        ArchaeologyJournalServerCatalog.ensureLoaded(source.getServer());
        TableDefinition table = ArchaeologyJournalServerCatalog.staticTable(tableId);
        if (table == null) {
            throw UNKNOWN_TABLE.create(tableId.toString());
        }
    }

    // 复用原版 suggestResource 的过滤逻辑：基于 remaining 做前缀匹配，
    // 并对 minecraft 命名空间做特殊处理（无冒号时同时匹配 namespace 与 path）
    private static CompletableFuture<Suggestions> suggestTableIds(CommandSourceStack source, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(getRawCatalog(source).keySet(), builder);
    }

    private static int countTotalItems(CatalogQueryIndex queryIndex, Set<ResourceLocation> tableIds) {
        int count = 0;
        for (ResourceLocation tableId : tableIds) {
            count += queryIndex.subtreeItems(tableId).size();
        }
        return count;
    }

    // 物品目录会在模拟期间渐进填充，拒绝基于半成品目录执行批量解锁。
    private static boolean rejectIncompleteSimulation(CommandSourceStack source) {
        Map<ResourceLocation, TableDefinition> rawCatalog = getRawCatalog(source);
        Map<ResourceLocation, TableDefinition> catalog = ArchaeologyJournalServerCatalog.simulatedTables();
        LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
        if ((worker != null && worker.isBusy())
                || catalog.size() != rawCatalog.size()
                || !catalog.keySet().containsAll(rawCatalog.keySet())) {
            source.sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.journal.error.simulation_in_progress"));
            return true;
        }
        return false;
    }
}
