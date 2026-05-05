package com.meteorite.unsuspiciousblock.command;

import com.meteorite.unsuspiciousblock.journal.ArchaeologyJournalStateHolder;
import com.meteorite.unsuspiciousblock.network.ArchaeologyJournalNetwork;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** 清除玩家考古数据指令 */
public final class ClearArchaeologyDataCommand {

    private ClearArchaeologyDataCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal("unsuspiciousblock")
                .then(Commands.literal("clear_data")
                        .requires(source -> source.hasPermission(0))
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            ServerPlayer player = source.getPlayerOrException();
                            if (player instanceof ArchaeologyJournalStateHolder holder) {
                                holder.unsuspiciousblock$getArchaeologyJournalState().clear();
                                ArchaeologyJournalNetwork.syncState(player);
                                source.sendSuccess(
                                        () -> Component.translatable("command.unsuspiciousblock.clear_data.success"),
                                        false);
                                return 1;
                            }
                            return 0;
                        }));

        dispatcher.register(builder);
    }
}
