package com.meteorite.unsuspiciousblock.command;

import com.meteorite.unsuspiciousblock.cat.CatFavorManager;
import com.meteorite.unsuspiciousblock.cat.state.CatFavorState;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 猫之恩惠调试子指令树 —— 直接操作玩家恩惠值，便于实机测试各项阈值能力。
 * <p>
 * 子指令：
 * <ul>
 *   <li>{@code favor add <amount>} —— 在当前恩惠值基础上增减指定量（可为负，自动 clamp 到 0-100）</li>
 *   <li>{@code favor set <amount>} —— 直接设置为指定值（仅允许 0-100）</li>
 *   <li>{@code favor get} —— 查询当前恩惠值</li>
 *   <li>{@code favor reset} —— 重置为 0 并同步</li>
 * </ul>
 * 调试指令不校验持有「猫之手」，直接操作玩家持久化状态并同步到客户端。
 */
public final class CatFavorDebugCommand {

    private static final String AMOUNT_ARG = "amount";

    private CatFavorDebugCommand() {
    }

    // 构建 favor 子树
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("favor")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("add")
                        .then(Commands.argument(AMOUNT_ARG, IntegerArgumentType.integer())
                                .executes(CatFavorDebugCommand::addFavor)))
                .then(Commands.literal("set")
                        .then(Commands.argument(AMOUNT_ARG, IntegerArgumentType.integer(0, 100))
                                .executes(CatFavorDebugCommand::setFavor)))
                .then(Commands.literal("get")
                        .executes(CatFavorDebugCommand::getFavor))
                .then(Commands.literal("reset")
                        .executes(CatFavorDebugCommand::resetFavor));
    }

    // add：在当前恩惠值基础上增减指定量（可为负）
    private static int addFavor(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int amount = IntegerArgumentType.getInteger(context, AMOUNT_ARG);
        CatFavorState state = CatFavorManager.getState(player);
        if (state == null) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.favor.error.state_unavailable"));
            return 0;
        }
        state.addFavor(amount);
        CatFavorManager.sync(player);
        final int current = state.getFavor();
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.unsuspiciousblock.usb.favor.add.success", amount, current), true);
        return current;
    }

    // set：直接设置恩惠值（0-100）
    private static int setFavor(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int amount = IntegerArgumentType.getInteger(context, AMOUNT_ARG);
        CatFavorState state = CatFavorManager.getState(player);
        if (state == null) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.favor.error.state_unavailable"));
            return 0;
        }
        state.setFavor(amount);
        CatFavorManager.sync(player);
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.unsuspiciousblock.usb.favor.set.success", amount), true);
        return amount;
    }

    // get：查询当前恩惠值
    private static int getFavor(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        CatFavorState state = CatFavorManager.getState(player);
        if (state == null) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.favor.error.state_unavailable"));
            return 0;
        }
        final int current = state.getFavor();
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.unsuspiciousblock.usb.favor.get.success", current), false);
        return current;
    }

    // reset：清零恩惠值并同步
    private static int resetFavor(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        CatFavorState state = CatFavorManager.getState(player);
        if (state == null) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.favor.error.state_unavailable"));
            return 0;
        }
        state.clear();
        CatFavorManager.sync(player);
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.unsuspiciousblock.usb.favor.reset.success"), true);
        return 0;
    }
}
