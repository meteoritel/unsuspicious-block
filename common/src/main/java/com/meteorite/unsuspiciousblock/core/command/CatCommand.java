package com.meteorite.unsuspiciousblock.core.command;

import com.meteorite.unsuspiciousblock.core.cat.CatFavorManager;
import com.meteorite.unsuspiciousblock.core.cat.state.CatBondLedger;
import com.meteorite.unsuspiciousblock.core.cat.state.CatFavorState;
import com.meteorite.unsuspiciousblock.core.cat.state.CatFavorView;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 猫族羁绊、完整关系重置与猫国灵体命令。
 */
public final class CatCommand {
    private static final String AMOUNT_ARG = "amount";
    private static final String KEY = "command.unsuspiciousblock.usb.cat.";
    private static final SimpleCommandExceptionType STATE_UNAVAILABLE = new SimpleCommandExceptionType(
            Component.translatable(KEY + "error.state_unavailable"));

    private CatCommand() {
    }

    /**
     * 构建猫族命令子树；标量调整保留关系是否已建立的状态。
     */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("cat")
                .then(Commands.literal("bond")
                        .then(Commands.literal("get").executes(CatCommand::getBond))
                        .then(Commands.literal("set")
                                .then(Commands.argument(AMOUNT_ARG, IntegerArgumentType.integer(
                                                CatFavorState.MIN_FAVOR, CatFavorState.MAX_FAVOR))
                                        .executes(CatCommand::setBond)))
                        .then(Commands.literal("add")
                                .then(Commands.argument(AMOUNT_ARG, IntegerArgumentType.integer())
                                        .executes(CatCommand::addBond))))
                .then(Commands.literal("reset").executes(CatCommand::reset))
                .then(SpiritCatCommand.build());
    }

    private static CatFavorView requireState(ServerPlayer player) throws CommandSyntaxException {
        CatFavorView state = CatFavorManager.view(player);
        if (state == null) throw STATE_UNAVAILABLE.create();
        return state;
    }

    private static int getBond(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        int current = requireState(context.getSource().getPlayerOrException()).getCatBond();
        context.getSource().sendSuccess(() -> Component.translatable(KEY + "bond.get.success", current), false);
        return current;
    }

    private static int setBond(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        requireState(player);
        int amount = IntegerArgumentType.getInteger(context, AMOUNT_ARG);
        CatFavorManager.apply(player, new CatBondLedger.SetBond(amount));
        context.getSource().sendSuccess(() -> Component.translatable(KEY + "bond.set.success", amount), true);
        return 1;
    }

    private static int addBond(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        CatFavorView state = requireState(player);
        int amount = IntegerArgumentType.getInteger(context, AMOUNT_ARG);
        // 输入允许完整 int 范围，先在 long 中相加，避免溢出后向相反方向夹取。
        int target = Math.clamp((long) state.getCatBond() + amount,
                CatFavorState.MIN_FAVOR, CatFavorState.MAX_FAVOR);
        CatFavorManager.apply(player, new CatBondLedger.SetBond(target));
        context.getSource().sendSuccess(() -> Component.translatable(KEY + "bond.add.success", amount, target), true);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        requireState(player);
        CatFavorManager.apply(player, CatBondLedger.Event.RESET);
        context.getSource().sendSuccess(() -> Component.translatable(KEY + "reset.success"), true);
        return 1;
    }
}
