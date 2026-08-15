package com.meteorite.unsuspiciousblock.command;

import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.loottable.simulation.LootProbabilitySimulationWorker;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.journal.tracking.JournalLogRecorder;
import com.meteorite.unsuspiciousblock.network.journal.JournalCatalogHandler;
import com.meteorite.unsuspiciousblock.network.journal.JournalStateHandler;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** 考古笔记调试子指令树：/usb journal ... */
public final class JournalCommand {
    private static final String TABLE_ID_ARG = "table_id";
    private static final DynamicCommandExceptionType UNKNOWN_TABLE = new DynamicCommandExceptionType(
            tableId -> Component.translatable("command.unsuspiciousblock.usb.journal.error.unknown_table", tableId)
    );

    private JournalCommand() {}

    // 构建 journal 子树
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("journal")
                .requires(source -> source.hasPermission(2))
                .then(buildClearSubcommand())
                .then(buildUnlockSubcommand())
                .then(buildReloadSubcommand())
                .then(buildListSubcommand());
    }

    // clear [table_id]：清空玩家考古数据，可指定单表
    private static LiteralArgumentBuilder<CommandSourceStack> buildClearSubcommand() {
        return Commands.literal("clear")
                .executes(context -> mutateCurrentPlayer(context,
                        ArchaeologyJournalState::clear,
                        JournalLogRecorder::clearLogs,
                        Component.translatable("command.unsuspiciousblock.usb.journal.clear.success")))
                .then(Commands.argument(TABLE_ID_ARG, ResourceLocationArgument.id())
                        .suggests((context, builder) -> suggestTableIds(context.getSource(), builder))
                        .executes(context -> {
                            ServerPlayer player = requirePlayer(context);
                            ResourceLocation tableId = ResourceLocationArgument.getId(context, TABLE_ID_ARG);
                            requireTable(context.getSource(), tableId);
                            return mutateAndSyncFull(context.getSource(), player,
                                    state -> state.removeTable(tableId),
                                    target -> JournalLogRecorder.clearLogsForTable(target, tableId),
                                    Component.translatable("command.unsuspiciousblock.usb.journal.clear_table.success", tableId.toString()));
                        }));
    }

    // unlock table|item [table_id]：解锁表或物品，无参=全部
    private static LiteralArgumentBuilder<CommandSourceStack> buildUnlockSubcommand() {
        return Commands.literal("unlock")
                .then(Commands.literal("table")
                        .executes(context -> {
                            ServerPlayer player = requirePlayer(context);
                            Map<ResourceLocation, TableDefinition> catalog = getRawCatalog(context.getSource());
                            return mutateAndSync(context.getSource(), player, state -> {
                                for (ResourceLocation tableId : catalog.keySet()) {
                                    state.unlockTable(tableId);
                                }
                            }, Component.translatable("command.unsuspiciousblock.usb.journal.unlock.table.all.success", catalog.size()));
                        })
                        .then(Commands.argument(TABLE_ID_ARG, ResourceLocationArgument.id())
                                .suggests((context, builder) -> suggestTableIds(context.getSource(), builder))
                                .executes(context -> {
                                    ServerPlayer player = requirePlayer(context);
                                    ResourceLocation tableId = ResourceLocationArgument.getId(context, TABLE_ID_ARG);
                                    requireTable(context.getSource(), tableId);
                                    return mutateAndSync(context.getSource(), player,
                                            state -> state.unlockTable(tableId),
                                            Component.translatable("command.unsuspiciousblock.usb.journal.unlock.table.success", tableId.toString()));
                                })))
                .then(Commands.literal("item")
                        .executes(context -> {
                            ServerPlayer player = requirePlayer(context);
                            if (!requireCompletedSimulation(context.getSource())) {
                                return 0;
                            }
                            Map<ResourceLocation, TableDefinition> catalog = getSimulatedCatalog(context.getSource());
                            int itemCount = countTotalItems(catalog);
                            return mutateAndSync(context.getSource(), player, state -> {
                                for (ResourceLocation tableId : catalog.keySet()) {
                                    unlockTableItems(state, tableId,
                                            LootTableCatalog.collectSubtreeItems(catalog, tableId));
                                }
                            }, Component.translatable("command.unsuspiciousblock.usb.journal.unlock.item.all.success", itemCount, catalog.size()));
                        })
                        .then(Commands.argument(TABLE_ID_ARG, ResourceLocationArgument.id())
                                .suggests((context, builder) -> suggestTableIds(context.getSource(), builder))
                                .executes(context -> {
                                    ServerPlayer player = requirePlayer(context);
                                    if (!requireCompletedSimulation(context.getSource())) {
                                        return 0;
                                    }
                                    ResourceLocation tableId = ResourceLocationArgument.getId(context, TABLE_ID_ARG);
                                    requireTable(context.getSource(), tableId);
                                    Map<ResourceLocation, TableDefinition> catalog = getSimulatedCatalog(context.getSource());
                                    List<ItemDefinition> items = LootTableCatalog.collectSubtreeItems(catalog, tableId);
                                    return mutateAndSync(context.getSource(), player,
                                            state -> unlockTableItems(state, tableId, items),
                                            Component.translatable("command.unsuspiciousblock.usb.journal.unlock.item.success", tableId.toString(), items.size()));
                                })));
    }

    // reload：强制清空概率缓存，重新加载并重新模拟所有跟踪的战利品表
    private static LiteralArgumentBuilder<CommandSourceStack> buildReloadSubcommand() {
        return Commands.literal("reload")
                .executes(context -> flushTablesWithFeedback(context.getSource()));
    }

    // list：列出所有已加载的考古战利品表
    private static LiteralArgumentBuilder<CommandSourceStack> buildListSubcommand() {
        return Commands.literal("list")
                .executes(context -> sendTableList(context.getSource()));
    }

    // reload/flush：清空缓存 + 异步重新模拟，并向执行玩家逐条发送进度
    private static int flushTablesWithFeedback(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ServerPlayer player = source.getPlayer();

        LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
        if (worker == null) {
            // 工作线程未启动（异常情况）：回退到无反馈的同步 flush
            JournalCatalogHandler.forceFlushCatalog(server);
            int tableCount = ArchaeologyJournalServerCatalog.getCatalog().size();
            source.sendSuccess(() -> Component.translatable(
                    "command.unsuspiciousblock.usb.journal.reload.success", tableCount), false);
            return tableCount;
        }

        // 暂停 worker，避免 flush 入队后立刻消费导致 total 未就绪时回调触发
        worker.pauseForReload();

        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger total = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.UUID playerId = player != null ? player.getUUID() : null;

        worker.setProgressListener((tableId, result) -> {
            int idx = done.incrementAndGet();
            int expected = total.get();
            boolean completed = expected > 0 && idx >= expected;
            if (completed) {
                worker.setProgressListener(null);
            }
            if (playerId == null) return;
            ServerPlayer p = server.getPlayerList().getPlayer(playerId);
            if (p == null) return;
            String displayName = LootTableNames.resolveDisplayName(tableId).getString();
            p.sendSystemMessage(Component.translatable(
                    "command.unsuspiciousblock.usb.journal.reload.progress",
                    idx, expected, tableId.toString(), displayName));
            if (completed) {
                p.sendSystemMessage(Component.translatable(
                        "command.unsuspiciousblock.usb.journal.reload.complete", idx));
            }
        });

        // 执行 flush：清空缓存 + 重新解析 + 入队（worker 已暂停，不会消费）
        JournalCatalogHandler.forceFlushCatalog(server);
        total.set(ArchaeologyJournalServerCatalog.getRawCatalogCount());
        if (total.get() == 0) {
            worker.setProgressListener(null);
        }

        // 恢复 worker 消费
        worker.resumeAfterReload();

        source.sendSuccess(() -> Component.translatable(
                "command.unsuspiciousblock.usb.journal.reload.started", total.get()), false);
        return total.get();
    }

    private static void unlockTableItems(ArchaeologyJournalState state, ResourceLocation tableId,
                                         List<ItemDefinition> items) {
        if (items.isEmpty()) {
            state.unlockTable(tableId);
            return;
        }
        state.unlockItems(tableId, items.stream().map(ItemDefinition::signature).toList());
    }

    private static int sendTableList(CommandSourceStack source) {
        Map<ResourceLocation, TableDefinition> catalog = getRawCatalog(source);
        if (catalog.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.unsuspiciousblock.usb.journal.list.empty"), false);
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("command.unsuspiciousblock.usb.journal.list.header", catalog.size()), false);
        int index = 1;
        for (TableDefinition table : catalog.values()) {
            int lineIndex = index++;
            ResourceLocation tableId = table.id();
            // 调试命令强制重新检索缺失 key 并写盘，作为 catalog 加载失败时的手动补救手段
            String displayName = LootTableNames.forceResolveDisplayName(tableId).getString();
            String translationKey = LootTableNames.translationKey(tableId);
            String fallbackName = LootTableNames.fallbackName(tableId);
            source.sendSuccess(() -> Component.translatable(
                    "command.unsuspiciousblock.usb.journal.list.entry",
                    lineIndex,
                    tableId.toString(),
                    translationKey,
                    fallbackName,
                    displayName
            ), false);
        }
        return catalog.size();
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
        return ArchaeologyJournalServerCatalog.getRawCatalog();
    }

    private static Map<ResourceLocation, TableDefinition> getSimulatedCatalog(CommandSourceStack source) {
        ArchaeologyJournalServerCatalog.ensureLoaded(source.getServer());
        return ArchaeologyJournalServerCatalog.getCatalog();
    }

    private static TableDefinition requireTable(CommandSourceStack source, ResourceLocation tableId) throws CommandSyntaxException {
        TableDefinition table = getRawCatalog(source).get(tableId);
        if (table == null) {
            throw UNKNOWN_TABLE.create(tableId.toString());
        }
        return table;
    }

    // 复用原版 suggestResource 的过滤逻辑：基于 remaining 做前缀匹配，
    // 并对 minecraft 命名空间做特殊处理（无冒号时同时匹配 namespace 与 path）
    private static CompletableFuture<Suggestions> suggestTableIds(CommandSourceStack source, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(getRawCatalog(source).keySet(), builder);
    }

    private static int countTotalItems(Map<ResourceLocation, TableDefinition> catalog) {
        int count = 0;
        for (ResourceLocation tableId : catalog.keySet()) {
            count += LootTableCatalog.collectSubtreeItems(catalog, tableId).size();
        }
        return count;
    }

    // 物品目录会在模拟期间渐进填充，拒绝基于半成品目录执行批量解锁。
    private static boolean requireCompletedSimulation(CommandSourceStack source) {
        Map<ResourceLocation, TableDefinition> rawCatalog = getRawCatalog(source);
        Map<ResourceLocation, TableDefinition> catalog = ArchaeologyJournalServerCatalog.getCatalog();
        LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
        if ((worker != null && worker.isBusy())
                || catalog.size() != rawCatalog.size()
                || !catalog.keySet().containsAll(rawCatalog.keySet())) {
            source.sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.journal.error.simulation_in_progress"));
            return false;
        }
        return true;
    }
}
