package com.meteorite.unsuspiciousblock.command;

import com.meteorite.unsuspiciousblock.entity.ShimmerEntity;
import com.meteorite.unsuspiciousblock.pan.ShimmerSpawnService;
import com.meteorite.unsuspiciousblock.pan.ShimmerSpawnStatistics;
import com.meteorite.unsuspiciousblock.pan.ShimmerLedger;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.world.level.ChunkPos;
import java.util.UUID;
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
    private static final String SOURCE_SPECIAL = "special";
    private static final List<String> SOURCES = List.of(SOURCE_NATURAL, SOURCE_WORLDGEN, SOURCE_SPECIAL);

    private ShimmerDebugCommand() {
    }

    // 构建 shimmer 子树
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("shimmer")
                .requires(source -> source.hasPermission(2))
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("help").executes(context -> help(context.getSource())))
                .then(Commands.literal("attempt").executes(context -> attempt(context.getSource())))
                .then(buildClear())
                .then(Commands.literal("rate")
                        .executes(context -> ShimmerSpawnStatistics.status(context.getSource(), false))
                        .then(Commands.literal("start")
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 86400))
                                        .executes(context -> ShimmerSpawnStatistics.start(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "seconds"),
                                                Services.PANNING_CONFIG.getSpawnIntervalTicks()))
                                        .then(Commands.argument("intervalTicks", IntegerArgumentType.integer(1, 72000))
                                                .executes(context -> ShimmerSpawnStatistics.start(context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "seconds"),
                                                        IntegerArgumentType.getInteger(context, "intervalTicks"))))))
                        .then(Commands.literal("status")
                                .executes(context -> ShimmerSpawnStatistics.status(context.getSource(), false)))
                        .then(Commands.literal("stop")
                                .executes(context -> ShimmerSpawnStatistics.status(context.getSource(), true))))
                .then(Commands.literal("stats")
                        .executes(context -> stats(context.getSource(), false))
                        .then(Commands.literal("all").executes(context -> stats(context.getSource(), true))))
                .then(Commands.literal("chunk").executes(context -> chunk(context.getSource())))
                .then(Commands.literal("expire")
                        .then(Commands.argument("uuid", UuidArgument.uuid())
                                .executes(context -> expire(context.getSource(), UuidArgument.getUuid(context, "uuid")))))
                .then(Commands.literal("cooldown")
                        .then(Commands.literal("set")
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 86400))
                                        .executes(context -> cooldown(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "seconds")))))
                        .then(Commands.literal("clear")
                                .executes(context -> cooldown(context.getSource(), 0))))
                .then(Commands.literal("spawn")
                        .then(Commands.argument(POS_ARG, BlockPosArgument.blockPos())
                                .executes(context -> spawn(context, true))
                                .then(Commands.argument(SOURCE_ARG, StringArgumentType.word())
                                        .suggests(ShimmerDebugCommand::suggestSources)
                                        .executes(ShimmerDebugCommand::spawnWithSource))));
    }

    // 类型用字面量校验；省略范围表示当前维度，显式维度支持原版补全。
    private static LiteralArgumentBuilder<CommandSourceStack> buildClear() {
        var clear = Commands.literal("clear");
        for (String type : List.of(SOURCE_NATURAL, SOURCE_WORLDGEN, SOURCE_SPECIAL, "all")) {
            clear.then(Commands.literal(type)
                    .executes(context -> clear(context.getSource(), type, List.of(context.getSource().getLevel())))
                    .then(Commands.literal("current")
                            .executes(context -> clear(context.getSource(), type, List.of(context.getSource().getLevel()))))
                    .then(Commands.literal("all")
                            .executes(context -> clear(context.getSource(), type, context.getSource().getServer().getAllLevels())))
                    .then(Commands.literal("dimension")
                            .then(Commands.argument("dimension", DimensionArgument.dimension())
                                    .executes(context -> clear(context.getSource(), type,
                                            List.of(DimensionArgument.getDimension(context, "dimension")))))));
        }
        return clear;
    }

    private static int clear(CommandSourceStack source, String type, Iterable<ServerLevel> levels) {
        ShimmerLedger.Source filter = type.equals("all") ? null
                : ShimmerLedger.Source.valueOf(type.toUpperCase(Locale.ROOT));
        int total = 0;
        for (ServerLevel level : levels) {
            var result = ShimmerSpawnService.clear(level, filter);
            total += result.loaded() + result.deferred();
            source.sendSuccess(() -> Component.translatable("command.unsuspiciousblock.usb.shimmer.clear.result",
                    level.dimension().location().toString(),
                    Component.translatable("command.unsuspiciousblock.usb.shimmer.clear.type." + type),
                    result.loaded(), result.deferred(), result.pendingWorldgen()), true);
        }
        return total;
    }

    // 位置来自命令执行源，也支持 execute positioned / in 指定测试地点和维度。
    private static BlockPos currentPos(CommandSourceStack source) {
        return BlockPos.containing(source.getPosition());
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("command.unsuspiciousblock.usb.shimmer.debug.help"), false);
        return 1;
    }

    // 真实尝试会生成实体，但不会铺水、跳过冷却或突破上限。
    private static int attempt(CommandSourceStack source) {
        ChunkPos chunk = new ChunkPos(currentPos(source));
        String result = ShimmerSpawnService.debugAttemptInChunk(source.getLevel(), chunk);
        source.sendSuccess(() -> Component.translatable("command.unsuspiciousblock.usb.shimmer.debug.attempt",
                chunk.x, chunk.z, Component.translatable("command.unsuspiciousblock.usb.shimmer.debug.attempt." + result)), false);
        return result.equals("success") ? 1 : 0;
    }

    // 过期标记不保存坐标；按 UUID 查询已加载实体区分待清理状态，绝不强制加载。
    private static int stats(CommandSourceStack source, boolean all) {
        int totalNatural = 0;
        Iterable<ServerLevel> levels = all ? source.getServer().getAllLevels() : List.of(source.getLevel());
        for (ServerLevel level : levels) {
            ShimmerLedger ledger = ShimmerLedger.of(level);
            int expiredLoaded = 0;
            var expired = ledger.expiredSnapshot();
            for (UUID uuid : expired) {
                if (level.getEntity(uuid) != null) expiredLoaded++;
            }
            int pendingUnloaded = expired.size() - expiredLoaded;
            int loaded = expiredLoaded;
            int natural = ledger.countNatural();
            totalNatural += natural;
            source.sendSuccess(() -> Component.translatable("command.unsuspiciousblock.usb.shimmer.debug.stats",
                    level.dimension().location().toString(), natural, Services.PANNING_CONFIG.getMaxNaturalPerDimension(),
                    ledger.countWorldgen(), expired.size(), pendingUnloaded, loaded,
                    Math.max(0L, ledger.getNextAttempt() - level.getGameTime())), false);
        }
        return totalNatural;
    }

    // 最多展示二十条，避免集中布置调试样本后刷屏。
    private static int chunk(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        ChunkPos chunk = new ChunkPos(currentPos(source));
        ShimmerLedger ledger = ShimmerLedger.of(level);
        var entries = ledger.entriesInChunk(chunk);
        source.sendSuccess(() -> Component.translatable("command.unsuspiciousblock.usb.shimmer.debug.chunk",
                chunk.x, chunk.z, level.isLoaded(chunk.getWorldPosition()),
                ledger.cooldownRemaining(chunk, level.getGameTime()), ledger.isChunkRolled(chunk), entries.size()), false);
        int shown = 0;
        for (var entry : entries.entrySet()) {
            if (shown++ >= 20) break;
            UUID uuid = entry.getKey();
            var point = entry.getValue();
            long remaining = point.source().hasLifetime()
                    ? Math.max(0L, ledger.expirationOf(uuid, level.getGameTime()) - level.getGameTime()) : -1L;
            source.sendSuccess(() -> Component.translatable("command.unsuspiciousblock.usb.shimmer.debug.entry",
                    uuid.toString(), point.pos().toShortString(), point.source().name().toLowerCase(Locale.ROOT),
                    remaining, level.getEntity(uuid) != null), false);
        }
        if (entries.size() > 20) source.sendSuccess(() -> Component.translatable(
                "command.unsuspiciousblock.usb.shimmer.debug.truncated", entries.size() - 20), false);
        return entries.size();
    }

    // 可用离开区块前记录的 UUID 测试卸载到期；世界生成点拒绝过期操作。
    private static int expire(CommandSourceStack source, UUID uuid) {
        ServerLevel level = source.getLevel();
        if (!ShimmerLedger.of(level).expireNatural(uuid)) {
            source.sendFailure(Component.translatable("command.unsuspiciousblock.usb.shimmer.debug.expire_failed", uuid.toString()));
            return 0;
        }
        if (level.getEntity(uuid) instanceof ShimmerEntity shimmer) shimmer.discard();
        source.sendSuccess(() -> Component.translatable("command.unsuspiciousblock.usb.shimmer.debug.expired", uuid.toString()), true);
        return 1;
    }

    private static int cooldown(CommandSourceStack source, int seconds) {
        ServerLevel level = source.getLevel();
        ShimmerLedger ledger = ShimmerLedger.of(level);
        if (seconds == 0) {
            int removed = ledger.clearHarvestCooldown(currentPos(source));
            source.sendSuccess(() -> Component.translatable("command.unsuspiciousblock.usb.shimmer.debug.cooldown_cleared", removed), true);
            return removed;
        }
        ledger.startHarvestCooldown(currentPos(source), level.getGameTime(), seconds * 20);
        source.sendSuccess(() -> Component.translatable("command.unsuspiciousblock.usb.shimmer.debug.cooldown_set", seconds), true);
        return 1;
    }

    // 带来源参数的变体：解析来源名称后再走统一生成流程
    private static int spawnWithSource(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String source = StringArgumentType.getString(context, SOURCE_ARG).toLowerCase(Locale.ROOT);
        if (!SOURCES.contains(source)) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.shimmer.error.unknown_source", source));
            return 0;
        }
        return spawn(context, switch (source) {
            case SOURCE_WORLDGEN -> ShimmerSpawnService.SpawnTrigger.WORLDGEN;
            case SOURCE_SPECIAL -> ShimmerSpawnService.SpawnTrigger.SPECIAL;
            default -> ShimmerSpawnService.SpawnTrigger.MANUAL;
        });
    }

    // 在指定水方块位置生成闪烁的光
    private static int spawn(CommandContext<CommandSourceStack> context, boolean natural) throws CommandSyntaxException {
        return spawn(context, natural ? ShimmerSpawnService.SpawnTrigger.MANUAL : ShimmerSpawnService.SpawnTrigger.WORLDGEN);
    }

    private static int spawn(CommandContext<CommandSourceStack> context, ShimmerSpawnService.SpawnTrigger trigger) throws CommandSyntaxException {
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

        ShimmerEntity shimmer = ShimmerSpawnService.spawnShimmer(level, waterPos, trigger);
        if (shimmer == null) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.shimmer.error.spawn_failed", waterPos.toShortString()));
            return 0;
        }
        String source = shimmer.getSpawnSource().name().toLowerCase(Locale.ROOT);
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
