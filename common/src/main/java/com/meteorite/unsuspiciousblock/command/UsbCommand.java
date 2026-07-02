package com.meteorite.unsuspiciousblock.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** USB 调试指令入口，统一挂载 journal / ghost_cat / favor 三个子系统子树 */
public final class UsbCommand {
    private UsbCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("usb")
                .requires(source -> source.hasPermission(2))
                .then(JournalCommand.build())
                .then(GhostCatDebugCommand.build())
                .then(CatFavorDebugCommand.build()));
    }
}
