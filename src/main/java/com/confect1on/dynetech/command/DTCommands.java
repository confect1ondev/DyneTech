package com.confect1on.dynetech.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import com.confect1on.dynetech.gene.DTAttachments;
import com.confect1on.dynetech.gene.EquippedPerks;
import com.confect1on.dynetech.gene.GodhoodEvents;
import com.confect1on.dynetech.gene.GodhoodState;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkEntry;
import com.confect1on.dynetech.gene.Perks;
import com.confect1on.dynetech.gene.VialContents;
import com.confect1on.dynetech.gene.VialState;
import com.confect1on.dynetech.item.GeneVialItem;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Dev/creative-aid commands. Registered on {@code RegisterCommandsEvent}.
 *
 * <p>{@code /dynetech vial <state> <perk> [quality]}: hands the executing player a Gene Vial
 * of the requested state, loaded with a single perk at the given quality. Op-only (level 2)
 * so servers do not have to whitelist it separately.
 */
public final class DTCommands {

    private static final DynamicCommandExceptionType UNKNOWN_PERK = new DynamicCommandExceptionType(
            id -> Component.literal("Unknown perk: " + id));

    /** Tab-completes with every registered perk id. */
    private static final SuggestionProvider<CommandSourceStack> PERK_SUGGESTIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggestResource(
                    Perks.all().stream().map(Perk::id), builder);

    private DTCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext ctx) {
        dispatcher.register(Commands.literal("dynetech")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("vial")
                        .then(Commands.literal("empty")
                                .executes(c -> giveVial(c.getSource(), VialContents.EMPTY)))
                        .then(Commands.literal("isolated")
                                .then(perkArg(VialState.ISOLATED)))
                        .then(Commands.literal("serum")
                                .then(perkArg(VialState.SERUM)))
                        .then(Commands.literal("raw")
                                .then(perkArg(VialState.RAW))))
                .then(Commands.literal("godhood")
                        .then(Commands.literal("set")
                                // /dynetech godhood set <count>  -> self
                                .then(Commands.argument("count", IntegerArgumentType.integer(0, GodhoodState.MAX_CHARGES))
                                        .executes(c -> setCharges(c.getSource(),
                                                List.of(c.getSource().getPlayerOrException()),
                                                IntegerArgumentType.getInteger(c, "count")))
                                        // /dynetech godhood set <count> <targets>
                                        .then(Commands.argument("targets", EntityArgument.players())
                                                .executes(c -> setCharges(c.getSource(),
                                                        EntityArgument.getPlayers(c, "targets"),
                                                        IntegerArgumentType.getInteger(c, "count"))))))
                        .then(Commands.literal("get")
                                .executes(c -> getCharges(c.getSource(), c.getSource().getPlayerOrException()))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(c -> getCharges(c.getSource(), EntityArgument.getPlayer(c, "target")))))
                        .then(Commands.literal("spawn_dummy")
                                .executes(c -> spawnEssenceDummy(c.getSource(), c.getSource().getPosition(), 0))
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(c -> spawnEssenceDummy(c.getSource(),
                                                Vec3Argument.getVec3(c, "pos"), 0))
                                        .then(Commands.argument("charges", IntegerArgumentType.integer(0, GodhoodState.MAX_CHARGES))
                                                .executes(c -> spawnEssenceDummy(c.getSource(),
                                                        Vec3Argument.getVec3(c, "pos"),
                                                        IntegerArgumentType.getInteger(c, "charges"))))))
                        .then(Commands.literal("end_vulnerability")
                                .executes(c -> endVulnerability(c.getSource(),
                                        List.of(c.getSource().getPlayerOrException())))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(c -> endVulnerability(c.getSource(),
                                                EntityArgument.getPlayers(c, "targets")))))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> perkArg(VialState state) {
        return Commands.argument("perk", ResourceLocationArgument.id())
                .suggests(PERK_SUGGESTIONS)
                .executes(c -> giveWithPerk(c.getSource(), state, ResourceLocationArgument.getId(c, "perk"), 1.0F))
                .then(Commands.argument("quality", FloatArgumentType.floatArg(0.0F, 1.0F))
                        .executes(c -> giveWithPerk(c.getSource(), state,
                                ResourceLocationArgument.getId(c, "perk"),
                                FloatArgumentType.getFloat(c, "quality"))));
    }

    private static int giveWithPerk(CommandSourceStack source, VialState state, ResourceLocation perkId, float quality) throws CommandSyntaxException {
        Perk perk = Perks.get(perkId);
        if (perk == null) throw UNKNOWN_PERK.create(perkId);

        PerkEntry entry = new PerkEntry(perkId, quality, Optional.empty(), Optional.empty());
        // Universal donor type so the resulting serum bypasses the injection compatibility check.
        // See VialContents.UNIVERSAL_DONOR_TYPE.
        VialContents contents = new VialContents(
                state,
                List.of(entry),
                Optional.empty(),
                Optional.empty(),
                Optional.of(VialContents.UNIVERSAL_DONOR_TYPE),
                Optional.empty());
        return giveVial(source, contents);
    }

    private static int giveVial(CommandSourceStack source, VialContents contents) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
        ItemStack stack = GeneVialItem.withContents(contents);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        source.sendSuccess(() -> Component.literal("Gave " + describe(contents)), false);
        return 1;
    }

    private static String describe(VialContents c) {
        if (c.state() == VialState.EMPTY || c.perks().isEmpty()) return c.state().name().toLowerCase() + " vial";
        PerkEntry e = c.perks().get(0);
        return c.state().name().toLowerCase() + " vial (" + e.perkId() + " @ " + e.quality() + ")";
    }

    private static int setCharges(CommandSourceStack source, Collection<ServerPlayer> targets, int count) {
        int clamped = Math.max(0, Math.min(GodhoodState.MAX_CHARGES, count));
        for (ServerPlayer target : targets) {
            GodhoodState prev = target.getData(DTAttachments.GODHOOD_STATE.get());
            GodhoodState next = prev.withCharges(clamped);
            target.setData(DTAttachments.GODHOOD_STATE.get(), next);
            GodhoodEvents.sendChargeSync(target, next);
        }
        int size = targets.size();
        source.sendSuccess(() -> Component.literal(
                "Set godhood charges to " + clamped + " on " + size + " player(s)."), true);
        return clamped;
    }

    private static int getCharges(CommandSourceStack source, ServerPlayer target) {
        GodhoodState st = target.getData(DTAttachments.GODHOOD_STATE.get());
        source.sendSuccess(() -> Component.literal(
                target.getGameProfile().getName() + " has " + st.regenCharges() + " / " + GodhoodState.MAX_CHARGES + " godhood charges."), false);
        return st.regenCharges();
    }

    /**
     * Debug: end the vulnerability window early. Runs through
     * {@link GodhoodEvents#endVulnerability(net.minecraft.world.entity.player.Player)} so the
     * max-HP modifier and Weakness effect come off the same way they would when the timer
     * expires naturally. Skips targets not currently in the window.
     */
    private static int endVulnerability(CommandSourceStack source, Collection<ServerPlayer> targets) {
        int ended = 0;
        for (ServerPlayer target : targets) {
            GodhoodState st = target.getData(DTAttachments.GODHOOD_STATE.get());
            if (st.vulnerabilityEndGameTime() <= 0) continue;
            GodhoodEvents.endVulnerability(target);
            ended++;
        }
        int finalEnded = ended;
        source.sendSuccess(() -> Component.literal(
                "Ended vulnerability on " + finalEnded + " player(s)."), true);
        return ended;
    }

    /**
     * Spawns a zombie flagged with {@link DTAttachments#TEST_ESSENCE_TARGET}. When a god kills
     * it, the essence rule fires through the same {@code tryGrantEssence} path a player kill
     * would, granting a charge. AI is off so the dummy stands still. {@code startingCharges}
     * seeds the Godhood state so the whisper radius/volume test can be dialed in without
     * hunting real players first.
     */
    private static int spawnEssenceDummy(CommandSourceStack source, Vec3 pos, int startingCharges) {
        ServerLevel level = source.getLevel();
        Zombie dummy = EntityType.ZOMBIE.create(level);
        if (dummy == null) {
            source.sendFailure(Component.literal("Failed to create dummy entity."));
            return 0;
        }
        dummy.moveTo(pos.x, pos.y, pos.z, 0F, 0F);
        dummy.setNoAi(true);
        dummy.setPersistenceRequired();
        dummy.setCustomName(Component.literal("Essence Dummy"));
        dummy.setCustomNameVisible(true);
        dummy.setData(DTAttachments.TEST_ESSENCE_TARGET.get(), Boolean.TRUE);
        // Equip Godhood so the whisper broadcaster picks the dummy up as an ambience source.
        PerkEntry godhood = new PerkEntry(Perks.GODHOOD.getId(), 1.0F, Optional.empty(), Optional.empty());
        dummy.setData(DTAttachments.EQUIPPED_PERKS.get(), new EquippedPerks(List.of(godhood)));
        int clampedCharges = Math.max(0, Math.min(GodhoodState.MAX_CHARGES, startingCharges));
        if (clampedCharges > 0) {
            dummy.setData(DTAttachments.GODHOOD_STATE.get(),
                    GodhoodState.EMPTY.withCharges(clampedCharges));
        }
        level.addFreshEntity(dummy);
        source.sendSuccess(() -> Component.literal("Spawned essence dummy at "
                + Math.round(pos.x) + " " + Math.round(pos.y) + " " + Math.round(pos.z)
                + " with " + clampedCharges + " charges"), false);
        return 1;
    }
}
