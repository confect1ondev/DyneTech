package com.confect1on.dynetech.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.block.DTBlocks;
import com.confect1on.dynetech.block.ShoalBloomBlock;
import com.confect1on.dynetech.block.ShoalGrowthBlock;
import com.confect1on.dynetech.block.ShoalSeep;
import com.confect1on.dynetech.blockentity.ShoalBloomBlockEntity;
import com.confect1on.dynetech.config.DTConfig;
import com.confect1on.dynetech.gene.DTAttachments;
import com.confect1on.dynetech.gene.EquippedPerks;
import com.confect1on.dynetech.gene.PerkEntry;
import com.confect1on.dynetech.gene.PerkLifecycle;
import com.confect1on.dynetech.gene.Perks;
import com.confect1on.dynetech.gene.ShoalContact;
import com.confect1on.dynetech.gene.ShoalHostState;
import com.confect1on.dynetech.item.DTItems;
import com.mojang.serialization.Dynamic;

import java.util.List;
import java.util.Optional;

/**
 * GameTests for the Shoal infection chain: block contact -> silent incubation -> symptomatic
 * infection -> seep block conversion, plus the wound/flinch/regrow loop and the purge.
 *
 * <p>The seep keeps static per-dimension flinch/regrow state and reads live config values.
 * The lifecycle test clamps the scan radii down first so every conversion stays inside its
 * own structure footprint, and restores both values when it succeeds. Mock players drive all
 * the entry points directly; none of them need to be real ServerPlayers because every code
 * path under test only relies on position, game mode, and data attachments.
 */
@GameTestHolder(DyneTech.MODID)
@PrefixGameTestTemplate(true)
public final class Shoal {

    private static final String TEMPLATE = "empty";
    private static final BlockPos CENTER = new BlockPos(2, 1, 2);

    private Shoal() {}

    // ============================================================================
    //  Host state
    // ============================================================================

    /** All four fields survive a codec round-trip, and the withers only touch their own fields. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void host_state_codec_roundtrips(GameTestHelper helper) {
        ShoalHostState original = new ShoalHostState(123L, 456L, 7, 9L);
        Tag encoded = ShoalHostState.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        ShoalHostState decoded = ShoalHostState.CODEC
                .parse(new Dynamic<>(NbtOps.INSTANCE, encoded)).getOrThrow();

        helper.assertTrue(decoded.equals(original), "all four fields roundtrip");
        helper.assertTrue(ShoalHostState.EMPTY.incubationStart() == -1L, "EMPTY starts unset");
        helper.assertTrue(original.withIncubationStart(5L).lastSeepTick() == 456L,
                "withIncubationStart keeps the seep fields");
        helper.assertTrue(original.withSeepTick(1L, 2, 3L).incubationStart() == 123L,
                "withSeepTick keeps the incubation stamp");
        helper.succeed();
    }

    // ============================================================================
    //  Bloom cover/restore
    // ============================================================================

    /** A bloom that grew over a block swaps it back in place and pays one residue. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void bloom_restore_swaps_covered_block_back(GameTestHelper helper) {
        ServerLevel server = helper.getLevel();
        helper.setBlock(CENTER, DTBlocks.SHOAL_BLOOM.get());
        BlockPos abs = helper.absolutePos(CENTER);
        BlockEntity raw = server.getBlockEntity(abs);
        helper.assertTrue(raw instanceof ShoalBloomBlockEntity, "bloom must own its block entity");
        ((ShoalBloomBlockEntity) raw).setCovered(Blocks.IRON_BLOCK.defaultBlockState());

        helper.assertTrue(ShoalBloomBlock.restoreCovered(server, abs),
                "covered bloom must report a restore");
        helper.assertBlockPresent(Blocks.IRON_BLOCK, CENTER);
        ItemEntity residue = findResidue(server, abs);
        helper.assertTrue(residue != null, "restore must pay one Shoal Residue");
        residue.discard();
        helper.succeed();
    }

    /** A player-placed bloom covers nothing; restore declines and leaves the block alone. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void bloom_restore_declines_when_nothing_covered(GameTestHelper helper) {
        ServerLevel server = helper.getLevel();
        helper.setBlock(CENTER, DTBlocks.SHOAL_BLOOM.get());
        BlockPos abs = helper.absolutePos(CENTER);

        helper.assertTrue(!ShoalBloomBlock.restoreCovered(server, abs),
                "bare bloom has nothing to restore");
        helper.assertBlockPresent(DTBlocks.SHOAL_BLOOM.get(), CENTER);
        helper.succeed();
    }

    /**
     * The real break-event wiring in DyneTech: breaking a covered bloom cancels the vanilla
     * break, restores the covered block, and pays residue. Guards against the listener being
     * unregistered or the cancel branch drifting.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void break_event_restores_covered_bloom(GameTestHelper helper) {
        ServerLevel server = helper.getLevel();
        helper.setBlock(CENTER, DTBlocks.SHOAL_BLOOM.get());
        BlockPos abs = helper.absolutePos(CENTER);
        ((ShoalBloomBlockEntity) server.getBlockEntity(abs))
                .setCovered(Blocks.GOLD_BLOCK.defaultBlockState());

        Player miner = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockEvent.BreakEvent event =
                new BlockEvent.BreakEvent(server, abs, server.getBlockState(abs), miner);
        NeoForge.EVENT_BUS.post(event);

        helper.assertTrue(event.isCanceled(), "covered bloom break must cancel the vanilla break");
        helper.assertBlockPresent(Blocks.GOLD_BLOCK, CENTER);
        ItemEntity residue = findResidue(server, abs);
        helper.assertTrue(residue != null, "break-event restore must pay residue");
        residue.discard();
        miner.discard();
        helper.succeed();
    }

    /** A bare pyre bloom breaks the vanilla way: the event stays uncancelled and no restore runs. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void break_event_leaves_bare_bloom_to_vanilla(GameTestHelper helper) {
        ServerLevel server = helper.getLevel();
        helper.setBlock(CENTER, DTBlocks.SHOAL_BLOOM.get());
        BlockPos abs = helper.absolutePos(CENTER);

        Player miner = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockEvent.BreakEvent event =
                new BlockEvent.BreakEvent(server, abs, server.getBlockState(abs), miner);
        NeoForge.EVENT_BUS.post(event);

        helper.assertTrue(!event.isCanceled(), "bare bloom break must fall through to vanilla");
        helper.assertBlockPresent(DTBlocks.SHOAL_BLOOM.get(), CENTER);
        helper.assertTrue(findResidue(server, abs) == null,
                "listener must not pay residue for a bare bloom, playerWillDestroy does");
        miner.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Contact transmission
    // ============================================================================

    /** Touch infection equips the silent incubation gene once and stamps the host state. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void contact_infects_survival_player_once(GameTestHelper helper) {
        ServerLevel server = helper.getLevel();
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        long stamp = server.getDayTime();

        ShoalContact.tryInfectByContact(server, p);
        EquippedPerks eq = p.getData(DTAttachments.EQUIPPED_PERKS.get());
        helper.assertTrue(eq.has(Perks.SHOAL_INCUBATION.getId()), "contact must equip incubation");
        ShoalHostState state = p.getData(DTAttachments.SHOAL_HOST_STATE.get());
        helper.assertTrue(state.incubationStart() == stamp,
                "onEquip must stamp incubation start from day time");

        // Second touch is a no-op: one entry, quality untouched by the merge-averaging in add().
        ShoalContact.tryInfectByContact(server, p);
        EquippedPerks after = p.getData(DTAttachments.EQUIPPED_PERKS.get());
        helper.assertTrue(after.perks().size() == 1, "repeat contact must not duplicate the entry");
        p.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void contact_skips_creative_and_spectator(GameTestHelper helper) {
        ServerLevel server = helper.getLevel();
        Player creative = helper.makeMockPlayer(GameType.CREATIVE);
        Player spectator = helper.makeMockPlayer(GameType.SPECTATOR);

        ShoalContact.tryInfectByContact(server, creative);
        ShoalContact.tryInfectByContact(server, spectator);

        helper.assertTrue(!creative.getData(DTAttachments.EQUIPPED_PERKS.get())
                .has(Perks.SHOAL_INCUBATION.getId()), "creative players are immune to contact");
        helper.assertTrue(!spectator.getData(DTAttachments.EQUIPPED_PERKS.get())
                .has(Perks.SHOAL_INCUBATION.getId()), "spectators are immune to contact");
        creative.discard();
        spectator.discard();
        helper.succeed();
    }

    /**
     * The global bounding-box sweep: standing beside a bloom (a side touch stepOn never sees)
     * still infects. Driven manually because mock players are not on the entity tick. The sweep
     * only fires on its 10-tick clock, hence the succeedWhen polling.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void block_contact_sweep_infects_side_toucher(GameTestHelper helper) {
        ServerLevel server = helper.getLevel();
        helper.setBlock(CENTER, DTBlocks.SHOAL_BLOOM.get());
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 stand = helper.absoluteVec(new Vec3(2.5D, 1.0D, 3.5D));
        p.moveTo(stand.x, stand.y, stand.z);

        helper.onEachTick(() -> ShoalContact.tickBlockContact(server, p));
        helper.succeedWhen(() -> {
            helper.assertTrue(p.getData(DTAttachments.EQUIPPED_PERKS.get())
                    .has(Perks.SHOAL_INCUBATION.getId()), "side contact with a bloom must infect");
            p.discard();
        });
    }

    /**
     * Proximity spread through the real infection-perk tick: a symptomatic carrier infects a
     * survival player crowding them, but not one across the arena and not a creative bystander.
     * The carrier is creative on purpose so the seep half of the perk tick stays inert and the
     * test only observes transmission. Victims must be added to the level because the scan runs
     * through getEntitiesOfClass.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void proximity_spreads_from_symptomatic_carrier(GameTestHelper helper) {
        ServerLevel server = helper.getLevel();
        Player carrier = helper.makeMockPlayer(GameType.CREATIVE);
        PerkEntry entry = equipMarker(carrier, Perks.SHOAL_INFECTION.getId());
        Vec3 at = helper.absoluteVec(new Vec3(1.0D, 1.0D, 1.0D));
        carrier.moveTo(at.x, at.y, at.z);

        Player close = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 closeAt = helper.absoluteVec(new Vec3(2.0D, 1.0D, 1.0D));
        close.moveTo(closeAt.x, closeAt.y, closeAt.z);
        server.addFreshEntity(close);

        Player far = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 farAt = helper.absoluteVec(new Vec3(4.4D, 1.0D, 4.4D));
        far.moveTo(farAt.x, farAt.y, farAt.z);
        server.addFreshEntity(far);

        Player bystander = helper.makeMockPlayer(GameType.CREATIVE);
        Vec3 byAt = helper.absoluteVec(new Vec3(1.0D, 1.0D, 2.0D));
        bystander.moveTo(byAt.x, byAt.y, byAt.z);
        server.addFreshEntity(bystander);

        helper.onEachTick(() -> Perks.SHOAL_INFECTION.get().tick(carrier, entry));
        helper.succeedWhen(() -> {
            helper.assertTrue(close.getData(DTAttachments.EQUIPPED_PERKS.get())
                    .has(Perks.SHOAL_INCUBATION.getId()), "crowding player must catch incubation");
            helper.assertTrue(!far.getData(DTAttachments.EQUIPPED_PERKS.get())
                    .has(Perks.SHOAL_INCUBATION.getId()), "distant player must stay clean");
            helper.assertTrue(!bystander.getData(DTAttachments.EQUIPPED_PERKS.get())
                    .has(Perks.SHOAL_INCUBATION.getId()), "creative bystander must stay clean");
            carrier.discard();
            close.discard();
            far.discard();
            bystander.discard();
        });
    }

    // ============================================================================
    //  Incubation lifecycle
    // ============================================================================

    /**
     * Backdated incubation matures into the symptomatic stage through the real perk tick.
     * Day time is jumped forward in whole days first (preserving the time of day, so the
     * daylight-dependent gene tests are unaffected) to guarantee the backdated stamp stays
     * positive; a negative stamp would hit the restamp branch instead.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void incubation_matures_into_infection(GameTestHelper helper) {
        ServerLevel server = helper.getLevel();
        long required = DTConfig.SHOAL_INCUBATION_TICKS.get() + 200L;
        long now = server.getDayTime();
        if (now < required) {
            long days = (required - now) / 24_000L + 1L;
            server.setDayTime(now + days * 24_000L);
            server.updateSkyBrightness();
        }

        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        ShoalContact.tryInfectByContact(server, p);
        helper.assertTrue(p.getData(DTAttachments.EQUIPPED_PERKS.get())
                .has(Perks.SHOAL_INCUBATION.getId()), "contact must equip incubation");
        ShoalHostState state = p.getData(DTAttachments.SHOAL_HOST_STATE.get());
        p.setData(DTAttachments.SHOAL_HOST_STATE.get(), state.withIncubationStart(
                server.getDayTime() - DTConfig.SHOAL_INCUBATION_TICKS.get() - 100L));

        // The perk gates itself to every 20th game tick, so drive it until it flips over.
        helper.onEachTick(() -> {
            EquippedPerks eq = p.getData(DTAttachments.EQUIPPED_PERKS.get());
            eq.find(Perks.SHOAL_INCUBATION.getId())
                    .ifPresent(e -> Perks.SHOAL_INCUBATION.get().tick(p, e));
        });
        helper.succeedWhen(() -> {
            EquippedPerks eq = p.getData(DTAttachments.EQUIPPED_PERKS.get());
            helper.assertTrue(eq.has(Perks.SHOAL_INFECTION.getId()),
                    "elapsed incubation must mature into infection");
            helper.assertTrue(!eq.has(Perks.SHOAL_INCUBATION.getId()),
                    "the incubation entry must be consumed by maturation");
            p.discard();
        });
    }

    /** Death-path clear (clearAllPerks) unequips incubation and wipes the host state with it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void incubation_clear_resets_host_state(GameTestHelper helper) {
        ServerLevel server = helper.getLevel();
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        ShoalContact.tryInfectByContact(server, p);
        helper.assertTrue(p.getData(DTAttachments.SHOAL_HOST_STATE.get()).incubationStart() >= 0L,
                "infection must stamp the host state first");

        PerkLifecycle.clearAllPerks(p);

        helper.assertTrue(p.getData(DTAttachments.EQUIPPED_PERKS.get()).perks().isEmpty(),
                "clear must strip the genome");
        helper.assertTrue(p.getData(DTAttachments.SHOAL_HOST_STATE.get())
                .equals(ShoalHostState.EMPTY), "onUnequip must reset the host state");
        p.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Purge
    // ============================================================================

    /** Purge restores covered blocks in place, deletes bare pyres, and pays nothing. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void purge_restores_covered_and_clears_pyres(GameTestHelper helper) {
        ServerLevel server = helper.getLevel();
        BlockPos coveredRel = new BlockPos(1, 1, 1);
        BlockPos bareRel = new BlockPos(3, 1, 3);
        helper.setBlock(coveredRel, DTBlocks.SHOAL_BLOOM.get());
        helper.setBlock(bareRel, DTBlocks.SHOAL_BLOOM.get());
        ((ShoalBloomBlockEntity) server.getBlockEntity(helper.absolutePos(coveredRel)))
                .setCovered(Blocks.IRON_BLOCK.defaultBlockState());

        BlockPos centerAbs = helper.absolutePos(CENTER);
        helper.assertTrue(ShoalSeep.purge(server, centerAbs, 3), "purge must report removals");
        helper.assertBlockPresent(Blocks.IRON_BLOCK, coveredRel);
        helper.assertBlockPresent(Blocks.AIR, bareRel);
        helper.assertTrue(findResidue(server, centerAbs) == null, "purge pays no residue");
        helper.assertTrue(!ShoalSeep.purge(server, centerAbs, 3), "second purge finds nothing");
        helper.succeed();
    }

    // ============================================================================
    //  Seep lifecycle
    // ============================================================================

    /**
     * One sequential pass over the whole seep loop: a lingering carrier converts a block, a
     * player wound freezes all conversion for the flinch pause, the wound regrows once the
     * pause ends, the creative/sprint/budget gates hold, and the purge wipes the outbreak.
     *
     * <p>Run as a single test because the seep's flinch state and the seep config values are
     * global; splitting the phases into parallel tests would let them interfere. The interval
     * gate is bypassed by resetting lastSeepTick before each driven tick, since real elapsed
     * game time would make the test take minutes. Foreign flinches from the break-event tests
     * only ever lengthen a pause, which every phase here tolerates.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void seep_converts_flinches_regrows_and_purges(GameTestHelper helper) {
        ServerLevel server = helper.getLevel();
        ShoalSeep.clearTransient();
        int prevScan = DTConfig.SEEP_SCAN_RADIUS.get();
        int prevSearch = DTConfig.SEEP_GROWTH_SEARCH_RADIUS.get();
        DTConfig.SEEP_SCAN_RADIUS.set(1);
        DTConfig.SEEP_GROWTH_SEARCH_RADIUS.set(2);

        // A 3x3 stone pad of eligible targets with the carrier lingering on top of it.
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        Player carrier = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 stand = helper.absoluteVec(new Vec3(2.5D, 2.0D, 2.5D));
        carrier.moveTo(stand.x, stand.y, stand.z);

        Player creativeCarrier = helper.makeMockPlayer(GameType.CREATIVE);
        Vec3 creativeAt = helper.absoluteVec(new Vec3(1.5D, 2.0D, 1.5D));
        creativeCarrier.moveTo(creativeAt.x, creativeAt.y, creativeAt.z);

        Player fastCarrier = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 fastAt = helper.absoluteVec(new Vec3(3.5D, 2.0D, 3.5D));
        fastCarrier.moveTo(fastAt.x, fastAt.y, fastAt.z);
        fastCarrier.setDeltaMovement(new Vec3(0.5D, 0.0D, 0.5D));

        final int baseline = countShoal(helper);
        final int[] phase = {0};
        final long[] flinchStart = {0L};
        final int[] frozenCount = {0};
        final BlockPos[] wound = {null};

        helper.onEachTick(() -> {
            long now = server.getGameTime();
            switch (phase[0]) {
                case 0 -> {
                    // Drive until the first conversion lands.
                    resetSeepClock(carrier);
                    ShoalSeep.tick(server, carrier);
                    if (countShoal(helper) > baseline) phase[0] = 1;
                }
                case 1 -> {
                    // Wound the biomass: rip out a bloom and report it with regrow queued.
                    BlockPos rel = findFirstShoal(helper);
                    helper.assertTrue(rel != null, "phase 0 conversion must leave a shoal block");
                    BlockPos abs = helper.absolutePos(rel);
                    server.removeBlock(abs, false);
                    ShoalSeep.notifyBroken(server, abs, true);
                    wound[0] = abs;
                    flinchStart[0] = now;
                    frozenCount[0] = countShoal(helper);
                    phase[0] = 2;
                }
                case 2 -> {
                    // Well inside the 100-tick pause: driven ticks must convert nothing.
                    resetSeepClock(carrier);
                    ShoalSeep.tick(server, carrier);
                    helper.assertTrue(countShoal(helper) == frozenCount[0],
                            "seep must stay frozen during the flinch pause");
                    if (now >= flinchStart[0] + 60L) phase[0] = 3;
                }
                case 3 -> {
                    // Keep driving; once conversion resumes it must be after the full pause,
                    // and the regrow queue must eventually fill the wound back in.
                    resetSeepClock(carrier);
                    ShoalSeep.tick(server, carrier);
                    if (countShoal(helper) != frozenCount[0]) {
                        helper.assertTrue(now >= flinchStart[0] + 100L,
                                "conversion must not resume before the pause ends");
                    }
                    if (server.getBlockState(wound[0]).getBlock() instanceof ShoalBloomBlock) {
                        phase[0] = 4;
                    }
                }
                case 4 -> {
                    // Gates: creative, sprinting, and exhausted-budget carriers all sit out.
                    int count = countShoal(helper);
                    ShoalSeep.tick(server, creativeCarrier);
                    helper.assertTrue(countShoal(helper) == count,
                            "creative carriers must not seep");
                    resetSeepClock(fastCarrier);
                    ShoalSeep.tick(server, fastCarrier);
                    helper.assertTrue(countShoal(helper) == count,
                            "a fast-moving carrier must not seep");
                    long today = server.getDayTime() / 24_000L;
                    carrier.setData(DTAttachments.SHOAL_HOST_STATE.get(),
                            new ShoalHostState(-1L, -1L, DTConfig.SEEP_DAILY_BUDGET.get(), today));
                    ShoalSeep.tick(server, carrier);
                    helper.assertTrue(countShoal(helper) == count,
                            "an exhausted daily budget must stop the seep");
                    phase[0] = 5;
                }
                case 5 -> {
                    // Purge the outbreak. Second sweep higher up catches any tall spire; the
                    // up-biased pyre growth can outrun a single radius-6 cube in the worst case.
                    BlockPos centerAbs = helper.absolutePos(new BlockPos(2, 2, 2));
                    helper.assertTrue(ShoalSeep.purge(server, centerAbs, 6),
                            "purge must clear the outbreak");
                    ShoalSeep.purge(server, centerAbs.above(8), 6);
                    helper.assertTrue(countShoal(helper) == 0,
                            "no shoal blocks may survive the purge");
                    phase[0] = 6;
                }
                default -> { }
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(phase[0] == 6, "seep lifecycle still in phase " + phase[0]);
            DTConfig.SEEP_SCAN_RADIUS.set(prevScan);
            DTConfig.SEEP_GROWTH_SEARCH_RADIUS.set(prevSearch);
            ShoalSeep.clearTransient();
            carrier.discard();
            creativeCarrier.discard();
            fastCarrier.discard();
        });
    }

    // ============================================================================
    //  Helpers
    // ============================================================================

    private static PerkEntry equipMarker(Player player, ResourceLocation perkId) {
        PerkEntry entry = new PerkEntry(perkId, 1.0F, Optional.empty(), Optional.empty());
        EquippedPerks eq = player.getData(DTAttachments.EQUIPPED_PERKS.get());
        player.setData(DTAttachments.EQUIPPED_PERKS.get(), eq.add(entry));
        return entry;
    }

    private static ItemEntity findResidue(ServerLevel server, BlockPos around) {
        List<ItemEntity> items = server.getEntitiesOfClass(ItemEntity.class,
                new AABB(around).inflate(2.0D),
                item -> item.getItem().is(DTItems.SHOAL_RESIDUE.get()));
        return items.isEmpty() ? null : items.get(0);
    }

    /** Bypass the interval gate: pretend the carrier has never seeped, keep budget bookkeeping. */
    private static void resetSeepClock(Player carrier) {
        ShoalHostState state = carrier.getData(DTAttachments.SHOAL_HOST_STATE.get());
        carrier.setData(DTAttachments.SHOAL_HOST_STATE.get(),
                state.withSeepTick(-1L, state.seepBudgetUsed(), state.seepDay()));
    }

    // Generous bounds around the 5x5x5 template: pyre growth is up-biased and the scatter
    // fallback can spill a block or two past the walls, so the census looks past them too.
    private static int countShoal(GameTestHelper helper) {
        int count = 0;
        for (int x = -4; x <= 8; x++) {
            for (int y = 0; y <= 12; y++) {
                for (int z = -4; z <= 8; z++) {
                    Block block = helper.getBlockState(new BlockPos(x, y, z)).getBlock();
                    if (block instanceof ShoalBloomBlock || block instanceof ShoalGrowthBlock) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static BlockPos findFirstShoal(GameTestHelper helper) {
        for (int x = -4; x <= 8; x++) {
            for (int y = 0; y <= 12; y++) {
                for (int z = -4; z <= 8; z++) {
                    BlockPos rel = new BlockPos(x, y, z);
                    Block block = helper.getBlockState(rel).getBlock();
                    if (block instanceof ShoalBloomBlock || block instanceof ShoalGrowthBlock) {
                        return rel;
                    }
                }
            }
        }
        return null;
    }
}
