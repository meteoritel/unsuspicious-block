package com.meteorite.unsuspiciousblock.core.command;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableCatalog.TableDefinition;
import com.meteorite.unsuspiciousblock.core.loottable.catalog.LootTableNames;
import com.meteorite.unsuspiciousblock.core.loottable.simulation.LootProbabilitySimulationWorker;
import com.meteorite.unsuspiciousblock.core.network.journal.JournalCatalogHandler;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务器共享目录维护；与执行玩家的笔记进度分别管理。
 */
public final class JournalCatalogCommand {
    private static final String KEY = "command.unsuspiciousblock.usb.journal.catalog.";

    private JournalCatalogCommand() {
    }

    /**
     * 构建目录维护子树，由 /usb 根节点检查权限。
     */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("catalog")
                .then(Commands.literal("list").executes(context -> list(context.getSource())))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("rebuild").executes(context -> rebuild(context.getSource())));
    }

    private static int list(CommandSourceStack source) {
        ArchaeologyJournalServerCatalog.ensureLoaded(source.getServer());
        var catalog = ArchaeologyJournalServerCatalog.staticTables();
        if (catalog.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(KEY + "list.empty"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(KEY + "list.header", catalog.size()), false);
        int index = 0;
        for (TableDefinition table : catalog.values()) {
            int lineIndex = ++index;
            ResourceLocation tableId = table.id();
            source.sendSuccess(() -> Component.translatable(KEY + "list.entry", lineIndex, tableId.toString(),
                    LootTableNames.translationKey(tableId), LootTableNames.fallbackName(tableId),
                    LootTableNames.resolveDisplayName(tableId)), false);
        }
        return catalog.size();
    }

    private static int status(CommandSourceStack source) {
        ArchaeologyJournalServerCatalog.ensureLoaded(source.getServer());
        var tables = ArchaeologyJournalServerCatalog.staticTables().keySet();
        var simulated = ArchaeologyJournalServerCatalog.simulatedTables().keySet();
        long ready = tables.stream().filter(simulated::contains).count();
        LootProbabilitySimulationWorker worker = LootProbabilitySimulationWorker.get();
        Component queue = Component.translatable(KEY + (worker != null && worker.isBusy() ? "busy" : "idle"));
        source.sendSuccess(() -> Component.translatable(KEY + "status", queue, ready, tables.size()), false);
        return 1;
    }

    private static int rebuild(CommandSourceStack source) {
        ArchaeologyJournalServerCatalog.ensureLoaded(source.getServer());
        int total = ArchaeologyJournalServerCatalog.staticTables().size();
        Constants.LOG.info(Constants.LOG_TAG + "[catalog-rebuild] tables={}", total);
        JournalCatalogHandler.forceFlushCatalog(source.getServer());
        source.sendSuccess(() -> Component.translatable(KEY + "rebuild.started", total), true);
        return 1;
    }
}
