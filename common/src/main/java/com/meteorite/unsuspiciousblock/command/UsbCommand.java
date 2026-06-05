package com.meteorite.unsuspiciousblock.command;

import com.meteorite.unsuspiciousblock.loottable.LootTableNames;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.ItemDefinition;
import com.meteorite.unsuspiciousblock.loottable.ArchaeologyLootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalState;
import com.meteorite.unsuspiciousblock.journal.state.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.network.ArchaeologyJournalNetwork;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** USB 调试指令入口 */
public final class UsbCommand {
    private static final String TABLE_ID_ARG = "table_id";
    private static final DynamicCommandExceptionType UNKNOWN_TABLE = new DynamicCommandExceptionType(
            tableId -> Component.translatable("command.unsuspiciousblock.usb.error.unknown_table", tableId)
    );

    private UsbCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("usb")
                .requires(source -> source.hasPermission(2))
                .then(buildClearSubcommand())
                .then(buildUnlockTableSubcommand())
                .then(buildUnlockItemSubcommand())
                .then(buildDebugSubcommand()));
    }

    // 清空玩家考古数据
    private static LiteralArgumentBuilder<CommandSourceStack> buildClearSubcommand() {
        return Commands.literal("clear")
                .executes(context -> mutateCurrentPlayer(context,
                        ArchaeologyJournalState::clear,
                        ArchaeologyJournalNetwork::clearLogs,
                        Component.translatable("command.unsuspiciousblock.usb.clear.success")))
                .then(Commands.argument(TABLE_ID_ARG, ResourceLocationArgument.id())
                        .suggests((context, builder) -> suggestTableIds(context.getSource(), builder))
                        .executes(context -> {
                            ServerPlayer player = requirePlayer(context);
                            ResourceLocation tableId = ResourceLocationArgument.getId(context, TABLE_ID_ARG);
                            requireTable(context.getSource(), tableId);
                            return mutateAndSyncFull(context.getSource(), player,
                                    state -> state.removeTable(tableId),
                                    target -> ArchaeologyJournalNetwork.clearLogsForTable(target, tableId),
                                    Component.translatable("command.unsuspiciousblock.usb.clear_table.success", tableId.toString()));
                        }));
    }

    // 解锁战利品表；无参数时解锁全部，有参数时解锁指定表
    private static LiteralArgumentBuilder<CommandSourceStack> buildUnlockTableSubcommand() {
        return Commands.literal("unlock_table")
                .executes(context -> {
                    ServerPlayer player = requirePlayer(context);
                    Map<ResourceLocation, TableDefinition> catalog = getCatalog(context.getSource());
                    return mutateAndSync(context.getSource(), player, state -> {
                        for (ResourceLocation tableId : catalog.keySet()) {
                            state.unlockTable(tableId);
                        }
                    }, null, Component.translatable("command.unsuspiciousblock.usb.unlock_tables.success", catalog.size()));
                })
                .then(Commands.argument(TABLE_ID_ARG, ResourceLocationArgument.id())
                        .suggests((context, builder) -> suggestTableIds(context.getSource(), builder))
                        .executes(context -> {
                            ServerPlayer player = requirePlayer(context);
                            ResourceLocation tableId = ResourceLocationArgument.getId(context, TABLE_ID_ARG);
                            requireTable(context.getSource(), tableId);
                            return mutateAndSync(context.getSource(), player,
                                    state -> state.unlockTable(tableId),
                                    null,
                                    Component.translatable("command.unsuspiciousblock.usb.unlock_table.success", tableId.toString()));
                        }));
    }

    // 解锁物品条目；无参数时解锁全部，有参数时解锁指定表中的全部条目
    private static LiteralArgumentBuilder<CommandSourceStack> buildUnlockItemSubcommand() {
        return Commands.literal("unlock_item")
                .executes(context -> {
                    ServerPlayer player = requirePlayer(context);
                    Map<ResourceLocation, TableDefinition> catalog = getCatalog(context.getSource());
                    int itemCount = countTotalItems(catalog);
                    return mutateAndSync(context.getSource(), player, state -> {
                        for (Map.Entry<ResourceLocation, TableDefinition> entry : catalog.entrySet()) {
                            unlockTableItems(state, entry.getKey(), entry.getValue());
                        }
                    }, null, Component.translatable("command.unsuspiciousblock.usb.unlock_items.success", itemCount, catalog.size()));
                })
                .then(Commands.argument(TABLE_ID_ARG, ResourceLocationArgument.id())
                        .suggests((context, builder) -> suggestTableIds(context.getSource(), builder))
                        .executes(context -> {
                            ServerPlayer player = requirePlayer(context);
                            ResourceLocation tableId = ResourceLocationArgument.getId(context, TABLE_ID_ARG);
                            TableDefinition table = requireTable(context.getSource(), tableId);
                            return mutateAndSync(context.getSource(), player,
                                    state -> unlockTableItems(state, tableId, table),
                                    null,
                                    Component.translatable("command.unsuspiciousblock.usb.unlock_items_in.success", tableId.toString(), table.items().size()));
                        }));
    }

    // 调试子指令
    private static LiteralArgumentBuilder<CommandSourceStack> buildDebugSubcommand() {
        return Commands.literal("debug")
                .then(Commands.literal("table_list")
                        .executes(context -> sendTableList(context.getSource())));
    }

    private static void unlockTableItems(ArchaeologyJournalState state, ResourceLocation tableId, TableDefinition table) {
        if (table.items().isEmpty()) {
            state.unlockTable(tableId);
            return;
        }
        state.unlockItems(tableId, table.items().stream().map(ItemDefinition::signature).toList());
    }

    private static int sendTableList(CommandSourceStack source) {
        Map<ResourceLocation, TableDefinition> catalog = getCatalog(source);
        if (catalog.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.unsuspiciousblock.usb.debug.table_list.empty"), false);
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("command.unsuspiciousblock.usb.debug.table_list.header", catalog.size()), false);
        int index = 1;
        for (TableDefinition table : catalog.values()) {
            int lineIndex = index++;
            ResourceLocation tableId = table.id();
            String translationKey = LootTableNames.translationKey(tableId);
            String fallbackName = LootTableNames.fallbackName(tableId);
            String displayName = table.displayName().getString();
            source.sendSuccess(() -> Component.translatable(
                    "command.unsuspiciousblock.usb.debug.table_list.entry",
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
            source.sendFailure(Component.translatable("command.unsuspiciousblock.usb.error.state_unavailable"));
            return 0;
        }

        ArchaeologyJournalState state = holder.unsuspiciousblock$getArchaeologyJournalState();
        mutator.accept(state);
        ArchaeologyJournalNetwork.syncStateFull(player);
        if (afterSync != null) {
            afterSync.accept(player);
        }
        source.sendSuccess(() -> successMessage, false);
        return 1;
    }

    // 变更后进行增量同步（用于普通解锁操作，只发送变更的表）
    private static int mutateAndSync(CommandSourceStack source, ServerPlayer player,
                                     Consumer<ArchaeologyJournalState> mutator,
                                     Consumer<ServerPlayer> afterSync,
                                     Component successMessage) {
        if (!(player instanceof ArchaeologyJournalStateHolder holder)) {
            source.sendFailure(Component.translatable("command.unsuspiciousblock.usb.error.state_unavailable"));
            return 0;
        }

        ArchaeologyJournalState state = holder.unsuspiciousblock$getArchaeologyJournalState();
        mutator.accept(state);
        ArchaeologyJournalNetwork.syncState(player);
        if (afterSync != null) {
            afterSync.accept(player);
        }
        source.sendSuccess(() -> successMessage, false);
        return 1;
    }

    private static Map<ResourceLocation, TableDefinition> getCatalog(CommandSourceStack source) {
        ArchaeologyJournalServerCatalog.ensureLoaded(source.getServer());
        return ArchaeologyJournalServerCatalog.getCatalog();
    }

    private static TableDefinition requireTable(CommandSourceStack source, ResourceLocation tableId) throws CommandSyntaxException {
        TableDefinition table = getCatalog(source).get(tableId);
        if (table == null) {
            throw UNKNOWN_TABLE.create(tableId.toString());
        }
        return table;
    }

    private static CompletableFuture<Suggestions> suggestTableIds(CommandSourceStack source, SuggestionsBuilder builder) {
        for (ResourceLocation tableId : getCatalog(source).keySet()) {
            builder.suggest(tableId.toString());
        }
        return builder.buildFuture();
    }

    private static int countTotalItems(Map<ResourceLocation, TableDefinition> catalog) {
        int count = 0;
        for (TableDefinition table : catalog.values()) {
            count += table.items().size();
        }
        return count;
    }
}
