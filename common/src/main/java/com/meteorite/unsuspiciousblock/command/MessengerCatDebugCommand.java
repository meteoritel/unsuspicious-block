package com.meteorite.unsuspiciousblock.command;

import com.meteorite.unsuspiciousblock.cat.CatGiftService;
import com.meteorite.unsuspiciousblock.entity.MessengerCat;
import com.meteorite.unsuspiciousblock.entity.ModEntities;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.MessengerCatPhase;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.MorningGiftBehavior;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 幽灵猫调试指令 —— 以距离执行者最近的幽灵猫为操作对象，便于实机测试阶段机与行为策略。
 * <p>
 * 子指令：
 * <ul>
 *   <li>{@code spawn} —— 在玩家附近召唤一只幽灵猫（晨礼行为，目标=自己，跳过恩惠检查）</li>
 *   <li>{@code info} —— 输出最近幽灵猫的阶段、tick、坐标、目标等运行时状态</li>
 *   <li>{@code phase <阶段>} —— 强制切换最近幽灵猫的阶段（用于跳过等待验证后续流程）</li>
 *   <li>{@code discard} —— 立即移除最近幽灵猫</li>
 * </ul>
 */
public final class MessengerCatDebugCommand {

    private static final String PHASE_ARG = "phase";

    private MessengerCatDebugCommand() {
    }

    // 构建 ghost_cat 子树
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("messenger_cat")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("spawn")
                        .executes(MessengerCatDebugCommand::spawnNearPlayer))
                .then(Commands.literal("info")
                        .executes(MessengerCatDebugCommand::showNearestInfo))
                .then(Commands.literal("discard")
                        .executes(MessengerCatDebugCommand::discardNearest))
                .then(Commands.literal("phase")
                        .then(Commands.argument(PHASE_ARG, StringArgumentType.word())
                                .suggests(MessengerCatDebugCommand::suggestPhases)
                                .executes(MessengerCatDebugCommand::forcePhase)));
    }

    // ========== 子指令实现 ==========

    // spawn：在玩家附近召唤一只幽灵猫，注入晨礼行为（目标=自己）
    private static int spawnNearPlayer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (!(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        MessengerCat ghost = ModEntities.MESSENGER_CAT.get().create(level);
        if (ghost == null) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.ghost_cat.error.spawn_failed"));
            return 0;
        }
        double x = player.getX() + (player.getRandom().nextInt(7) - 3);
        double y = player.getY();
        double z = player.getZ() + (player.getRandom().nextInt(7) - 3);
        ghost.moveTo(x, y, z, player.getRandom().nextFloat() * 360.0F, 0.0F);
        // 记录召唤 Y 作为穿墙位移底部夹紧基准，防止掉到基岩层
        ghost.freezeSpawnY();
        // 调试指令跳过恩惠检查，直接以自己为目标注入晨礼行为
        ghost.assignBehavior(new MorningGiftBehavior(
                player.getUUID(), CatGiftService.GHOST_GIFT_LOOT_TABLE));
        level.addFreshEntity(ghost);
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.unsuspiciousblock.usb.ghost_cat.spawn.success",
                ghost.getId()), true);
        return 1;
    }

    // info：输出最近幽灵猫运行时状态
    private static int showNearestInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        MessengerCat ghost = findNearest(player);
        if (ghost == null) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.ghost_cat.error.not_found"));
            return 0;
        }
        String phaseName = ghost.getPhase().name();
        int phaseTicks = ghost.getPhaseTicks();
        int totalTicks = ghost.getTotalTicks();
        boolean hasBehavior = ghost.getBehavior() != null;
        String behaviorName = hasBehavior ? ghost.getBehavior().getClass().getSimpleName() : "null";
        String targetUuid = hasBehavior && ghost.getBehavior().getTargetUuid() != null
                ? ghost.getBehavior().getTargetUuid().toString() : "null";
        boolean hasLoot = hasBehavior && ghost.getBehavior().getLootTable() != null;
        double dist = Math.sqrt(ghost.distanceToSqr(player));
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.unsuspiciousblock.usb.ghost_cat.info.summary",
                ghost.getId(),
                String.format("%.2f", ghost.getX()), String.format("%.2f", ghost.getY()), String.format("%.2f", ghost.getZ()),
                phaseName, phaseTicks, totalTicks,
                behaviorName, targetUuid, hasLoot,
                String.format("%.2f", dist)), false);
        return 1;
    }

    // discard：立即移除最近幽灵猫
    private static int discardNearest(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        MessengerCat ghost = findNearest(player);
        if (ghost == null) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.ghost_cat.error.not_found"));
            return 0;
        }
        int id = ghost.getId();
        ghost.discard();
        final int finalId = id;
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.unsuspiciousblock.usb.ghost_cat.discard.success", finalId), true);
        return 1;
    }

    // phase：强制切换最近幽灵猫的阶段
    private static int forcePhase(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String phaseName = StringArgumentType.getString(context, PHASE_ARG);
        MessengerCatPhase phase = parsePhase(phaseName);
        if (phase == null) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.ghost_cat.error.unknown_phase", phaseName));
            return 0;
        }
        MessengerCat ghost = findNearest(player);
        if (ghost == null) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.ghost_cat.error.not_found"));
            return 0;
        }
        ghost.setPhase(phase);
        final String chosenName = phase.name();
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.unsuspiciousblock.usb.ghost_cat.phase.success",
                ghost.getId(), chosenName), true);
        return 1;
    }

    // ========== 辅助 ==========

    // 在玩家 64 格范围内寻找最近的幽灵猫
    private static MessengerCat findNearest(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return null;
        }
        AABB box = AABB.ofSize(player.position(), 128.0, 128.0, 128.0);
        List<MessengerCat> ghosts = level.getEntitiesOfClass(MessengerCat.class, box);
        if (ghosts.isEmpty()) {
            return null;
        }
        ghosts.sort(Comparator.comparingDouble(g -> g.distanceToSqr(player)));
        return ghosts.getFirst();
    }

    // 阶段名补全
    private static CompletableFuture<Suggestions> suggestPhases(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        for (MessengerCatPhase phase : MessengerCatPhase.values()) {
            builder.suggest(phase.name().toLowerCase());
        }
        return builder.buildFuture();
    }

    // 大小写不敏感解析阶段枚举
    private static MessengerCatPhase parsePhase(String name) {
        for (MessengerCatPhase phase : MessengerCatPhase.values()) {
            if (phase.name().equalsIgnoreCase(name)) {
                return phase;
            }
        }
        return null;
    }
}
