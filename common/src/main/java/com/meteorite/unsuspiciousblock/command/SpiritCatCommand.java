package com.meteorite.unsuspiciousblock.command;

import com.meteorite.unsuspiciousblock.cat.CatGiftService;
import com.meteorite.unsuspiciousblock.cat.CatPassiveAbilities;
import com.meteorite.unsuspiciousblock.cat.SpiritCatDebugRegistry;
import com.meteorite.unsuspiciousblock.entity.MerchantCat;
import com.meteorite.unsuspiciousblock.entity.MessengerCat;
import com.meteorite.unsuspiciousblock.entity.ModEntities;
import com.meteorite.unsuspiciousblock.entity.SpiritCat;
import com.meteorite.unsuspiciousblock.entity.SwordsmanCat;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.MerchantCatPhase;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.MessengerCatPhase;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.MessengerCatPositioning;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.MorningGiftBehavior;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.SpiritCatRole;
import com.meteorite.unsuspiciousblock.entity.ai.spiritcat.SwordsmanCatPhase;
import com.meteorite.unsuspiciousblock.platform.Services;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * 三职业猫国灵体调试与区域清理命令。
 */
public final class SpiritCatCommand {
    private static final String TYPE_ARG = "type";
    private static final String STATE_ARG = "state";
    private static final String POS_ARG = "pos";
    private static final String FROM_ARG = "from";
    private static final String TO_ARG = "to";
    private static final int MAX_CLEAR_AXIS_SPAN = 256;

    private SpiritCatCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("spirit_cat")
                .then(Commands.literal("debug")
                        .then(Commands.literal("spawn")
                                .then(roleArgument().executes(SpiritCatCommand::spawnDebug)))
                        .then(Commands.literal("info")
                                .then(roleArgument().executes(SpiritCatCommand::showDebugInfo)))
                        .then(Commands.literal("state")
                                .then(Commands.argument(TYPE_ARG, StringArgumentType.word())
                                        .suggests(SpiritCatCommand::suggestRoles)
                                        .then(Commands.argument(STATE_ARG, StringArgumentType.word())
                                                .suggests(SpiritCatCommand::suggestStates)
                                                .executes(SpiritCatCommand::forceDebugState))))
                        .then(Commands.literal("discard")
                                .then(roleArgument().executes(SpiritCatCommand::discardDebug))))
                .then(Commands.literal("clear")
                        .then(Commands.literal("at")
                                .then(Commands.argument(POS_ARG, BlockPosArgument.blockPos())
                                        .then(clearTypeArgument().executes(SpiritCatCommand::clearAt))))
                        .then(Commands.literal("area")
                                .then(Commands.argument(FROM_ARG, BlockPosArgument.blockPos())
                                        .then(Commands.argument(TO_ARG, BlockPosArgument.blockPos())
                                                .then(clearTypeArgument().executes(SpiritCatCommand::clearArea))))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> roleArgument() {
        return Commands.argument(TYPE_ARG, StringArgumentType.word()).suggests(SpiritCatCommand::suggestRoles);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> clearTypeArgument() {
        return Commands.argument(TYPE_ARG, StringArgumentType.word()).suggests(SpiritCatCommand::suggestClearTypes);
    }

    private static int spawnDebug(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        SpiritCatRole role = parseRole(context);
        if (role == null || !(player.level() instanceof ServerLevel level)) {
            return 0;
        }
        SpiritCat previous = SpiritCatDebugRegistry.find(context.getSource().getServer(), player.getUUID(), role);
        if (previous != null) {
            previous.discard();
        }

        SpiritCat cat = createDebugCat(level, player, role);
        if (cat == null || !level.addFreshEntity(cat)) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.spirit_cat.error.spawn_failed", role.commandName()));
            return 0;
        }
        SpiritCatDebugRegistry.bind(player.getUUID(), role, cat.getUUID());
        if (cat instanceof SwordsmanCat) {
            CatPassiveAbilities.grantNineLivesProtection(player, false);
        }
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.unsuspiciousblock.usb.spirit_cat.spawn.success", role.commandName(), cat.getId()), true);
        return 1;
    }

    private static SpiritCat createDebugCat(ServerLevel level, ServerPlayer player, SpiritCatRole role) {
        return switch (role) {
            case MESSENGER -> createDebugMessenger(level, player);
            case SWORDSMAN -> createDebugSwordsman(level, player);
            case MERCHANT -> createDebugMerchant(level, player);
        };
    }

    private static MessengerCat createDebugMessenger(ServerLevel level, ServerPlayer player) {
        MessengerCat cat = ModEntities.MESSENGER_CAT.get().create(level);
        if (cat == null) {
            return null;
        }
        MessengerCatPositioning.placeNearTarget(level, cat, player);
        MorningGiftBehavior behavior = new MorningGiftBehavior(
                player.getUUID(), null, CatGiftService.GHOST_GIFT_LOOT_TABLE, false);
        cat.assignBehavior(behavior);
        cat.activateDebugDuty(Services.SPIRIT_CAT_CONFIG.getMessengerLifetimeTicks());
        return cat;
    }

    private static SwordsmanCat createDebugSwordsman(ServerLevel level, ServerPlayer player) {
        SwordsmanCat cat = ModEntities.SWORDSMAN_CAT.get().create(level);
        if (cat == null) {
            return null;
        }
        double angle = player.getRandom().nextDouble() * Math.PI * 2.0;
        cat.moveTo(player.getX() + Math.cos(angle) * 0.8, player.getY() + 0.5,
                player.getZ() + Math.sin(angle) * 0.8, player.getYRot(), 0.0F);
        int lifetime = Services.SPIRIT_CAT_CONFIG.getSwordsmanLifetimeTicks();
        cat.activateDebugDuty(lifetime);
        cat.configureProtection(player.getUUID(), null, lifetime);
        return cat;
    }

    private static MerchantCat createDebugMerchant(ServerLevel level, ServerPlayer player) {
        MerchantCat cat = ModEntities.MERCHANT_CAT.get().create(level);
        if (cat == null) {
            return null;
        }
        BlockPos center = player.blockPosition();
        cat.moveTo(player.getX(), player.getY() + 1.5, player.getZ(), player.getYRot(), 0.0F);
        cat.activateDebugDuty(Services.SPIRIT_CAT_CONFIG.getMerchantLifetimeTicks());
        cat.configureActivityCenter(center);
        cat.configureDebugOwner(player.getUUID());
        cat.initializeTrades(level, 100);
        return cat;
    }

    private static int showDebugInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        SpiritCatRole role = parseRole(context);
        SpiritCat cat = findBound(context, player, role);
        if (cat == null) {
            return 0;
        }
        String position = String.format(Locale.ROOT, "%.2f, %.2f, %.2f", cat.getX(), cat.getY(), cat.getZ());
        String state = describeState(cat);
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.unsuspiciousblock.usb.spirit_cat.info.summary", role.commandName(), cat.getId(),
                position, state, cat.getSpiritLifetimeRemaining()), false);
        return 1;
    }

    private static String describeState(SpiritCat cat) {
        if (cat instanceof MessengerCat messenger) {
            return messenger.getPhase().name() + " / phase=" + messenger.getPhaseTicks()
                    + " / total=" + messenger.getTotalTicks() + " / relocations=" + messenger.getRelocationCount();
        }
        if (cat instanceof SwordsmanCat swordsman) {
            return swordsman.getSwordsmanPhase().name() + " / phase=" + swordsman.getPhaseTicks();
        }
        if (cat instanceof MerchantCat merchant) {
            return merchant.getMerchantPhase().name() + " / phase=" + merchant.getPhaseTicks()
                    + " / center=" + merchant.getActivityCenter().toShortString();
        }
        return cat.getSpiritMovementState().name();
    }

    private static int forceDebugState(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        SpiritCatRole role = parseRole(context);
        SpiritCat cat = findBound(context, player, role);
        if (cat == null) {
            return 0;
        }
        String requested = StringArgumentType.getString(context, STATE_ARG);
        String normalized = requested.toUpperCase(Locale.ROOT);
        try {
            switch (role) {
                case MESSENGER -> ((MessengerCat) cat).setPhase(MessengerCatPhase.valueOf(normalized));
                case SWORDSMAN -> ((SwordsmanCat) cat).forceDebugPhase(SwordsmanCatPhase.valueOf(normalized));
                case MERCHANT -> ((MerchantCat) cat).forceDebugPhase(MerchantCatPhase.valueOf(normalized));
            }
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.spirit_cat.error.unknown_state", requested, role.commandName()));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.unsuspiciousblock.usb.spirit_cat.state.success", role.commandName(), cat.getId(), normalized), true);
        return 1;
    }

    private static int discardDebug(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        SpiritCatRole role = parseRole(context);
        SpiritCat cat = findBound(context, player, role);
        if (cat == null) {
            return 0;
        }
        int id = cat.getId();
        cat.discard();
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.unsuspiciousblock.usb.spirit_cat.discard.success", role.commandName(), id), true);
        return 1;
    }

    private static int clearAt(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getBlockPos(context, POS_ARG);
        return clear(context, new AABB(pos));
    }

    private static int clearArea(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        BlockPos from = BlockPosArgument.getBlockPos(context, FROM_ARG);
        BlockPos to = BlockPosArgument.getBlockPos(context, TO_ARG);
        if (axisSpan(from.getX(), to.getX()) > MAX_CLEAR_AXIS_SPAN
                || axisSpan(from.getY(), to.getY()) > MAX_CLEAR_AXIS_SPAN
                || axisSpan(from.getZ(), to.getZ()) > MAX_CLEAR_AXIS_SPAN) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.spirit_cat.error.area_too_large", MAX_CLEAR_AXIS_SPAN));
            return 0;
        }
        AABB area = new AABB(
                Math.min(from.getX(), to.getX()), Math.min(from.getY(), to.getY()), Math.min(from.getZ(), to.getZ()),
                Math.max(from.getX(), to.getX()) + 1.0, Math.max(from.getY(), to.getY()) + 1.0,
                Math.max(from.getZ(), to.getZ()) + 1.0);
        return clear(context, area);
    }

    private static int clear(CommandContext<CommandSourceStack> context, AABB area) {
        ServerLevel level = context.getSource().getLevel();
        String typeName = StringArgumentType.getString(context, TYPE_ARG);
        SpiritCatRole role = "all".equalsIgnoreCase(typeName) ? null : SpiritCatRole.parse(typeName);
        if (role == null && !"all".equalsIgnoreCase(typeName)) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.spirit_cat.error.unknown_type", typeName));
            return 0;
        }
        var cats = level.getEntitiesOfClass(SpiritCat.class, area,
                cat -> role == null || role.matches(cat));
        cats.forEach(SpiritCat::discard);
        int count = cats.size();
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.unsuspiciousblock.usb.spirit_cat.clear.success", count, typeName), true);
        return count;
    }

    private static long axisSpan(int first, int second) {
        return Math.abs((long) first - second);
    }

    private static SpiritCat findBound(CommandContext<CommandSourceStack> context, ServerPlayer player,
                                       SpiritCatRole role) {
        if (role == null) {
            return null;
        }
        SpiritCat cat = SpiritCatDebugRegistry.find(context.getSource().getServer(), player.getUUID(), role);
        if (cat == null) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.spirit_cat.error.not_found", role.commandName()));
        }
        return cat;
    }

    private static SpiritCatRole parseRole(CommandContext<CommandSourceStack> context) {
        String typeName = StringArgumentType.getString(context, TYPE_ARG);
        SpiritCatRole role = SpiritCatRole.parse(typeName);
        if (role == null) {
            context.getSource().sendFailure(Component.translatable(
                    "command.unsuspiciousblock.usb.spirit_cat.error.unknown_type", typeName));
        }
        return role;
    }

    private static CompletableFuture<Suggestions> suggestRoles(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                Arrays.stream(SpiritCatRole.values()).map(SpiritCatRole::commandName), builder);
    }

    private static CompletableFuture<Suggestions> suggestClearTypes(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                java.util.stream.Stream.concat(java.util.stream.Stream.of("all"),
                        Arrays.stream(SpiritCatRole.values()).map(SpiritCatRole::commandName)), builder);
    }

    private static CompletableFuture<Suggestions> suggestStates(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        SpiritCatRole role = SpiritCatRole.parse(StringArgumentType.getString(context, TYPE_ARG));
        java.util.stream.Stream<String> states = switch (role) {
            case MESSENGER -> Arrays.stream(MessengerCatPhase.values()).map(Enum::name);
            case SWORDSMAN -> Arrays.stream(SwordsmanCatPhase.values()).map(Enum::name);
            case MERCHANT -> Arrays.stream(MerchantCatPhase.values()).map(Enum::name);
            case null -> java.util.stream.Stream.empty();
        };
        return SharedSuggestionProvider.suggest(states.map(name -> name.toLowerCase(Locale.ROOT)), builder);
    }
}
