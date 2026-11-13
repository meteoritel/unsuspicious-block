package com.meteorite.unsuspiciousblock.core.command;

import com.meteorite.unsuspiciousblock.core.entity.ShimmerEntity;
import com.meteorite.unsuspiciousblock.core.pan.ShimmerLedger;
import com.meteorite.unsuspiciousblock.core.pan.ShimmerSpawnService;
import com.meteorite.unsuspiciousblock.core.pan.ShimmerSpawnStatistics;
import com.meteorite.unsuspiciousblock.core.pan.variant.ShimmerVariant;
import com.meteorite.unsuspiciousblock.core.pan.variant.ShimmerVariants;
import com.meteorite.unsuspiciousblock.platform.Services;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
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
import java.util.stream.Stream;

/**
 * 淘洗点样本、账本查询、清理与真实生成测试；坐标指向依附方块。
 */
public final class ShimmerCommand {
    private static final String POS_ARG = "pos";
    private static final String VARIANT_ARG = "variant";
    private static final String SOURCE_ARG = "source";
    private static final String SECONDS_ARG = "seconds";
    private static final String INTERVAL_ARG = "interval_ticks";
    private static final String ALL = "all";
    private static final String CURRENT = "current";
    private static final int MAX_SECONDS = 86400;
    private static final int MAX_INTERVAL_TICKS = 72000;
    private static final List<String> SOURCES = List.of("natural", "worldgen", "special");
    private static final String KEY = "command.unsuspiciousblock.usb.shimmer.";
    private static final DynamicCommandExceptionType UNKNOWN_VARIANT = new DynamicCommandExceptionType(
            value -> Component.translatable(KEY + "error.unknown_variant", value));
    private static final DynamicCommandExceptionType UNKNOWN_SOURCE = new DynamicCommandExceptionType(
            value -> Component.translatable(KEY + "error.unknown_source", value));
    private static final DynamicCommandExceptionType INVALID_FROZEN = new DynamicCommandExceptionType(
            value -> Component.translatable(KEY + "error.invalid_frozen", value));

    private ShimmerCommand() {
    }

    /**
     * 构建淘洗子树；筛选轴各占独立参数位置，权限由 /usb 根节点检查。
     */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("shimmer")
                .then(Commands.literal("spawn")
                        .then(Commands.argument(VARIANT_ARG, StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(ShimmerVariants.paths(), builder))
                                .then(Commands.argument(SOURCE_ARG, StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(SOURCES, builder))
                                        .then(Commands.argument(POS_ARG, BlockPosArgument.blockPos())
                                                .executes(ShimmerCommand::spawn)
                                                .then(Commands.literal("frozen").executes(ShimmerCommand::spawnFrozen))))))
                .then(Commands.literal("clear")
                        .then(variantFilterArgument()
                                .then(Commands.argument(SOURCE_ARG, StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                Stream.concat(SOURCES.stream(), Stream.of(ALL)), builder))
                                        .then(Commands.literal(CURRENT).executes(context -> clear(context, List.of(context.getSource().getLevel()))))
                                        .then(Commands.literal(ALL).executes(context -> clear(context, context.getSource().getServer().getAllLevels()))))))
                .then(Commands.literal("stats")
                        .then(variantFilterArgument()
                                .then(Commands.literal(CURRENT).executes(context -> stats(context, List.of(context.getSource().getLevel()))))
                                .then(Commands.literal(ALL).executes(context -> stats(context, context.getSource().getServer().getAllLevels())))))
                .then(Commands.literal("chunk").executes(context -> chunk(context.getSource())))
                .then(Commands.literal("attempt").executes(context -> attempt(context.getSource())))
                .then(Commands.literal("expire")
                        .then(Commands.argument("uuid", UuidArgument.uuid())
                                .executes(context -> expire(context.getSource(), UuidArgument.getUuid(context, "uuid")))))
                .then(Commands.literal("cooldown")
                        .then(Commands.argument(SECONDS_ARG, IntegerArgumentType.integer(0, MAX_SECONDS))
                                .executes(context -> cooldown(context.getSource(), IntegerArgumentType.getInteger(context, SECONDS_ARG)))))
                .then(buildRate());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRate() {
        return Commands.literal("rate")
                .then(Commands.literal("start")
                        .then(Commands.argument(SECONDS_ARG, IntegerArgumentType.integer(1, MAX_SECONDS))
                                .executes(context -> ShimmerSpawnStatistics.start(context.getSource(),
                                        IntegerArgumentType.getInteger(context, SECONDS_ARG), Services.PANNING_CONFIG.getSpawnIntervalTicks()))
                                .then(Commands.argument(INTERVAL_ARG, IntegerArgumentType.integer(1, MAX_INTERVAL_TICKS))
                                        .executes(context -> ShimmerSpawnStatistics.start(context.getSource(),
                                                IntegerArgumentType.getInteger(context, SECONDS_ARG),
                                                IntegerArgumentType.getInteger(context, INTERVAL_ARG))))))
                .then(Commands.literal("status").executes(context -> ShimmerSpawnStatistics.status(context.getSource(), false)))
                .then(Commands.literal("stop").executes(context -> ShimmerSpawnStatistics.status(context.getSource(), true)));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> variantFilterArgument() {
        return Commands.argument(VARIANT_ARG, StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                        Stream.concat(ShimmerVariants.paths().stream(), Stream.of(ALL)), builder));
    }

    private static ShimmerVariant requireVariant(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, VARIANT_ARG);
        if (!ShimmerVariants.paths().contains(name)) throw UNKNOWN_VARIANT.create(name);
        ShimmerVariant variant = ShimmerVariants.byPath(name);
        if (variant == null) throw UNKNOWN_VARIANT.create(name);
        return variant;
    }

    @Nullable
    private static ResourceLocation variantFilter(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return ALL.equals(StringArgumentType.getString(context, VARIANT_ARG)) ? null : requireVariant(context).id();
    }

    private static ShimmerLedger.Source requireSource(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, SOURCE_ARG);
        if (!SOURCES.contains(name)) throw UNKNOWN_SOURCE.create(name);
        return ShimmerLedger.Source.valueOf(name.toUpperCase(Locale.ROOT));
    }

    private static int clear(CommandContext<CommandSourceStack> context, Iterable<ServerLevel> levels) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ResourceLocation variantId = variantFilter(context);
        String type = StringArgumentType.getString(context, SOURCE_ARG);
        ShimmerLedger.Source filter = ALL.equals(type) ? null : requireSource(context);
        Component variantLabel = variantLabel(variantId);
        int total = 0;
        for (ServerLevel level : levels) {
            var result = ShimmerSpawnService.clear(level, filter, variantId);
            total += result.loaded() + result.deferred();
            source.sendSuccess(() -> Component.translatable(KEY + "clear.result",
                    level.dimension().location().toString(), variantLabel,
                    Component.translatable(KEY + "clear.type." + type), result.loaded(), result.deferred()), true);
        }
        return total;
    }

    // 位置来自命令执行源，也支持 execute positioned / in 指定测试地点和维度。
    private static BlockPos currentPos(CommandSourceStack source) {
        return BlockPos.containing(source.getPosition());
    }

    // 真实尝试会生成实体，但不会铺介质、跳过冷却或突破上限；幽微的光没有运行时自然生成入口。
    private static int attempt(CommandSourceStack source) {
        ChunkPos chunk = new ChunkPos(currentPos(source));
        String result = ShimmerSpawnService.debugAttemptInChunk(source.getLevel(), chunk);
        source.sendSuccess(() -> Component.translatable("command.unsuspiciousblock.usb.shimmer.debug.attempt",
                chunk.x, chunk.z, Component.translatable("command.unsuspiciousblock.usb.shimmer.debug.attempt." + result)), false);
        return result.equals("success") ? 1 : 0;
    }

    private static int stats(CommandContext<CommandSourceStack> context, Iterable<ServerLevel> levels) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ResourceLocation variantFilter = variantFilter(context);
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

    private static int spawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ShimmerVariant variant = requireVariant(context);
        return spawnOnMedium(context, variant, variant.anchor().mediumState());
    }

    private static int spawnFrozen(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ShimmerVariant variant = requireVariant(context);
        BlockState ice = Blocks.ICE.defaultBlockState();
        if (!variant.anchor().isAnchorBlock(ice)) throw INVALID_FROZEN.create(variant.id().toString());
        return spawnOnMedium(context, variant, ice);
    }

    private static int spawnOnMedium(CommandContext<CommandSourceStack> context, ShimmerVariant variant,
                                     BlockState medium) throws CommandSyntaxException {
        ShimmerSpawnService.SpawnTrigger trigger = switch (requireSource(context)) {
            case NATURAL -> ShimmerSpawnService.SpawnTrigger.MANUAL;
            case WORLDGEN -> ShimmerSpawnService.SpawnTrigger.WORLDGEN;
            case SPECIAL -> ShimmerSpawnService.SpawnTrigger.SPECIAL;
        };
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
