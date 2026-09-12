package com.meteorite.unsuspiciousblock.command;

import com.meteorite.unsuspiciousblock.entity.ShimmerEntity;
import com.meteorite.unsuspiciousblock.pan.ShimmerSpawnService;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/***
 * 闪烁的光调试子指令树——在指定坐标铺设其绑定的水方块，并生成淘洗点。
 * <p>
 * 坐标参数指向<b>绑定的水方块</b>所在位置（而非实体本身）：指令会先把该处替换为水源方块，
 * 再生成附着于其水面的闪烁的光，因此可以在陆地上直接搭建测试点。生成流程复用自然生成
 * 使用的 {@link ShimmerSpawnService#spawnShimmer}，账本登记、初始淘洗次数与入水演出与
 * 正式生成的实体完全一致（间距校验与每维度上限不参与判定，便于集中布置测试样本）。
 * <p>
 * 子指令：{@code shimmer spawn <pos> [natural|worldgen]}——省略来源时按自然生成处理。
 * 自然生成来源带随机寿命并计入每维度上限，世界生成来源不消散也不计入上限。
 * <p>
 * 清理已生成的调试样本可直接使用原版指令：{@code /kill @e[type=unsuspiciousblock:shimmer]}，
 * 实体消散时会自行从账本注销。
 */
public final class ShimmerDebugCommand {
    private static final String POS_ARG = "pos";
    private static final String SOURCE_ARG = "source";
    // 来源名称——与指令输入一致地使用小写
    private static final String SOURCE_NATURAL = "natural";
    private static final String SOURCE_WORLDGEN = "worldgen";
    private static final List<String> SOURCES = List.of(SOURCE_NATURAL, SOURCE_WORLDGEN);

    private ShimmerDebugCommand() {
    }

    // 构建 shimmer 子树
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("shimmer")
                .then(Commands.literal("spawn")
                        .then(Commands.argument(POS_ARG, BlockPosArgument.blockPos())
                                .executes(context -> spawn(context, true))
                                .then(Commands.argument(SOURCE_ARG, StringArgumentType.word())
                                        .suggests(ShimmerDebugCommand::suggestSources)
                                        .executes(ShimmerDebugCommand::spawnWithSource))));
    }

    // 带来源参数的变体：解析来源名称后再走统一生成流程
    private static int spawnWithSource(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String source = StringArgumentType.getString(context, SOURCE_ARG).toLowerCase(Locale.ROOT);
        if (!SOURCES.contains(source)) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.shimmer.error.unknown_source", source));
            return 0;
        }
        return spawn(context, SOURCE_NATURAL.equals(source));
    }

    // 在指定水方块位置生成闪烁的光
    private static int spawn(CommandContext<CommandSourceStack> context, boolean natural) throws CommandSyntaxException {
        ServerLevel level = context.getSource().getLevel();
        BlockPos waterPos = BlockPosArgument.getBlockPos(context, POS_ARG);
        // 上方必须是空气：否则实体在生成后的第一个 tick 就会判定绑定水方块失效而立刻消散
        if (!level.getBlockState(waterPos.above()).isAir()) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.shimmer.error.blocked_above", waterPos.toShortString()));
            return 0;
        }
        // 铺设绑定水方块；已是水时跳过，避免多余的方块更新与流体重算
        if (!level.getBlockState(waterPos).is(Blocks.WATER)) {
            level.setBlockAndUpdate(waterPos, Blocks.WATER.defaultBlockState());
        }

        ShimmerEntity shimmer = ShimmerSpawnService.spawnShimmer(level, waterPos, natural);
        if (shimmer == null) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.shimmer.error.spawn_failed", waterPos.toShortString()));
            return 0;
        }
        String source = natural ? SOURCE_NATURAL : SOURCE_WORLDGEN;
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.unsuspiciousblock.usb.shimmer.spawn.success", waterPos.toShortString(), source,
                shimmer.getPanRemaining()), true);
        return 1;
    }

    // 来源名称补全
    private static CompletableFuture<Suggestions> suggestSources(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(SOURCES, builder);
    }
}
