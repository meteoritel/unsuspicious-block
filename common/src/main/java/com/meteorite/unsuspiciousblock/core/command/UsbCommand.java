package com.meteorite.unsuspiciousblock.core.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * 管理员命令入口，统一提供权限检查与 Brigadier 用法导航。
 */
public final class UsbCommand {
    private UsbCommand() {
    }

    /**
     * 注册共用命令树；全部子树继承根节点的 OP 2 权限。
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("usb")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    CommandSourceStack source = context.getSource();
                    for (String usage : dispatcher.getAllUsage(dispatcher.getRoot().getChild("usb"), source, true)) {
                        source.sendSuccess(() -> Component.literal("/usb " + usage), false);
                    }
                    return 1;
                })
                .then(JournalCommand.build())
                .then(CatCommand.build())
                .then(ShimmerCommand.build()));
    }
}
