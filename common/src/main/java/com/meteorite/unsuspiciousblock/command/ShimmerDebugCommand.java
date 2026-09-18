package com.meteorite.unsuspiciousblock.command;

import com.meteorite.unsuspiciousblock.entity.ShimmerEntity;
import com.meteorite.unsuspiciousblock.pan.ShimmerLedger;
import com.meteorite.unsuspiciousblock.pan.ShimmerSpawnService;
import com.meteorite.unsuspiciousblock.pan.ShimmerSpawnStatistics;
import com.meteorite.unsuspiciousblock.pan.variant.ShimmerVariant;
import com.meteorite.unsuspiciousblock.pan.variant.ShimmerVariants;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/***
 * 闪烁的光调试子指令树——在指定坐标铺设该变体的依附方块，并生成淘洗点。
 * <p>
 * 坐标参数指向<b>绑定的依附方块</b>所在位置（而非实体本身）：指令会先把该处替换为该变体的
 * 代表介质（水域为水源、岩浆域为岩浆源），再生成附着于其液面的淘洗点，因此可以在陆地上直接搭建测试点。
 * 生成流程复用自然生成使用的 {@link ShimmerSpawnService#spawnShimmer}，账本登记、初始淘洗次数
 * 与入液演出与正式生成的实体完全一致（间距校验与每维度上限不参与判定，便于集中布置测试样本）。
 * <p>
 * 变体与来源是两条正交的过滤轴，子指令用同一套名字引用它们，变体名取自注册表路径名：
 * <ul>
 *     <li>{@code shimmer spawn <坐标> [<变体>|<来源>] [<来源>] [frozen]}——省略时按水域变体 + 手动来源处理。
 *         第一位同时接受变体名与来源名：给出变体名时可再跟一个来源名，给出来源名时按水域变体处理。
 *         {@code frozen} 分支把该处铺成冰块，用于验证冻结相位下的依附、波光与淘洗判定。</li>
 *     <li>{@code shimmer clear <变体|<来源>|all> [<来源>|all] [current|all|dimension <维度ID>]}。</li>
 *     <li>{@code shimmer stats [<变体>] [all]}——省略变体时统计该维度的全部变体。</li>
 * </ul>
 * 清理已生成的调试样本也可直接使用原版指令：{@code /kill @e[type=unsuspiciousblock:shimmer]}，
 * 实体消散时会自行从账本注销。
 */
public final class ShimmerDebugCommand {
    private static final String POS_ARG = "pos";
    private static final String FROZEN_ARG = "frozen";
    private static final String ALL = "all";
    private static final String CURRENT = "current";
    private static final String DIMENSION_ARG = "dimension";
    // 来源名称——与指令输入一致地使用小写
    private static final String SOURCE_NATURAL = "natural";
    private static final String SOURCE_WORLDGEN = "worldgen";
    private static final String SOURCE_SPECIAL = "special";
    private static final List<String> SOURCES = List.of(SOURCE_NATURAL, SOURCE_WORLDGEN, SOURCE_SPECIAL);

    private ShimmerDebugCommand() {
    }

    // 变体名与来源名共用同一层字面量位置，必须互不重名，否则会静默遮蔽其中一个
    private static void checkNameCollisions() {
        for (String path : ShimmerVariants.paths()) {
            if (SOURCES.contains(path) || path.equals(ALL) || path.equals(FROZEN_ARG) || path.equals(CURRENT)) {
                throw new IllegalStateException("Shimmer variant name collides with a command keyword: " + path);
            }
        }
    }

    // 构建 shimmer 子树
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        checkNameCollisions();
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
                .then(buildStats())
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
                .then(buildSpawn());
    }

    // stats [<变体>] [all]——省略变体时统计该维度的全部变体
    private static LiteralArgumentBuilder<CommandSourceStack> buildStats() {
        var stats = Commands.literal("stats")
                .executes(context -> stats(context.getSource(), null, false))
                .then(Commands.literal(ALL).executes(context -> stats(context.getSource(), null, true)));
        for (String path : ShimmerVariants.paths()) {
            ShimmerVariant variant = ShimmerVariants.byPath(path);
            stats.then(Commands.literal(path)
                    .executes(context -> stats(context.getSource(), variant, false))
                    .then(Commands.literal(ALL).executes(context -> stats(context.getSource(), variant, true))));
        }
        return stats;
    }

    // spawn <坐标> [<变体>|<来源>] [<来源>] [frozen]
    private static LiteralArgumentBuilder<CommandSourceStack> buildSpawn() {
        var posArgument = Commands.argument(POS_ARG, BlockPosArgument.blockPos())
                .executes(context -> spawn(context, ShimmerVariants.WATER,
                        ShimmerSpawnService.SpawnTrigger.MANUAL, false))
                .then(Commands.literal(FROZEN_ARG)
                        .executes(context -> spawn(context, ShimmerVariants.WATER,
                                ShimmerSpawnService.SpawnTrigger.MANUAL, true)));
        // 第一位给出来源名时按水域变体处理，保持原有语法可用
        for (String sourceName : SOURCES) {
            posArgument.then(Commands.literal(sourceName)
                    .executes(context -> spawn(context, ShimmerVariants.WATER, triggerOf(sourceName), false))
                    .then(Commands.literal(FROZEN_ARG)
                            .executes(context -> spawn(context, ShimmerVariants.WATER, triggerOf(sourceName), true))));
        }
        for (String path : ShimmerVariants.paths()) {
            ShimmerVariant variant = ShimmerVariants.byPath(path);
            var variantNode = Commands.literal(path)
                    .executes(context -> spawn(context, variant, ShimmerSpawnService.SpawnTrigger.MANUAL, false))
                    .then(Commands.literal(FROZEN_ARG)
                            .executes(context -> spawn(context, variant,
                                    ShimmerSpawnService.SpawnTrigger.MANUAL, true)));
            for (String sourceName : SOURCES) {
                variantNode.then(Commands.literal(sourceName)
                        .executes(context -> spawn(context, variant, triggerOf(sourceName), false))
                        .then(Commands.literal(FROZEN_ARG)
                                .executes(context -> spawn(context, variant, triggerOf(sourceName), true))));
            }
            posArgument.then(variantNode);
        }
        return Commands.literal("spawn").then(posArgument);
    }

    // clear <变体|<来源>|all> [<来源>|all] [current|all|dimension <维度ID>]
    private static LiteralArgumentBuilder<CommandSourceStack> buildClear() {
        var clear = Commands.literal("clear");
        // 第一位给出来源名时按「全部变体 + 该来源」处理，保持原有语法可用
        for (String sourceName : SOURCES) {
            var node = Commands.literal(sourceName);
            addClearScopes(node, null, sourceName);
            clear.then(node);
        }
        var allNode = Commands.literal(ALL);
        addClearScopes(allNode, null, ALL);
        clear.then(allNode);
        // 第一位给出变体名时，第二位可选来源过滤
        for (String path : ShimmerVariants.paths()) {
            ResourceLocation variantId = ShimmerVariants.byPath(path).id();
            var variantNode = Commands.literal(path);
            addClearScopes(variantNode, variantId, ALL);
            for (String sourceName : SOURCES) {
                var sourceNode = Commands.literal(sourceName);
                addClearScopes(sourceNode, variantId, sourceName);
                variantNode.then(sourceNode);
            }
            var variantAllNode = Commands.literal(ALL);
            addClearScopes(variantAllNode, variantId, ALL);
            variantNode.then(variantAllNode);
            clear.then(variantNode);
        }
        return clear;
    }

    // 附加清除范围：省略表示当前维度，显式维度支持原版补全
    private static void addClearScopes(LiteralArgumentBuilder<CommandSourceStack> node,
                                       @Nullable ResourceLocation variantId, String type) {
        node.executes(context -> clear(context.getSource(), variantId, type,
                List.of(context.getSource().getLevel())));
        node.then(Commands.literal(CURRENT).executes(context -> clear(context.getSource(), variantId, type,
                List.of(context.getSource().getLevel()))));
        node.then(Commands.literal(ALL).executes(context -> clear(context.getSource(), variantId, type,
                context.getSource().getServer().getAllLevels())));
        node.then(Commands.literal(DIMENSION_ARG)
                .then(Commands.argument(DIMENSION_ARG, DimensionArgument.dimension())
                        .executes(context -> clear(context.getSource(), variantId, type,
                                List.of(DimensionArgument.getDimension(context, DIMENSION_ARG))))));
    }

    private static ShimmerSpawnService.SpawnTrigger triggerOf(String sourceName) {
        return switch (sourceName) {
            case SOURCE_WORLDGEN -> ShimmerSpawnService.SpawnTrigger.WORLDGEN;
            case SOURCE_SPECIAL -> ShimmerSpawnService.SpawnTrigger.SPECIAL;
            default -> ShimmerSpawnService.SpawnTrigger.MANUAL;
        };
    }

    // variantId 为 null 表示全部变体，type 为 all 表示全部来源
    private static int clear(CommandSourceStack source, @Nullable ResourceLocation variantId,
                             String type, Iterable<ServerLevel> levels) {
        ShimmerLedger.Source filter = type.equals(ALL) ? null
                : ShimmerLedger.Source.valueOf(type.toUpperCase(Locale.ROOT));
        Component variantLabel = variantLabel(variantId);
        int total = 0;
        for (ServerLevel level : levels) {
            var result = ShimmerSpawnService.clear(level, filter, variantId);
            total += result.loaded() + result.deferred();
            source.sendSuccess(() -> Component.translatable("command.unsuspiciousblock.usb.shimmer.clear.result",
                    level.dimension().location().toString(), variantLabel,
                    Component.translatable("command.unsuspiciousblock.usb.shimmer.clear.type." + type),
                    result.loaded(), result.deferred()), true);
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

    // 真实尝试会生成实体，但不会铺介质、跳过冷却或突破上限；幽微的光没有运行时自然生成入口。
    private static int attempt(CommandSourceStack source) {
        ChunkPos chunk = new ChunkPos(currentPos(source));
        String result = ShimmerSpawnService.debugAttemptInChunk(source.getLevel(), chunk);
        source.sendSuccess(() -> Component.translatable("command.unsuspiciousblock.usb.shimmer.debug.attempt",
                chunk.x, chunk.z, Component.translatable("command.unsuspiciousblock.usb.shimmer.debug.attempt." + result)), false);
        return result.equals("success") ? 1 : 0;
    }

    // variant 为 null 时汇总该维度的全部变体
    private static int stats(CommandSourceStack source, @Nullable ShimmerVariant variant, boolean all) {
        Iterable<ServerLevel> levels = all ? source.getServer().getAllLevels() : List.of(source.getLevel());
        ResourceLocation variantFilter = variant == null ? null : variant.id();
        Component variantLabel = variantLabel(variantFilter);
        int totalNatural = 0;
        for (ServerLevel level : levels) {
            ShimmerLedger ledger = ShimmerLedger.of(level);
            int expiredLoaded = 0;
            var expired = ledger.expiredSnapshot();
            for (UUID uuid : expired) {
                if (level.getEntity(uuid) != null) expiredLoaded++;
            }
            int pendingUnloaded = expired.size() - expiredLoaded;
            int loaded = expiredLoaded;
            int natural = variantFilter == null
                    ? ledger.countNaturalOfAllVariants()
                    : ledger.countNatural(variantFilter);
            totalNatural += natural;
            source.sendSuccess(() -> Component.translatable("command.unsuspiciousblock.usb.shimmer.debug.stats",
                    level.dimension().location().toString(), variantLabel, natural,
                    Services.PANNING_CONFIG.getMaxNaturalPerDimension(),
                    ledger.countSpecial(variantFilter), ledger.countWorldgen(variantFilter),
                    expired.size(), pendingUnloaded, loaded,
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
                ledger.cooldownRemaining(chunk, level.getGameTime()), entries.size()), false);
        int shown = 0;
        for (var entry : entries.entrySet()) {
            if (shown++ >= 20) break;
            UUID uuid = entry.getKey();
            var point = entry.getValue();
            long remaining = point.source().hasLifetime()
                    ? Math.max(0L, ledger.expirationOf(uuid, level.getGameTime()) - level.getGameTime()) : -1L;
            source.sendSuccess(() -> Component.translatable("command.unsuspiciousblock.usb.shimmer.debug.entry",
                    uuid.toString(), point.pos().toShortString(), point.variant().toString(),
                    point.source().name().toLowerCase(Locale.ROOT),
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

    // 在指定位置生成淘洗点：先铺该变体的代表介质（frozen 时改铺冰块），再生成附着实体
    private static int spawn(CommandContext<CommandSourceStack> context, ShimmerVariant variant,
                             ShimmerSpawnService.SpawnTrigger trigger, boolean frozen) throws CommandSyntaxException {
        ServerLevel level = context.getSource().getLevel();
        BlockPos mediumPos = BlockPosArgument.getBlockPos(context, POS_ARG);
        // 上方必须是空气：否则实体在生成后的第一个 tick 就会判定依附失效而立刻消散
        if (!level.getBlockState(mediumPos.above()).isAir()) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.shimmer.error.blocked_above",
                    mediumPos.toShortString(), variant.id().toString()));
            return 0;
        }
        // 铺设代表介质；已是目标状态时跳过，避免多余的方块更新与流体重算
        BlockState medium = frozen ? Blocks.ICE.defaultBlockState() : variant.anchor().mediumState();
        if (!level.getBlockState(mediumPos).equals(medium)) {
            level.setBlockAndUpdate(mediumPos, medium);
        }

        ShimmerEntity shimmer = ShimmerSpawnService.spawnShimmer(level, mediumPos, variant, trigger);
        if (shimmer == null) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.shimmer.error.spawn_failed", mediumPos.toShortString()));
            return 0;
        }
        String source = shimmer.getSpawnSource().name().toLowerCase(Locale.ROOT);
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.unsuspiciousblock.usb.shimmer.spawn.success", mediumPos.toShortString(),
                shimmer.getType().getDescription(), source, shimmer.getPanRemaining()), true);
        return 1;
    }

    // 变体标签——指定变体时直接展示 id，未指定时表示全部变体
    private static Component variantLabel(@Nullable ResourceLocation variantId) {
        return variantId == null
                ? Component.translatable("command.unsuspiciousblock.usb.shimmer.variant.all")
                : Component.literal(variantId.toString());
    }
}
