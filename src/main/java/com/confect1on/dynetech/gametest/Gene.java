package com.confect1on.dynetech.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.core.Holder;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.confect1on.dynetech.gene.DTAttachments;
import com.confect1on.dynetech.gene.EquippedPerks;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkCondition;
import com.confect1on.dynetech.gene.GeneOps;
import com.confect1on.dynetech.gene.PerkLifecycle;
import com.confect1on.dynetech.gene.PerkEntry;
import com.confect1on.dynetech.gene.PerkGrade;
import com.confect1on.dynetech.gene.Perks;
import com.confect1on.dynetech.gene.SpeciesPool;
import com.confect1on.dynetech.gene.VialContents;
import com.confect1on.dynetech.gene.VialState;
import com.confect1on.dynetech.pehkui.PehkuiCompat;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * GameTests for the Gene / Perk system.
 *
 * <p>Runs against a dedicated empty template so the tests are name-space-isolated: prefixed
 * {@code gene.} via {@link PrefixGameTestTemplate}, letting {@code /test runall gene} target
 * this suite specifically.
 *
 * <p>Tests live in three tiers:
 * <ul>
 *   <li><b>Pure logic</b> - {@link PerkGrade} thresholds, {@link VialContents} codec + merge,
 *   {@link EquippedPerks} add/upgrade, {@link SpeciesPool} lookups. No entities involved.</li>
 *   <li><b>Engine flow</b> - {@link GeneOps#rollRawFromEntity}, {@link GeneOps#sequenceOne},
 *   {@link GeneOps#splice}. Uses a real spawned mob as donor but otherwise deterministic.</li>
 *   <li><b>Perk application</b> - one test per registered perk that spawns a suitable entity,
 *   invokes {@code onEquip}, asserts the observable effect, then invokes {@code onUnequip}
 *   and asserts the reversal. Also verifies mob-effect durations are safely above vanilla
 *   Night Vision's flash threshold.</li>
 * </ul>
 */
@GameTestHolder(com.confect1on.dynetech.DyneTech.MODID)
@PrefixGameTestTemplate(true)
public final class Gene {

    public static final String TEMPLATE = "empty";

    // Subject spawn spot inside the 5x5x5 empty template.
    private static final BlockPos SUBJECT_POS = new BlockPos(2, 1, 2);

    private Gene() {}

    // ============================================================================
    //  Pure logic
    // ============================================================================

    /**
     * PerkGrade should slot a continuous quality float into the correct discrete tier. Boundaries:
     * PRISTINE >= 1.00, EXCELLENT >= 0.80, STABLE >= 0.60, VIABLE >= 0.40, DEGRADED >= 0.20,
     * CORRUPTED >= 0.05, DENATURED >= 0.00. A value slightly below a boundary must drop a tier.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_grade_maps_quality_to_expected_tier(GameTestHelper helper) {
        ensureBootstrapped();
        assertGrade(helper, 1.00F, PerkGrade.PRISTINE);
        assertGrade(helper, 0.85F, PerkGrade.EXCELLENT);
        assertGrade(helper, 0.65F, PerkGrade.STABLE);
        assertGrade(helper, 0.45F, PerkGrade.VIABLE);
        assertGrade(helper, 0.25F, PerkGrade.DEGRADED);
        assertGrade(helper, 0.10F, PerkGrade.CORRUPTED);
        assertGrade(helper, 0.00F, PerkGrade.DENATURED);
        // Boundary drop: 0.599 must NOT round up to STABLE.
        assertGrade(helper, 0.599F, PerkGrade.VIABLE);
        helper.succeed();
    }

    /**
     * A round-trip through the CODEC must preserve every field exactly - state, perks, donor,
     * name, and species. NBT is the on-disk format so this doubles as a save-file compat check.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void vial_contents_codec_roundtrips(GameTestHelper helper) {
        ensureBootstrapped();
        RegistryAccess registries = helper.getLevel().registryAccess();
        DynamicOps<net.minecraft.nbt.Tag> ops = registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);

        UUID donor = UUID.randomUUID();
        ResourceLocation cowType = ResourceLocation.withDefaultNamespace("cow");
        PerkEntry entry = new PerkEntry(Perks.VITALITY.getId(), 0.72F, Optional.of(donor), Optional.empty());
        VialContents original = VialContents.serum(List.of(entry),
                Optional.of(donor), Optional.of("Bessie"), Optional.of(cowType));

        net.minecraft.nbt.Tag encoded = VialContents.CODEC.encodeStart(ops, original).getOrThrow();
        VialContents decoded = VialContents.CODEC.parse(new Dynamic<>(ops, encoded)).getOrThrow();

        helper.assertTrue(decoded.state() == VialState.SERUM, "state should roundtrip");
        helper.assertTrue(decoded.perks().size() == 1, "single perk should roundtrip");
        helper.assertTrue(decoded.perks().get(0).perkId().equals(Perks.VITALITY.getId()), "perk id");
        helper.assertTrue(Math.abs(decoded.perks().get(0).quality() - 0.72F) < 1e-4F, "quality");
        helper.assertTrue(decoded.donor().equals(Optional.of(donor)), "donor");
        helper.assertTrue(decoded.donorName().equals(Optional.of("Bessie")), "donor name");
        helper.assertTrue(decoded.donorType().equals(Optional.of(cowType)), "donor type");
        helper.succeed();
    }

    /**
     * Merging two isolated vials with the same perk id should average the two qualities.
     * Distinct perks should be concatenated. Donor type must be dropped when the two sources
     * disagree - otherwise the merged vial would misrepresent its lineage.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void merge_isolated_averages_and_drops_conflicting_donor(GameTestHelper helper) {
        ensureBootstrapped();
        ResourceLocation cowType = ResourceLocation.withDefaultNamespace("cow");
        ResourceLocation pigType = ResourceLocation.withDefaultNamespace("pig");

        // Same-id averaging.
        PerkEntry a = new PerkEntry(Perks.VITALITY.getId(), 0.4F, Optional.empty(), Optional.empty());
        PerkEntry b = new PerkEntry(Perks.VITALITY.getId(), 0.8F, Optional.empty(), Optional.empty());
        VialContents va = VialContents.isolated(a, Optional.empty(), Optional.empty(), Optional.of(cowType));
        VialContents vb = VialContents.isolated(b, Optional.empty(), Optional.empty(), Optional.of(cowType));
        VialContents mergedSame = VialContents.mergeIsolated(va, vb);
        helper.assertTrue(mergedSame.perks().size() == 1, "same-id perks should collapse to one entry");
        float mergedQuality = mergedSame.perks().get(0).quality();
        helper.assertTrue(Math.abs(mergedQuality - 0.6F) < 1e-4F,
                "quality should average to 0.6, got " + mergedQuality);
        helper.assertTrue(mergedSame.donorType().equals(Optional.of(cowType)),
                "matching donorType should propagate");

        // Different-id concat + donor-type conflict.
        PerkEntry brawn = new PerkEntry(Perks.BRAWN.getId(), 0.5F, Optional.empty(), Optional.empty());
        VialContents vc = VialContents.isolated(brawn, Optional.empty(), Optional.empty(), Optional.of(pigType));
        VialContents mergedDiff = VialContents.mergeIsolated(va, vc);
        helper.assertTrue(mergedDiff.perks().size() == 2, "distinct perks should both survive the merge");
        helper.assertTrue(mergedDiff.donorType().isEmpty(),
                "conflicting donor types should collapse to empty");
        helper.succeed();
    }

    /**
     * EquippedPerks.add now mirrors VialContents.mergeIsolated: dedupe by perk id and average
     * the two qualities. Symmetric on order (add(lo).add(hi) == add(hi).add(lo)) so re-injection
     * cannot corrupt the tracker by choosing the "wrong" order.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void equipped_perks_add_averages_like_merge_isolated(GameTestHelper helper) {
        ensureBootstrapped();
        PerkEntry lo = new PerkEntry(Perks.VITALITY.getId(), 0.3F, Optional.empty(), Optional.empty());
        PerkEntry hi = new PerkEntry(Perks.VITALITY.getId(), 0.9F, Optional.empty(), Optional.empty());

        EquippedPerks a = EquippedPerks.EMPTY.add(lo).add(hi);
        helper.assertTrue(a.perks().size() == 1, "same-id perks should dedupe");
        helper.assertTrue(Math.abs(a.perks().get(0).quality() - 0.6F) < 1e-4F,
                "add(lo).add(hi) should average to 0.6, got " + a.perks().get(0).quality());

        EquippedPerks b = EquippedPerks.EMPTY.add(hi).add(lo);
        helper.assertTrue(Math.abs(b.perks().get(0).quality() - 0.6F) < 1e-4F,
                "add(hi).add(lo) should also average to 0.6, got " + b.perks().get(0).quality());

        // find() returns the resolved (averaged) entry so PerkLifecycle.inject can pass it to onEquip.
        helper.assertTrue(a.find(Perks.VITALITY.getId()).isPresent(), "find should return the merged entry");
        helper.assertTrue(a.find(Perks.BRAWN.getId()).isEmpty(), "find on an unequipped id should be empty");
        helper.succeed();
    }

    /**
     * The player pool is the anti-dupe safeguard: self-samples must isolate into the inert
     * "dud" perk exclusively.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void species_pool_player_returns_only_dud(GameTestHelper helper) {
        ensureBootstrapped();
        Set<ResourceLocation> playerIds = SpeciesPool.perkIdsFor(EntityType.PLAYER);
        helper.assertTrue(playerIds.size() == 1, "player pool should hold exactly one entry");
        helper.assertTrue(playerIds.contains(Perks.DUD.getId()), "player pool should be DUD");

        Set<ResourceLocation> cowIds = SpeciesPool.perkIdsFor(EntityType.COW);
        helper.assertTrue(cowIds.contains(Perks.VITALITY.getId()), "cow pool should include vitality");
        helper.succeed();
    }

    // ============================================================================
    //  Engine flow - sampling, sequencing, splicing
    // ============================================================================

    /**
     * Sampling a cow should produce a RAW vial whose perks are all drawn from the cow's own
     * species pool. Donor UUID and species type get stamped on the result for lineage tracing.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void roll_raw_from_cow_stays_within_species_pool(GameTestHelper helper) {
        ensureBootstrapped();
        Cow cow = helper.spawn(EntityType.COW, SUBJECT_POS);

        VialContents raw = GeneOps.rollRawFromEntity(cow, helper.getLevel().random);
        helper.assertTrue(raw.state() == VialState.RAW, "state should be RAW");
        helper.assertTrue(!raw.perks().isEmpty(), "cow pool is non-empty so raw should carry perks");
        Set<ResourceLocation> cowPool = SpeciesPool.perkIdsFor(EntityType.COW);
        for (PerkEntry p : raw.perks()) {
            helper.assertTrue(cowPool.contains(p.perkId()),
                    "raw perk " + p.perkId() + " not in cow species pool");
        }
        helper.assertTrue(raw.donor().equals(Optional.of(cow.getUUID())), "donor UUID stamped");
        helper.assertTrue(raw.donorType().isPresent(), "donor type stamped");
        cow.discard();
        helper.succeed();
    }

    /**
     * sequenceOne must produce an ISOLATED vial with exactly one perk drawn from the raw's pool.
     * Preserves donor lineage so the sequenced vial can still credit its source species.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void sequence_one_extracts_one_perk_from_pool(GameTestHelper helper) {
        ensureBootstrapped();
        Cow cow = helper.spawn(EntityType.COW, SUBJECT_POS);
        VialContents raw = GeneOps.rollRawFromEntity(cow, helper.getLevel().random);

        VialContents isolated = GeneOps.sequenceOne(raw, helper.getLevel().random);
        helper.assertTrue(isolated.state() == VialState.ISOLATED, "state should be ISOLATED");
        helper.assertTrue(isolated.perks().size() == 1, "exactly one perk after isolation");
        Set<ResourceLocation> rawIds = raw.perks().stream().map(PerkEntry::perkId)
                .collect(java.util.stream.Collectors.toSet());
        helper.assertTrue(rawIds.contains(isolated.perks().get(0).perkId()),
                "isolated perk should come from raw's pool");
        helper.assertTrue(isolated.donorType().equals(raw.donorType()),
                "donor lineage should propagate through sequencing");
        cow.discard();
        helper.succeed();
    }

    /**
     * splice(A_isolated, B_isolated) should merge. splice(anything, empty) should NOT match -
     * this is the fix that stopped the splicer from auto-looping into a serum after a merge.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void splice_matches_only_two_isolated(GameTestHelper helper) {
        ensureBootstrapped();
        PerkEntry vit = new PerkEntry(Perks.VITALITY.getId(), 0.6F, Optional.empty(), Optional.empty());
        PerkEntry brawn = new PerkEntry(Perks.BRAWN.getId(), 0.7F, Optional.empty(), Optional.empty());
        VialContents a = VialContents.isolated(vit, Optional.empty(), Optional.empty(), Optional.empty());
        VialContents b = VialContents.isolated(brawn, Optional.empty(), Optional.empty(), Optional.empty());

        Optional<VialContents> merged = GeneOps.splice(a, b);
        helper.assertTrue(merged.isPresent(), "two isolated should splice");
        helper.assertTrue(merged.get().state() == VialState.ISOLATED, "merge result stays ISOLATED");
        helper.assertTrue(merged.get().perks().size() == 2, "two distinct perks preserved");

        Optional<VialContents> noMatch = GeneOps.splice(a, VialContents.EMPTY);
        helper.assertTrue(noMatch.isEmpty(), "isolated+empty must not splice (no serum path)");
        helper.succeed();
    }

    // ============================================================================
    //  Attribute perks - verify modifier presence + reversal
    // ============================================================================

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_vitality_adds_max_health_modifier(GameTestHelper helper) {
        assertAttributePerkCycles(helper, Perks.VITALITY.get(), Attributes.MAX_HEALTH, spawnZombie(helper));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_brawn_adds_attack_damage_modifier(GameTestHelper helper) {
        assertAttributePerkCycles(helper, Perks.BRAWN.get(), Attributes.ATTACK_DAMAGE, spawnZombie(helper));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_fleetfoot_adds_movement_speed_modifier(GameTestHelper helper) {
        assertAttributePerkCycles(helper, Perks.FLEETFOOT.get(), Attributes.MOVEMENT_SPEED, spawnZombie(helper));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_leap_adds_jump_strength_modifier(GameTestHelper helper) {
        // Horses have JUMP_STRENGTH out of the box; a zombie doesn't.
        Horse horse = helper.spawn(EntityType.HORSE, SUBJECT_POS);
        assertAttributePerkCycles(helper, Perks.LEAP.get(), Attributes.JUMP_STRENGTH, horse);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_stoneskin_adds_knockback_resistance_modifier(GameTestHelper helper) {
        assertAttributePerkCycles(helper, Perks.STONESKIN.get(), Attributes.KNOCKBACK_RESISTANCE, spawnZombie(helper));
    }

    // ============================================================================
    //  Mob-effect perks - verify effect applied + duration above night-vision flash cutoff
    // ============================================================================

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_gills_applies_water_breathing(GameTestHelper helper) {
        assertMobEffectPerkCycles(helper, Perks.GILLS.get(), MobEffects.WATER_BREATHING, spawnVillager(helper));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_fireproof_applies_fire_resistance(GameTestHelper helper) {
        assertMobEffectPerkCycles(helper, Perks.FIREPROOF.get(), MobEffects.FIRE_RESISTANCE, spawnVillager(helper));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_nocturnal_applies_night_vision_without_flashing(GameTestHelper helper) {
        Villager v = spawnVillager(helper);
        PerkEntry entry = new PerkEntry(Perks.NOCTURNAL.getId(), 1.0F, Optional.empty(), Optional.empty());
        Perks.NOCTURNAL.get().onEquip(v, entry);
        helper.assertTrue(v.hasEffect(MobEffects.NIGHT_VISION), "villager should have night vision after equip");
        int duration = v.getEffect(MobEffects.NIGHT_VISION).getDuration();
        // Vanilla flashes below ~200 ticks. Our applied duration is 300; we require >= 240 so
        // the refresh cadence keeps a safe margin.
        helper.assertTrue(duration >= 240,
                "night vision duration " + duration + " ticks must stay above the flash threshold");
        Perks.NOCTURNAL.get().onUnequip(v, entry);
        helper.assertTrue(!v.hasEffect(MobEffects.NIGHT_VISION), "night vision should be cleared on unequip");
        v.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Scale perks - Pehkui-backed size changes
    // ============================================================================

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_micro_shrinks_target_scale(GameTestHelper helper) {
        Zombie z = spawnZombie(helper);
        PerkEntry entry = new PerkEntry(Perks.MICRO.getId(), 1.0F, Optional.empty(), Optional.empty());
        Perks.MICRO.get().onEquip(z, entry);
        float target = PehkuiCompat.getTargetScale(z);
        // MICRO delta = -0.5, quality 1.0 -> target = clamp(0.05, 1.0 - 0.5) = 0.5.
        helper.assertTrue(Math.abs(target - 0.5F) < 0.05F,
                "micro should target 0.5x scale, got " + target);
        Perks.MICRO.get().onUnequip(z, entry);
        helper.assertTrue(Math.abs(PehkuiCompat.getTargetScale(z) - 1.0F) < 0.05F,
                "unequip should reset target scale to 1.0");
        z.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_macro_grows_target_scale(GameTestHelper helper) {
        Zombie z = spawnZombie(helper);
        PerkEntry entry = new PerkEntry(Perks.MACRO.getId(), 1.0F, Optional.empty(), Optional.empty());
        Perks.MACRO.get().onEquip(z, entry);
        float target = PehkuiCompat.getTargetScale(z);
        // MACRO delta = +1.0, quality 1.0 -> target = 2.0.
        helper.assertTrue(Math.abs(target - 2.0F) < 0.05F,
                "macro should target 2.0x scale, got " + target);
        Perks.MACRO.get().onUnequip(z, entry);
        z.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Defects - verify negative effects apply
    // ============================================================================

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void defect_frailty_applies_weakness(GameTestHelper helper) {
        assertMobEffectPerkCycles(helper, Perks.FRAILTY_DEFECT.get(), MobEffects.WEAKNESS, spawnVillager(helper));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void defect_starvation_applies_hunger(GameTestHelper helper) {
        assertMobEffectPerkCycles(helper, Perks.STARVATION_DEFECT.get(), MobEffects.HUNGER, spawnVillager(helper));
    }

    /**
     * Ignition Defect fires on ticks that are multiples of an internal interval. At quality 1.0
     * that interval is 100 ticks. Setting the entity's {@code tickCount} directly lets us trigger
     * the branch deterministically.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void defect_ignition_lights_on_fire_on_interval(GameTestHelper helper) {
        Zombie z = spawnZombie(helper);
        z.clearFire();
        z.tickCount = 100; // divisor for the quality-1.0 interval

        PerkEntry entry = new PerkEntry(Perks.IGNITION_DEFECT.getId(), 1.0F, Optional.empty(), Optional.empty());
        Perks.IGNITION_DEFECT.get().tick(z, entry);

        helper.assertTrue(z.getRemainingFireTicks() > 0,
                "ignition defect should have set the target on fire on the interval tick");
        z.clearFire();
        z.discard();
        helper.succeed();
    }

    // ============================================================================
    //  DUD - no-op perk, self-sample safeguard
    // ============================================================================

    /**
     * The dud perk exists so player self-samples yield inert vials. It must not touch any
     * attribute or effect on equip.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_dud_is_inert(GameTestHelper helper) {
        Zombie z = spawnZombie(helper);
        double initialHealth = z.getAttribute(Attributes.MAX_HEALTH).getValue();
        double initialSpeed = z.getAttribute(Attributes.MOVEMENT_SPEED).getValue();
        int initialEffectCount = z.getActiveEffects().size();

        PerkEntry entry = new PerkEntry(Perks.DUD.getId(), 1.0F, Optional.empty(), Optional.empty());
        Perks.DUD.get().onEquip(z, entry);

        helper.assertTrue(z.getAttribute(Attributes.MAX_HEALTH).getValue() == initialHealth,
                "dud must not touch max health");
        helper.assertTrue(z.getAttribute(Attributes.MOVEMENT_SPEED).getValue() == initialSpeed,
                "dud must not touch movement speed");
        helper.assertTrue(z.getActiveEffects().size() == initialEffectCount,
                "dud must not add any active effect");
        z.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Injection integration - end-to-end apply via PerkLifecycle.inject
    // ============================================================================

    /**
     * Injecting a native, full-quality isolated vial into a zombie must equip the perk with
     * zero defect chance (defect chance = 0.35 * (1 - quality) = 0 at quality 1.0). The
     * modifier must land, and the perk id must appear in EquippedPerks.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void inject_pristine_native_perk_never_defects(GameTestHelper helper) {
        ensureBootstrapped();
        Zombie z = spawnZombie(helper);
        // Vitality is in the zombie's species pool per SpeciesPool. Serum must carry a matching
        // donor type so the blood-compatibility gate lets it through.
        PerkEntry entry = new PerkEntry(Perks.VITALITY.getId(), 1.0F, Optional.empty(), Optional.empty());
        ResourceLocation zombieType = ResourceLocation.withDefaultNamespace("zombie");
        VialContents serum = VialContents.serum(java.util.List.of(entry),
                Optional.empty(), Optional.empty(), Optional.of(zombieType));

        PerkLifecycle.inject(z, serum, helper.getLevel().random);

        EquippedPerks eq = z.getData(DTAttachments.EQUIPPED_PERKS.get());
        helper.assertTrue(eq.has(Perks.VITALITY.getId()),
                "vitality should be equipped after inject; got " + eq.perks());
        AttributeInstance maxHealth = z.getAttribute(Attributes.MAX_HEALTH);
        helper.assertTrue(maxHealth.getModifier(Perks.VITALITY.getId()) != null,
                "vitality attribute modifier should be present on max health");
        z.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Player-specific attribute perks
    // ============================================================================

    /**
     * Longarm targets {@link Attributes#ENTITY_INTERACTION_RANGE}, which is only present on
     * players. This test verifies the perk actually lands on a mock player rather than silently
     * no-op'ing on entities that don't declare the attribute.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_longarm_applies_to_player(GameTestHelper helper) {
        ensureBootstrapped();
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        AttributeInstance reach = player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
        helper.assertTrue(reach != null, "mock player should have ENTITY_INTERACTION_RANGE");
        helper.assertTrue(reach.getModifier(Perks.LONGARM.getId()) == null, "no modifier before equip");

        PerkEntry entry = new PerkEntry(Perks.LONGARM.getId(), 1.0F, Optional.empty(), Optional.empty());
        Perks.LONGARM.get().onEquip(player, entry);
        helper.assertTrue(reach.getModifier(Perks.LONGARM.getId()) != null,
                "Longarm modifier must land on the player's ENTITY_INTERACTION_RANGE");

        Perks.LONGARM.get().onUnequip(player, entry);
        helper.assertTrue(reach.getModifier(Perks.LONGARM.getId()) == null, "modifier gone after unequip");
        player.discard();
        helper.succeed();
    }

    /**
     * Chameleon applies vanilla {@link MobEffects#INVISIBILITY}. Server-side test: after
     * onEquip, the target must have the effect present; after onUnequip, it must be gone.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_invisibility_applies_vanilla_effect(GameTestHelper helper) {
        ensureBootstrapped();
        Zombie z = spawnZombie(helper);
        PerkEntry entry = new PerkEntry(Perks.INVISIBILITY.getId(), 1.0F, Optional.empty(), Optional.empty());
        Perks.INVISIBILITY.get().onEquip(z, entry);
        helper.assertTrue(z.hasEffect(MobEffects.INVISIBILITY),
                "target must have vanilla INVISIBILITY after Chameleon equip");
        Perks.INVISIBILITY.get().onUnequip(z, entry);
        helper.assertTrue(!z.hasEffect(MobEffects.INVISIBILITY),
                "invisibility should be cleared on unequip");
        z.discard();
        helper.succeed();
    }

    /**
     * The runtime tick path: inject reachminer via a real splice-and-inject pipeline (not by
     * hand-forging a PerkEntry), then call {@link PerkLifecycle#tickPerks} - the same entry point
     * the {@code EntityTickEvent.Post} listener uses - and confirm the modifier lands when the
     * player crouches.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void reachminer_sneaking_end_to_end_via_tick_engine(GameTestHelper helper) {
        ensureBootstrapped();
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        ResourceLocation playerType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(net.minecraft.world.entity.EntityType.PLAYER);

        // Manually craft an isolated with the SNEAKING condition - this is what the sequencer
        // would produce if the roll landed on SNEAKING.
        PerkEntry withCondition = new PerkEntry(Perks.REACHMINER.getId(), 1.0F, Optional.empty(),
                Optional.of(PerkCondition.SNEAKING));
        VialContents isolated = VialContents.isolated(withCondition, Optional.empty(), Optional.empty(), Optional.empty());

        VialContents rawSelf = VialContents.rawPlayerBlood(player.getUUID(), "Alice", playerType,
                java.util.List.of(), farFutureExpiry(helper));
        VialContents serum = GeneOps.splice(rawSelf, isolated).orElseThrow();
        helper.assertTrue(serum.perks().get(0).condition().equals(Optional.of(PerkCondition.SNEAKING)),
                "splice must preserve the SNEAKING condition on the perk");

        PerkLifecycle.inject(player, serum, helper.getLevel().random);
        EquippedPerks eq = player.getData(DTAttachments.EQUIPPED_PERKS.get());
        PerkEntry equippedEntry = eq.perks().stream()
                .filter(e -> e.perkId().equals(Perks.REACHMINER.getId()))
                .findFirst().orElseThrow();
        helper.assertTrue(equippedEntry.condition().equals(Optional.of(PerkCondition.SNEAKING)),
                "equipped entry must retain SNEAKING condition after injection");

        AttributeInstance reach = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
        helper.assertTrue(reach.getModifier(Perks.REACHMINER.getId()) == null,
                "modifier must be absent right after inject (player not sneaking)");

        // Simulate sneaking. Both pose and shift-key are honored now.
        player.setPose(net.minecraft.world.entity.Pose.CROUCHING);
        PerkLifecycle.tickPerks(player);
        helper.assertTrue(reach.getModifier(Perks.REACHMINER.getId()) != null,
                "PerkLifecycle.tickPerks after crouch must apply the modifier");

        player.setPose(net.minecraft.world.entity.Pose.STANDING);
        player.setShiftKeyDown(false);
        PerkLifecycle.tickPerks(player);
        helper.assertTrue(reach.getModifier(Perks.REACHMINER.getId()) == null,
                "PerkLifecycle.tickPerks after stand must remove the modifier");

        player.discard();
        helper.succeed();
    }

    /**
     * Reachminer specifically with a SNEAKING condition on a mock player. Verifies the
     * tick-reconciliation path: crouch -> modifier appears, stand -> modifier disappears.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void reachminer_with_sneaking_condition_toggles(GameTestHelper helper) {
        ensureBootstrapped();
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        AttributeInstance reach = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
        helper.assertTrue(reach != null, "player must have BLOCK_INTERACTION_RANGE");

        PerkEntry entry = new PerkEntry(Perks.REACHMINER.getId(), 1.0F,
                Optional.empty(), Optional.of(PerkCondition.SNEAKING));
        Perks.REACHMINER.get().onEquip(player, entry);

        helper.assertTrue(!player.isCrouching(), "mock player should start un-crouched");
        helper.assertTrue(reach.getModifier(Perks.REACHMINER.getId()) == null,
                "modifier must be absent while not sneaking");

        player.setPose(net.minecraft.world.entity.Pose.CROUCHING);
        helper.assertTrue(player.isCrouching(), "setPose(CROUCHING) must make isCrouching() true");
        Perks.REACHMINER.get().tick(player, entry);
        helper.assertTrue(reach.getModifier(Perks.REACHMINER.getId()) != null,
                "crouching plus tick must apply the reach modifier");

        player.setPose(net.minecraft.world.entity.Pose.STANDING);
        Perks.REACHMINER.get().tick(player, entry);
        helper.assertTrue(reach.getModifier(Perks.REACHMINER.getId()) == null,
                "standing plus tick must remove the reach modifier");

        Perks.REACHMINER.get().onUnequip(player, entry);
        player.discard();
        helper.succeed();
    }

    // ============================================================================
    //  End-to-end: player-blood serum lets a player inject their own genes
    // ============================================================================

    /**
     * The full self-injection loop the user asked about with Reachminer specifically. Steps:
     * <ol>
     *   <li>Craft a player-blood RAW (locked snapshot of the player's genome).</li>
     *   <li>Craft an ISOLATED reachminer gene.</li>
     *   <li>Splice them -> SERUM whose donor identity carries over from the player blood.</li>
     *   <li>Inject the same player -> compatibility gate passes, modifier lands.</li>
     * </ol>
     * If any step of that pipeline drops the perk on the floor, this test catches it.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_reachminer_full_self_inject_pipeline(GameTestHelper helper) {
        ensureBootstrapped();
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        UUID selfId = player.getUUID();
        ResourceLocation playerType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(net.minecraft.world.entity.EntityType.PLAYER);

        VialContents rawSelf = VialContents.rawPlayerBlood(selfId, "Alice", playerType,
                java.util.List.of(), farFutureExpiry(helper));
        PerkEntry reach = new PerkEntry(Perks.REACHMINER.getId(), 1.0F, Optional.empty(), Optional.empty());
        VialContents isolated = VialContents.isolated(reach, Optional.empty(), Optional.empty(), Optional.empty());

        Optional<VialContents> spliced = GeneOps.splice(rawSelf, isolated);
        helper.assertTrue(spliced.isPresent(), "player-blood RAW should splice with isolated");
        VialContents serum = spliced.get();
        helper.assertTrue(serum.state() == VialState.SERUM, "result should be SERUM");
        helper.assertTrue(serum.donor().equals(Optional.of(selfId)),
                "serum donor should carry over from the player blood");

        PerkLifecycle.inject(player, serum, helper.getLevel().random);

        AttributeInstance reachAttr = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
        helper.assertTrue(reachAttr.getModifier(Perks.REACHMINER.getId()) != null,
                "reachminer modifier must be present on the player's block-interaction range");
        double delta = reachAttr.getValue() - reachAttr.getBaseValue();
        helper.assertTrue(delta > 1.0,
                "reach should extend by at least +1 block after reachminer equip (got " + delta + ")");

        player.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Coverage sweep - every remaining perk, one test per, so a silent
    //  no-op reveals itself immediately.
    // ============================================================================

    // Attribute perks that only players have the attribute for.
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_bulwark_applies(GameTestHelper helper) {
        assertAttributePerkCyclesOnMockPlayer(helper, Perks.BULWARK.get(), Attributes.ARMOR);
    }
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_cushion_applies(GameTestHelper helper) {
        assertAttributePerkCyclesOnMockPlayer(helper, Perks.CUSHION.get(), Attributes.SAFE_FALL_DISTANCE);
    }
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_reachminer_applies(GameTestHelper helper) {
        assertAttributePerkCyclesOnMockPlayer(helper, Perks.REACHMINER.get(), Attributes.BLOCK_INTERACTION_RANGE);
    }
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_deepbreath_applies(GameTestHelper helper) {
        assertAttributePerkCyclesOnMockPlayer(helper, Perks.DEEPBREATH.get(), Attributes.OXYGEN_BONUS);
    }
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_hollowbones_applies(GameTestHelper helper) {
        assertAttributePerkCyclesOnMockPlayer(helper, Perks.HOLLOWBONES.get(), Attributes.GRAVITY);
    }
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_miner_applies(GameTestHelper helper) {
        assertAttributePerkCyclesOnMockPlayer(helper, Perks.MINER.get(), Attributes.BLOCK_BREAK_SPEED);
    }

    // Mob-effect perks not previously tested.
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_regen_applies(GameTestHelper helper) {
        assertMobEffectPerkCycles(helper, Perks.REGEN.get(), MobEffects.REGENERATION, spawnVillager(helper));
    }
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_grace_applies(GameTestHelper helper) {
        assertMobEffectPerkCycles(helper, Perks.GRACE.get(), MobEffects.DOLPHINS_GRACE, spawnVillager(helper));
    }
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_luck_applies(GameTestHelper helper) {
        assertMobEffectPerkCycles(helper, Perks.LUCK.get(), MobEffects.LUCK, spawnVillager(helper));
    }
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_invisibility_applies(GameTestHelper helper) {
        assertMobEffectPerkCycles(helper, Perks.INVISIBILITY.get(), MobEffects.INVISIBILITY, spawnVillager(helper));
    }

    // Defects using mob effects.
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void defect_withering_applies(GameTestHelper helper) {
        assertMobEffectPerkCycles(helper, Perks.WITHERING_DEFECT.get(), MobEffects.WITHER, spawnVillager(helper));
    }

    // ============================================================================
    //  Blood compatibility gate
    // ============================================================================

    /**
     * Mob-blood serum must match the target's entity type. Trying to inject a pig-typed serum
     * into a zombie must be silently refused: the target keeps no equipped perk.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void inject_refused_when_species_mismatch(GameTestHelper helper) {
        ensureBootstrapped();
        Zombie z = spawnZombie(helper);
        PerkEntry entry = new PerkEntry(Perks.VITALITY.getId(), 1.0F, Optional.empty(), Optional.empty());
        ResourceLocation pigType = ResourceLocation.withDefaultNamespace("pig");
        VialContents serum = VialContents.serum(java.util.List.of(entry),
                Optional.empty(), Optional.empty(), Optional.of(pigType));

        PerkLifecycle.inject(z, serum, helper.getLevel().random);

        EquippedPerks eq = z.getData(DTAttachments.EQUIPPED_PERKS.get());
        helper.assertTrue(!eq.has(Perks.VITALITY.getId()),
                "pig-blood serum must be refused against a zombie target");
        z.discard();
        helper.succeed();
    }

    /**
     * Player-blood serum locks to a specific donor UUID: a serum tagged with Player A's UUID
     * must not equip on Player B. Uses two mock players with generated UUIDs.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void inject_refused_when_player_uuid_mismatch(GameTestHelper helper) {
        ensureBootstrapped();
        var playerB = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        UUID otherPlayerUuid = UUID.randomUUID();
        PerkEntry entry = new PerkEntry(Perks.VITALITY.getId(), 1.0F, Optional.empty(), Optional.empty());
        ResourceLocation playerType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(net.minecraft.world.entity.EntityType.PLAYER);
        VialContents serum = VialContents.serum(java.util.List.of(entry),
                Optional.of(otherPlayerUuid), Optional.of("PlayerA"), Optional.of(playerType));

        PerkLifecycle.inject(playerB, serum, helper.getLevel().random);

        EquippedPerks eq = playerB.getData(DTAttachments.EQUIPPED_PERKS.get());
        helper.assertTrue(!eq.has(Perks.VITALITY.getId()),
                "player A's serum must be refused against player B");
        playerB.discard();
        helper.succeed();
    }

    /**
     * The splicer's RAW + ISOLATED path produces a SERUM whose donor identity carries over
     * from the RAW blood, not the ISOLATED gene. Blood is the delivery vehicle.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void splice_raw_isolated_produces_serum_with_raw_donor(GameTestHelper helper) {
        ensureBootstrapped();
        ResourceLocation cowType = ResourceLocation.withDefaultNamespace("cow");
        ResourceLocation pigType = ResourceLocation.withDefaultNamespace("pig");
        UUID rawDonor = UUID.randomUUID();

        VialContents raw = VialContents.raw(java.util.List.of(), rawDonor, Optional.of("some cow"), cowType);
        PerkEntry vit = new PerkEntry(Perks.VITALITY.getId(), 0.7F, Optional.empty(), Optional.empty());
        VialContents iso = VialContents.isolated(vit, Optional.of(UUID.randomUUID()),
                Optional.of("some pig"), Optional.of(pigType));

        Optional<VialContents> result = GeneOps.splice(raw, iso);
        helper.assertTrue(result.isPresent(), "RAW + ISOLATED should splice");
        VialContents serum = result.get();
        helper.assertTrue(serum.state() == VialState.SERUM, "result should be SERUM");
        helper.assertTrue(serum.donorType().equals(Optional.of(cowType)),
                "donor type should come from the RAW (cow), not the isolated (pig)");
        helper.assertTrue(serum.donor().equals(Optional.of(rawDonor)),
                "donor UUID should come from the RAW");
        helper.assertTrue(serum.perks().size() == 1 && serum.perks().get(0).perkId().equals(Perks.VITALITY.getId()),
                "perk payload should come from the ISOLATED");
        helper.succeed();
    }

    // ============================================================================
    //  Conditions + purity threshold
    // ============================================================================

    /**
     * A perk with a SPRINTING condition must not apply its modifier while the host isn't
     * sprinting. Toggling sprint on/off across ticks must add/remove the modifier accordingly.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void condition_gates_attribute_perk_on_and_off(GameTestHelper helper) {
        ensureBootstrapped();
        Zombie z = spawnZombie(helper);
        z.setSprinting(false);

        PerkEntry entry = new PerkEntry(Perks.FLEETFOOT.getId(), 1.0F,
                Optional.empty(), Optional.of(PerkCondition.SPRINTING));
        Perks.FLEETFOOT.get().onEquip(z, entry);
        AttributeInstance speed = z.getAttribute(Attributes.MOVEMENT_SPEED);
        helper.assertTrue(speed.getModifier(Perks.FLEETFOOT.getId()) == null,
                "modifier must be absent when the sprint condition is unmet");

        z.setSprinting(true);
        Perks.FLEETFOOT.get().tick(z, entry);
        helper.assertTrue(speed.getModifier(Perks.FLEETFOOT.getId()) != null,
                "sprint start should add the modifier on the next tick");

        z.setSprinting(false);
        Perks.FLEETFOOT.get().tick(z, entry);
        helper.assertTrue(speed.getModifier(Perks.FLEETFOOT.getId()) == null,
                "sprint stop should remove the modifier on the next tick");

        Perks.FLEETFOOT.get().onUnequip(z, entry);
        z.discard();
        helper.succeed();
    }

    /**
     * Injecting a quality-0.0 vial should always add a defect on top of the intended perk,
     * because the purity roll is 100% at that quality.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void inject_below_purity_threshold_adds_defect(GameTestHelper helper) {
        ensureBootstrapped();
        Zombie z = spawnZombie(helper);
        PerkEntry entry = new PerkEntry(Perks.VITALITY.getId(), 0.0F, Optional.empty(), Optional.empty());
        ResourceLocation zombieType = ResourceLocation.withDefaultNamespace("zombie");
        VialContents serum = VialContents.serum(java.util.List.of(entry),
                Optional.empty(), Optional.empty(), Optional.of(zombieType));

        PerkLifecycle.inject(z, serum, helper.getLevel().random);
        EquippedPerks eq = z.getData(DTAttachments.EQUIPPED_PERKS.get());

        helper.assertTrue(eq.has(Perks.VITALITY.getId()), "target should still have the intended perk");
        boolean anyDefect = eq.perks().stream()
                .map(e -> Perks.get(e.perkId()))
                .anyMatch(p -> p != null && p.isDefect());
        helper.assertTrue(anyDefect, "quality-0 injection must always append a defect");
        z.discard();
        helper.succeed();
    }

    /**
     * The sequencer condition roll depends on quality: high-quality entries almost always
     * come back as {@link PerkCondition#ALWAYS}. This test runs sequencing many times with a
     * quality-1.0 pool (produced by rerolls inside the engine) and verifies the ALWAYS bias.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void high_quality_sequencing_biases_toward_always(GameTestHelper helper) {
        ensureBootstrapped();
        Cow cow = helper.spawn(EntityType.COW, SUBJECT_POS);

        // Craft a synthetic RAW pool with quality 1.0 for a perk that has restrictive
        // conditions in its allowed set - Fleetfoot fits. sequenceOne will roll a fresh
        // quality per pull, so we still expect variance; the assertion is loose.
        PerkEntry stub = new PerkEntry(Perks.FLEETFOOT.getId(), 1.0F, Optional.empty(), Optional.empty());
        VialContents raw = VialContents.raw(List.of(stub), cow.getUUID(), Optional.empty(),
                ResourceLocation.withDefaultNamespace("cow"));

        // Force high quality by overriding the pool's entry list - sequenceOne rerolls quality
        // but the condition roll is separate; run many trials and count.
        int alwaysCount = 0;
        int trials = 400;
        for (int i = 0; i < trials; i++) {
            VialContents iso = GeneOps.sequenceOne(raw, helper.getLevel().random);
            if (iso.perks().isEmpty()) continue;
            PerkEntry e = iso.perks().get(0);
            if (e.condition().isEmpty() || e.condition().get() == PerkCondition.ALWAYS) alwaysCount++;
        }
        // With the average quality bias built into rollQuality (roughly 0.5), ALWAYS should appear
        // at least ~1/3 of the time. Keep the bar loose so it's not flaky.
        helper.assertTrue(alwaysCount > trials / 4,
                "expected at least " + (trials / 4) + " ALWAYS rolls, got " + alwaysCount);
        cow.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Helpers
    // ============================================================================

    /**
     * Perks self-register via the mod's {@link Perks#REGISTRAR}, so by the time gametests run
     * the registry is populated. Species pools would normally load from datapack JSON but the
     * gametest environment does not always run a resource reload; we prime a minimal pool set
     * directly so the sampling pipeline has something to draw from.
     */
    private static void ensureBootstrapped() {
        if (SpeciesPool.registeredSpecies().isEmpty()) {
            java.util.Map<net.minecraft.world.entity.EntityType<?>, List<SpeciesPool.Weighted>> pools = new java.util.HashMap<>();
            pools.put(EntityType.COW, List.of(
                    new SpeciesPool.Weighted(Perks.VITALITY.getId(), 6),
                    new SpeciesPool.Weighted(Perks.BRAWN.getId(), 2),
                    new SpeciesPool.Weighted(Perks.REGEN.getId(), 2)));
            pools.put(EntityType.ZOMBIE, List.of(
                    new SpeciesPool.Weighted(Perks.VITALITY.getId(), 4),
                    new SpeciesPool.Weighted(Perks.BRAWN.getId(), 3)));
            pools.put(EntityType.PLAYER, List.of(
                    new SpeciesPool.Weighted(Perks.DUD.getId(), 10)));
            SpeciesPool.installForTests(pools);
        }
    }

    private static void assertGrade(GameTestHelper helper, float quality, PerkGrade expected) {
        PerkGrade actual = PerkGrade.fromQuality(quality);
        helper.assertTrue(actual == expected,
                "PerkGrade.fromQuality(" + quality + ") expected " + expected + " but got " + actual);
    }

    private static Zombie spawnZombie(GameTestHelper helper) {
        ensureBootstrapped();
        return helper.spawn(EntityType.ZOMBIE, SUBJECT_POS);
    }

    private static Villager spawnVillager(GameTestHelper helper) {
        ensureBootstrapped();
        return helper.spawn(EntityType.VILLAGER, SUBJECT_POS);
    }

    /**
     * Shared shape for AttributePerk tests: equip at quality 1.0 -> modifier present; unequip ->
     * modifier gone.
     */
    private static void assertAttributePerkCycles(GameTestHelper helper, Perk perk,
                                                  Holder<Attribute> attribute, LivingEntity subject) {
        AttributeInstance inst = subject.getAttribute(attribute);
        helper.assertTrue(inst != null, "subject entity missing attribute " + attribute + " for perk " + perk.id());
        helper.assertTrue(inst.getModifier(perk.id()) == null,
                "modifier should not be present before equip");

        PerkEntry entry = new PerkEntry(perk.id(), 1.0F, Optional.empty(), Optional.empty());
        perk.onEquip(subject, entry);
        helper.assertTrue(inst.getModifier(perk.id()) != null,
                perk.id() + " modifier should be present after equip");

        perk.onUnequip(subject, entry);
        helper.assertTrue(inst.getModifier(perk.id()) == null,
                perk.id() + " modifier should be gone after unequip");
        subject.discard();
        helper.succeed();
    }

    /**
     * Wraps a mock player + attribute-modifier cycle assertion. Kept separate from
     * {@link #assertAttributePerkCycles} because the mock player's {@code discard()}
     * behavior differs from spawned mob entities.
     */
    private static void assertAttributePerkCyclesOnMockPlayer(GameTestHelper helper, Perk perk,
                                                              Holder<Attribute> attribute) {
        ensureBootstrapped();
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        AttributeInstance inst = player.getAttribute(attribute);
        helper.assertTrue(inst != null, "mock player is missing attribute " + attribute + " for perk " + perk.id());
        helper.assertTrue(inst.getModifier(perk.id()) == null, "no modifier before equip");

        PerkEntry entry = new PerkEntry(perk.id(), 1.0F, Optional.empty(), Optional.empty());
        perk.onEquip(player, entry);
        helper.assertTrue(inst.getModifier(perk.id()) != null,
                perk.id() + " modifier must land on the player's " + attribute.getRegisteredName());

        perk.onUnequip(player, entry);
        helper.assertTrue(inst.getModifier(perk.id()) == null,
                perk.id() + " modifier must be gone after unequip");
        player.discard();
        helper.succeed();
    }

    /**
     * Shared shape for MobEffectPerk tests: equip -> effect present, duration above the
     * night-vision flash cutoff; unequip -> effect gone.
     */
    private static void assertMobEffectPerkCycles(GameTestHelper helper, Perk perk,
                                                  Holder<net.minecraft.world.effect.MobEffect> effect,
                                                  LivingEntity subject) {
        PerkEntry entry = new PerkEntry(perk.id(), 1.0F, Optional.empty(), Optional.empty());
        perk.onEquip(subject, entry);
        helper.assertTrue(subject.hasEffect(effect),
                perk.id() + " should apply " + effect.getRegisteredName() + " on equip");
        int duration = subject.getEffect(effect).getDuration();
        helper.assertTrue(duration >= 240,
                perk.id() + " effect duration " + duration + " must sit above the night-vision flash floor");

        perk.onUnequip(subject, entry);
        helper.assertTrue(!subject.hasEffect(effect),
                perk.id() + " should clear " + effect.getRegisteredName() + " on unequip");
        subject.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Regression coverage for the review fixes
    // ============================================================================

    /**
     * Re-injecting a lower-quality serum used to downgrade the equipped modifier while
     * EquippedPerks still reported the higher quality. With averaging + resolved-entry onEquip
     * the modifier must reflect the averaged value, and the tracker must agree.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void inject_averages_when_reinjecting_same_perk(GameTestHelper helper) {
        ensureBootstrapped();
        Zombie z = spawnZombie(helper);
        ResourceLocation zombieType = ResourceLocation.withDefaultNamespace("zombie");

        VialContents highSerum = VialContents.serum(
                java.util.List.of(new PerkEntry(Perks.VITALITY.getId(), 0.9F, Optional.empty(), Optional.empty())),
                Optional.empty(), Optional.empty(), Optional.of(zombieType));
        VialContents lowSerum = VialContents.serum(
                java.util.List.of(new PerkEntry(Perks.VITALITY.getId(), 0.3F, Optional.empty(), Optional.empty())),
                Optional.empty(), Optional.empty(), Optional.of(zombieType));

        PerkLifecycle.inject(z, highSerum, helper.getLevel().random);
        PerkLifecycle.inject(z, lowSerum, helper.getLevel().random);

        EquippedPerks eq = z.getData(DTAttachments.EQUIPPED_PERKS.get());
        PerkEntry resolved = eq.find(Perks.VITALITY.getId()).orElseThrow();
        helper.assertTrue(Math.abs(resolved.quality() - 0.6F) < 1e-4F,
                "tracker quality should average to 0.6 after high+low, got " + resolved.quality());

        // The actual attribute modifier must be sized off the averaged (0.6) quality, not the
        // last-injected 0.3. AttributePerk floors at MIN_MULTIPLIER = 0.4 but 0.6 is above that.
        AttributeInstance maxHealth = z.getAttribute(Attributes.MAX_HEALTH);
        var modifier = maxHealth.getModifier(Perks.VITALITY.getId());
        helper.assertTrue(modifier != null, "vitality modifier should still be present");
        // Vitality full magnitude = 6.0. At quality 0.6, modifier amount should be 3.6.
        helper.assertTrue(Math.abs(modifier.amount() - 3.6D) < 1e-3D,
                "modifier amount should reflect the averaged quality, got " + modifier.amount());
        z.discard();
        helper.succeed();
    }

    /**
     * A SERUM built without a donorType (malformed or crafted via /data) used to bypass the
     * compatibility gate. The strict gate refuses it now.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void inject_refused_when_donor_type_missing(GameTestHelper helper) {
        ensureBootstrapped();
        Zombie z = spawnZombie(helper);
        PerkEntry entry = new PerkEntry(Perks.VITALITY.getId(), 1.0F, Optional.empty(), Optional.empty());
        VialContents serum = VialContents.serum(java.util.List.of(entry),
                Optional.empty(), Optional.empty(), Optional.empty());

        PerkLifecycle.inject(z, serum, helper.getLevel().random);

        EquippedPerks eq = z.getData(DTAttachments.EQUIPPED_PERKS.get());
        helper.assertTrue(!eq.has(Perks.VITALITY.getId()),
                "serum without donorType must be refused (no legacy passthrough)");
        z.discard();
        helper.succeed();
    }

    /**
     * A denatured entry (quality below CORRUPTED's floor) is inert: no modifier, no effect, no
     * scale change. The isCurrentlyActive gate handles this uniformly across all perk families.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void denatured_perk_expresses_nothing(GameTestHelper helper) {
        ensureBootstrapped();
        Zombie z = spawnZombie(helper);
        PerkEntry denatured = new PerkEntry(Perks.VITALITY.getId(), 0.02F, Optional.empty(), Optional.empty());
        helper.assertTrue(denatured.isDenatured(), "quality 0.02 should be flagged denatured");
        helper.assertTrue(!Perks.VITALITY.get().isCurrentlyActive(z, denatured),
                "isCurrentlyActive must be false for a denatured entry");

        Perks.VITALITY.get().onEquip(z, denatured);
        AttributeInstance maxHealth = z.getAttribute(Attributes.MAX_HEALTH);
        helper.assertTrue(maxHealth.getModifier(Perks.VITALITY.getId()) == null,
                "denatured attribute perk must not add a modifier on equip");

        // Mob-effect and scale perks route through the same gate.
        PerkEntry denaturedEffect = new PerkEntry(Perks.GILLS.getId(), 0.02F, Optional.empty(), Optional.empty());
        Perks.GILLS.get().onEquip(z, denaturedEffect);
        helper.assertTrue(!z.hasEffect(net.minecraft.world.effect.MobEffects.WATER_BREATHING),
                "denatured mob-effect perk must not apply the effect on equip");

        z.discard();
        helper.succeed();
    }

    /**
     * A corrupted-tier entry (quality just above the denatured floor) still uses the
     * AttributePerk MIN_MULTIPLIER so the perk is felt in play. This locks in the "corrupted
     * hurts but functions, denatured does nothing" contract.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void corrupted_perk_applies_at_min_multiplier_floor(GameTestHelper helper) {
        ensureBootstrapped();
        Zombie z = spawnZombie(helper);
        PerkEntry corrupted = new PerkEntry(Perks.VITALITY.getId(), 0.10F, Optional.empty(), Optional.empty());
        helper.assertTrue(!corrupted.isDenatured(), "quality 0.10 must not be denatured");
        helper.assertTrue(corrupted.grade() == PerkGrade.CORRUPTED, "quality 0.10 is CORRUPTED tier");

        Perks.VITALITY.get().onEquip(z, corrupted);
        AttributeInstance maxHealth = z.getAttribute(Attributes.MAX_HEALTH);
        var modifier = maxHealth.getModifier(Perks.VITALITY.getId());
        helper.assertTrue(modifier != null, "corrupted-tier vitality must still add a modifier");
        // Vitality full magnitude 6.0; MIN_MULTIPLIER floor is 0.4 so amount is 6.0 * 0.4 = 2.4.
        helper.assertTrue(Math.abs(modifier.amount() - 2.4D) < 1e-3D,
                "corrupted-tier modifier should sit at the 0.4 floor, got " + modifier.amount());
        Perks.VITALITY.get().onUnequip(z, corrupted);
        z.discard();
        helper.succeed();
    }

    /**
     * A denatured defect must not fire either. IgnitionDefect on quality 0 should never ignite
     * even on its interval tick.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void denatured_defect_does_not_fire(GameTestHelper helper) {
        ensureBootstrapped();
        Zombie z = spawnZombie(helper);
        z.clearFire();
        z.tickCount = 100;

        PerkEntry denatured = new PerkEntry(Perks.IGNITION_DEFECT.getId(), 0.02F, Optional.empty(), Optional.empty());
        Perks.IGNITION_DEFECT.get().tick(z, denatured);
        helper.assertTrue(z.getRemainingFireTicks() == 0,
                "denatured ignition defect must not set the target on fire");
        z.discard();
        helper.succeed();
    }

    /**
     * PerkLifecycle.clearAllPerks runs onUnequip on every entry and resets the attachment. This is
     * the path LivingDeath uses; if it drops entries silently, respawn/revive leaves stale
     * modifiers behind.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void clear_all_perks_removes_modifiers_and_empties_tracker(GameTestHelper helper) {
        ensureBootstrapped();
        Zombie z = spawnZombie(helper);
        ResourceLocation zombieType = ResourceLocation.withDefaultNamespace("zombie");
        PerkEntry entry = new PerkEntry(Perks.VITALITY.getId(), 1.0F, Optional.empty(), Optional.empty());
        VialContents serum = VialContents.serum(java.util.List.of(entry),
                Optional.empty(), Optional.empty(), Optional.of(zombieType));
        PerkLifecycle.inject(z, serum, helper.getLevel().random);

        AttributeInstance maxHealth = z.getAttribute(Attributes.MAX_HEALTH);
        helper.assertTrue(maxHealth.getModifier(Perks.VITALITY.getId()) != null,
                "sanity: modifier should be present after inject");

        PerkLifecycle.clearAllPerks(z);

        helper.assertTrue(maxHealth.getModifier(Perks.VITALITY.getId()) == null,
                "clearAllPerks must strip attribute modifiers via onUnequip");
        EquippedPerks eq = z.getData(DTAttachments.EQUIPPED_PERKS.get());
        helper.assertTrue(eq.perks().isEmpty(), "attachment should be EMPTY after clearAllPerks");
        z.discard();
        helper.succeed();
    }

    /**
     * PerkLifecycle.reapplyAll is the login/dimension-change entrypoint. Simulate an entity whose
     * attribute modifiers have been externally cleared (as happens on dimension transfer) and
     * confirm reapplyAll rebuilds them from the attachment.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void reapply_all_rebuilds_modifiers_from_attachment(GameTestHelper helper) {
        ensureBootstrapped();
        Zombie z = spawnZombie(helper);
        ResourceLocation zombieType = ResourceLocation.withDefaultNamespace("zombie");
        PerkEntry entry = new PerkEntry(Perks.VITALITY.getId(), 1.0F, Optional.empty(), Optional.empty());
        VialContents serum = VialContents.serum(java.util.List.of(entry),
                Optional.empty(), Optional.empty(), Optional.of(zombieType));
        PerkLifecycle.inject(z, serum, helper.getLevel().random);

        // Simulate the "modifiers dropped by transfer" case: strip the modifier but keep the
        // attachment.
        AttributeInstance maxHealth = z.getAttribute(Attributes.MAX_HEALTH);
        maxHealth.removeModifier(Perks.VITALITY.getId());
        helper.assertTrue(maxHealth.getModifier(Perks.VITALITY.getId()) == null,
                "sanity: modifier should be gone after explicit removal");

        PerkLifecycle.reapplyAll(z);

        helper.assertTrue(maxHealth.getModifier(Perks.VITALITY.getId()) != null,
                "reapplyAll must rebuild the attribute modifier from the equipped entry");
        z.discard();
        helper.succeed();
    }

    /**
     * ALONE was the tick-loop hot spot: a 32-block box scan per equipped mob per tick. The
     * cache locks the answer for a 5-second window; ticks past the boundary force a recompute.
     * We can't easily observe recompute vs. cache hit from outside so the assertion is
     * intentionally coarse: it verifies stability inside the window and that a wraparound
     * across the boundary does not throw.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void alone_condition_is_cached_within_window(GameTestHelper helper) {
        ensureBootstrapped();
        Zombie z = spawnZombie(helper);
        z.tickCount = 1000;

        boolean firstCall = PerkCondition.ALONE.check(z);
        boolean sameTickCall = PerkCondition.ALONE.check(z);
        helper.assertTrue(firstCall == sameTickCall,
                "back-to-back calls in the same tick must agree");

        z.tickCount = 1050; // 2.5 seconds in; still inside the 100-tick window
        helper.assertTrue(PerkCondition.ALONE.check(z) == firstCall,
                "in-window call should return the cached value");

        z.tickCount = 1200; // past the 5-second window; may recompute but must not throw
        PerkCondition.ALONE.check(z);

        z.discard();
        helper.succeed();
    }

    /**
     * Player donors are always named; unnamed mob donors leave donorName empty so the tooltip
     * can render the species label off the entity type id, which localizes on the viewer's
     * client. A renamed mob (nametag) does carry its custom name across.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void raw_omits_donor_name_for_unnamed_mob(GameTestHelper helper) {
        ensureBootstrapped();
        Cow cow = helper.spawn(EntityType.COW, SUBJECT_POS);
        VialContents rawUnnamed = GeneOps.rollRawFromEntity(cow, helper.getLevel().random);
        helper.assertTrue(rawUnnamed.donorName().isEmpty(),
                "unnamed cow should not stamp a donorName");

        cow.setCustomName(net.minecraft.network.chat.Component.literal("Bessie"));
        VialContents rawNamed = GeneOps.rollRawFromEntity(cow, helper.getLevel().random);
        helper.assertTrue(rawNamed.donorName().equals(Optional.of("Bessie")),
                "custom-named cow should stamp its display name; got " + rawNamed.donorName());

        cow.discard();
        helper.succeed();
    }

    /**
     * The Perks registry contains every built-in perk after mod init, and the snapshot returned
     * by {@link Perks#all} is a fresh immutable list on each call.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perks_registry_populated_and_snapshot_immutable(GameTestHelper helper) {
        ensureBootstrapped();
        List<Perk> all = Perks.all();
        helper.assertTrue(!all.isEmpty(), "perk registry should be populated after mod init");
        helper.assertTrue(Perks.get(Perks.VITALITY.getId()) != null,
                "vitality must resolve through the registry lookup");
        boolean threw = false;
        try {
            all.add(null);
        } catch (UnsupportedOperationException e) {
            threw = true;
        }
        helper.assertTrue(threw, "Perks.all() snapshot must be unmodifiable");
        helper.succeed();
    }

    // ============================================================================
    //  Block entity + gun state machines
    // ============================================================================

    /**
     * Sequencer is all-or-nothing: a RAW input plus three EMPTY output vials must complete
     * atomically, mint three ISOLATED outputs, and drain the input to EMPTY. Progress ticks are
     * driven manually so the test does not need to wait for the config-driven threshold.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void sequencer_all_or_nothing_dispatch(GameTestHelper helper) {
        ensureBootstrapped();
        Cow cow = helper.spawn(EntityType.COW, SUBJECT_POS);
        VialContents raw = GeneOps.rollRawFromEntity(cow, helper.getLevel().random);
        cow.discard();

        var be = new com.confect1on.dynetech.blockentity.GeneSequencerBlockEntity(
                new BlockPos(0, 0, 0), com.confect1on.dynetech.block.DTBlocks.GENE_SEQUENCER.get().defaultBlockState());
        be.inventory().setItem(com.confect1on.dynetech.blockentity.GeneSequencerBlockEntity.INPUT_SLOT,
                com.confect1on.dynetech.item.GeneVialItem.withContents(raw));
        for (int i = 0; i < com.confect1on.dynetech.blockentity.GeneSequencerBlockEntity.OUTPUT_SLOT_COUNT; i++) {
            be.inventory().setItem(com.confect1on.dynetech.blockentity.GeneSequencerBlockEntity.OUTPUT_SLOT_START + i,
                    com.confect1on.dynetech.item.GeneVialItem.empty());
        }

        int threshold = com.confect1on.dynetech.config.DTConfig.SEQUENCER_TICKS.get();
        for (int i = 0; i <= threshold; i++) be.serverTick(helper.getLevel());

        helper.assertTrue(com.confect1on.dynetech.item.GeneVialItem.isState(
                        be.inventory().getItem(com.confect1on.dynetech.blockentity.GeneSequencerBlockEntity.INPUT_SLOT),
                        VialState.EMPTY),
                "input slot should be drained to EMPTY after dispatch");
        for (int i = 0; i < com.confect1on.dynetech.blockentity.GeneSequencerBlockEntity.OUTPUT_SLOT_COUNT; i++) {
            VialContents out = com.confect1on.dynetech.item.GeneVialItem.getContents(
                    be.inventory().getItem(com.confect1on.dynetech.blockentity.GeneSequencerBlockEntity.OUTPUT_SLOT_START + i));
            helper.assertTrue(out.state() == VialState.ISOLATED,
                    "output slot " + i + " should hold ISOLATED, got " + out.state());
            helper.assertTrue(out.perks().size() == 1,
                    "isolated output must carry exactly one perk");
        }
        helper.succeed();
    }

    /**
     * If any output slot is missing an EMPTY vial the sequencer refuses to spend progress. This
     * is the guard that stops the machine from consuming a RAW vial without producing outputs.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void sequencer_refuses_partial_output_slots(GameTestHelper helper) {
        ensureBootstrapped();
        Cow cow = helper.spawn(EntityType.COW, SUBJECT_POS);
        VialContents raw = GeneOps.rollRawFromEntity(cow, helper.getLevel().random);
        cow.discard();

        var be = new com.confect1on.dynetech.blockentity.GeneSequencerBlockEntity(
                new BlockPos(0, 0, 0), com.confect1on.dynetech.block.DTBlocks.GENE_SEQUENCER.get().defaultBlockState());
        be.inventory().setItem(com.confect1on.dynetech.blockentity.GeneSequencerBlockEntity.INPUT_SLOT,
                com.confect1on.dynetech.item.GeneVialItem.withContents(raw));
        // Two of three output slots filled with empty vials, third left blank.
        be.inventory().setItem(com.confect1on.dynetech.blockentity.GeneSequencerBlockEntity.OUTPUT_SLOT_START,
                com.confect1on.dynetech.item.GeneVialItem.empty());
        be.inventory().setItem(com.confect1on.dynetech.blockentity.GeneSequencerBlockEntity.OUTPUT_SLOT_START + 1,
                com.confect1on.dynetech.item.GeneVialItem.empty());

        int threshold = com.confect1on.dynetech.config.DTConfig.SEQUENCER_TICKS.get();
        for (int i = 0; i <= threshold; i++) be.serverTick(helper.getLevel());

        helper.assertTrue(com.confect1on.dynetech.item.GeneVialItem.isState(
                        be.inventory().getItem(com.confect1on.dynetech.blockentity.GeneSequencerBlockEntity.INPUT_SLOT),
                        VialState.RAW),
                "input must remain RAW when a required output slot is missing");
        helper.succeed();
    }

    /** Player-blood RAW vials are inspection-only; sequencer must refuse them. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void sequencer_refuses_player_blood_input(GameTestHelper helper) {
        ensureBootstrapped();
        VialContents playerBlood = VialContents.rawPlayerBlood(UUID.randomUUID(), "Alice",
                BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.PLAYER), List.of(), farFutureExpiry(helper));

        var be = new com.confect1on.dynetech.blockentity.GeneSequencerBlockEntity(
                new BlockPos(0, 0, 0), com.confect1on.dynetech.block.DTBlocks.GENE_SEQUENCER.get().defaultBlockState());
        be.inventory().setItem(com.confect1on.dynetech.blockentity.GeneSequencerBlockEntity.INPUT_SLOT,
                com.confect1on.dynetech.item.GeneVialItem.withContents(playerBlood));
        for (int i = 0; i < com.confect1on.dynetech.blockentity.GeneSequencerBlockEntity.OUTPUT_SLOT_COUNT; i++) {
            be.inventory().setItem(com.confect1on.dynetech.blockentity.GeneSequencerBlockEntity.OUTPUT_SLOT_START + i,
                    com.confect1on.dynetech.item.GeneVialItem.empty());
        }

        int threshold = com.confect1on.dynetech.config.DTConfig.SEQUENCER_TICKS.get();
        for (int i = 0; i <= threshold; i++) be.serverTick(helper.getLevel());

        helper.assertTrue(com.confect1on.dynetech.item.GeneVialItem.getContents(
                        be.inventory().getItem(com.confect1on.dynetech.blockentity.GeneSequencerBlockEntity.INPUT_SLOT))
                        .isPlayerBlood(),
                "player-blood RAW must not be consumed by the sequencer");
        helper.succeed();
    }

    /**
     * Splicer combining two ISOLATED vials leaves the merged result in slot B and empties slot A
     * (the container). If it did not empty slot A the machine would immediately re-run on
     * (merged, empty container) and drift the state.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void splicer_merges_two_isolated_into_slot_b(GameTestHelper helper) {
        ensureBootstrapped();
        PerkEntry vit = new PerkEntry(Perks.VITALITY.getId(), 0.6F, Optional.empty(), Optional.empty());
        PerkEntry brawn = new PerkEntry(Perks.BRAWN.getId(), 0.7F, Optional.empty(), Optional.empty());
        VialContents isoA = VialContents.isolated(vit, Optional.empty(), Optional.empty(), Optional.empty());
        VialContents isoB = VialContents.isolated(brawn, Optional.empty(), Optional.empty(), Optional.empty());

        var be = new com.confect1on.dynetech.blockentity.GeneSplicerBlockEntity(
                new BlockPos(0, 0, 0), com.confect1on.dynetech.block.DTBlocks.GENE_SPLICER.get().defaultBlockState());
        be.inventory().setItem(com.confect1on.dynetech.blockentity.GeneSplicerBlockEntity.SLOT_A,
                com.confect1on.dynetech.item.GeneVialItem.withContents(isoA));
        be.inventory().setItem(com.confect1on.dynetech.blockentity.GeneSplicerBlockEntity.SLOT_B,
                com.confect1on.dynetech.item.GeneVialItem.withContents(isoB));

        int threshold = com.confect1on.dynetech.config.DTConfig.SPLICER_TICKS.get();
        for (int i = 0; i <= threshold; i++) be.serverTick(helper.getLevel());

        VialContents outA = com.confect1on.dynetech.item.GeneVialItem.getContents(
                be.inventory().getItem(com.confect1on.dynetech.blockentity.GeneSplicerBlockEntity.SLOT_A));
        VialContents outB = com.confect1on.dynetech.item.GeneVialItem.getContents(
                be.inventory().getItem(com.confect1on.dynetech.blockentity.GeneSplicerBlockEntity.SLOT_B));
        helper.assertTrue(outA.state() == VialState.EMPTY, "slot A must empty out after merge");
        helper.assertTrue(outB.state() == VialState.ISOLATED, "slot B must hold the merged ISOLATED");
        helper.assertTrue(outB.perks().size() == 2, "merged vial must carry both perks");
        helper.succeed();
    }

    /** Splicing RAW + ISOLATED produces a SERUM whose donor identity comes from the RAW. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void splicer_raw_plus_isolated_produces_serum(GameTestHelper helper) {
        ensureBootstrapped();
        ResourceLocation cowType = ResourceLocation.withDefaultNamespace("cow");
        UUID rawDonor = UUID.randomUUID();
        VialContents raw = VialContents.raw(List.of(), rawDonor, Optional.empty(), cowType);
        VialContents iso = VialContents.isolated(
                new PerkEntry(Perks.VITALITY.getId(), 0.8F, Optional.empty(), Optional.empty()),
                Optional.empty(), Optional.empty(), Optional.empty());

        var be = new com.confect1on.dynetech.blockentity.GeneSplicerBlockEntity(
                new BlockPos(0, 0, 0), com.confect1on.dynetech.block.DTBlocks.GENE_SPLICER.get().defaultBlockState());
        be.inventory().setItem(com.confect1on.dynetech.blockentity.GeneSplicerBlockEntity.SLOT_A,
                com.confect1on.dynetech.item.GeneVialItem.withContents(raw));
        be.inventory().setItem(com.confect1on.dynetech.blockentity.GeneSplicerBlockEntity.SLOT_B,
                com.confect1on.dynetech.item.GeneVialItem.withContents(iso));

        int threshold = com.confect1on.dynetech.config.DTConfig.SPLICER_TICKS.get();
        for (int i = 0; i <= threshold; i++) be.serverTick(helper.getLevel());

        VialContents outB = com.confect1on.dynetech.item.GeneVialItem.getContents(
                be.inventory().getItem(com.confect1on.dynetech.blockentity.GeneSplicerBlockEntity.SLOT_B));
        helper.assertTrue(outB.state() == VialState.SERUM, "slot B must hold a SERUM");
        helper.assertTrue(outB.donor().equals(Optional.of(rawDonor)),
                "SERUM donor must carry over from the RAW input");
        helper.assertTrue(outB.donorType().equals(Optional.of(cowType)),
                "SERUM donor type must carry over from the RAW input");
        helper.succeed();
    }

    // ============================================================================
    //  Injection gun state transitions
    // ============================================================================

    /**
     * fireAtSelf is the self-inject entrypoint used by the client-side "shift + attack empty"
     * gesture. With a loaded EMPTY vial the gun samples the player (player blood) and stores it
     * as RAW back on the stack.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void injection_gun_self_sample_produces_player_blood(GameTestHelper helper) {
        ensureBootstrapped();
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        ItemStack gun = new ItemStack(com.confect1on.dynetech.item.DTItems.INJECTION_GUN.get());
        // Load with EMPTY (the physical container that gets refilled by sampling).
        gun.set(com.confect1on.dynetech.component.DTDataComponents.LOADED_VIAL.get(), VialContents.EMPTY);

        boolean fired = com.confect1on.dynetech.item.InjectionGunItem.fireAtSelf(player, gun);
        helper.assertTrue(fired, "fireAtSelf must return true for a loaded EMPTY vial");

        VialContents loaded = com.confect1on.dynetech.item.InjectionGunItem.getLoaded(gun);
        helper.assertTrue(loaded.state() == VialState.RAW,
                "after self-sample the loaded vial should be RAW, got " + loaded.state());
        helper.assertTrue(loaded.isPlayerBlood(),
                "self-sample must produce player-blood (locked snapshot)");
        helper.assertTrue(loaded.donor().equals(Optional.of(player.getUUID())),
                "player blood donor must be the sampled player's UUID");
        player.discard();
        helper.succeed();
    }

    /**
     * Firing a loaded SERUM at self applies the perks and empties the gun so the container is
     * ready to be reloaded or refilled by another sample.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void injection_gun_self_inject_consumes_serum(GameTestHelper helper) {
        ensureBootstrapped();
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        ResourceLocation playerType = BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.PLAYER);
        VialContents serum = VialContents.serum(
                List.of(new PerkEntry(Perks.REACHMINER.getId(), 1.0F, Optional.empty(), Optional.empty())),
                Optional.of(player.getUUID()), Optional.of("Alice"), Optional.of(playerType));

        ItemStack gun = new ItemStack(com.confect1on.dynetech.item.DTItems.INJECTION_GUN.get());
        gun.set(com.confect1on.dynetech.component.DTDataComponents.LOADED_VIAL.get(), serum);

        boolean fired = com.confect1on.dynetech.item.InjectionGunItem.fireAtSelf(player, gun);
        helper.assertTrue(fired, "fireAtSelf must return true for a loaded SERUM");

        EquippedPerks eq = player.getData(DTAttachments.EQUIPPED_PERKS.get());
        helper.assertTrue(eq.has(Perks.REACHMINER.getId()),
                "reachminer should be equipped after self-inject");
        VialContents afterFire = com.confect1on.dynetech.item.InjectionGunItem.getLoaded(gun);
        helper.assertTrue(afterFire.state() == VialState.EMPTY,
                "gun should hold an EMPTY container after firing a SERUM");
        player.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Cryo Preservator - E2E through a real placed block
    // ============================================================================

    private static final BlockPos CRYO_POS = new BlockPos(1, 1, 1);

    /** Places the real block, returns its live block entity from the world. */
    private static com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity placeCryo(GameTestHelper helper) {
        helper.setBlock(CRYO_POS, com.confect1on.dynetech.block.DTBlocks.CRYO_PRESERVATOR.get());
        var be = helper.getLevel().getBlockEntity(helper.absolutePos(CRYO_POS));
        helper.assertTrue(be instanceof com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity,
                "expected a CryoPreservatorBlockEntity at " + CRYO_POS + ", got " + be);
        return (com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity) be;
    }

    private static void tickCryo(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity be,
                                 GameTestHelper helper, int times) {
        for (int i = 0; i < times; i++) be.serverTick(helper.getLevel());
    }

    /** Shared helper: build a player-blood vial with a fresh-enough expiry for tests. */
    private static VialContents playerBloodWith(GameTestHelper helper, UUID donor, String name, List<PerkEntry> snapshot) {
        ResourceLocation playerType = BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.PLAYER);
        return VialContents.rawPlayerBlood(donor, name, playerType, snapshot, farFutureExpiry(helper));
    }

    /** Timestamp that won't elapse before any test ends; use whenever a test doesn't care about decay. */
    private static long farFutureExpiry(GameTestHelper helper) {
        return helper.getLevel().getGameTime() + 24_000L;
    }

    /**
     * Ice dropped into the input slot must stay visible to the player when there's no vial to
     * preserve. Nothing is silently teleported into an invisible buffer.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void cryo_ice_stays_in_slot_without_vial(GameTestHelper helper) {
        ensureBootstrapped();
        var be = placeCryo(helper);
        be.inventory().setItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_ICE_A,
                new ItemStack(net.minecraft.world.item.Items.ICE, 32));

        tickCryo(be, helper, 5);

        helper.assertTrue(be.iceCount() == 32,
                "ice must remain in the input slot with no vial to preserve, got " + be.iceCount());
        helper.succeed();
    }

    /** All four ice slots together must hold the full 256-item reservoir capacity. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void cryo_ice_grid_holds_up_to_four_stacks(GameTestHelper helper) {
        ensureBootstrapped();
        var be = placeCryo(helper);
        for (int slot : com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.ICE_SLOTS) {
            be.inventory().setItem(slot, new ItemStack(net.minecraft.world.item.Items.ICE, 64));
        }

        tickCryo(be, helper, 2);

        helper.assertTrue(be.iceCount() == com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.MAX_ICE_STORAGE,
                "four filled ice slots must total the reservoir capacity, got " + be.iceCount());
        helper.succeed();
    }

    /**
     * With a valid vial and one ice in the reservoir, a full CRYO_ICE_INTERVAL_TICKS of
     * preservation consumes exactly one ice. This is the wall-clock burn rate.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void cryo_burns_one_ice_per_interval(GameTestHelper helper) {
        ensureBootstrapped();
        var be = placeCryo(helper);
        VialContents template = playerBloodWith(helper, UUID.randomUUID(), "Alice",
                List.of(new PerkEntry(Perks.BRAWN.getId(), 1.0F, Optional.empty(), Optional.empty())));
        be.inventory().setItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_BLOOD,
                com.confect1on.dynetech.item.GeneVialItem.withContents(template));
        be.inventory().setItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_ICE_A,
                new ItemStack(net.minecraft.world.item.Items.ICE, 3));

        // The priming tick pulls all 3 ice into the reservoir AND starts burning the first
        // one, so total ice equivalents = iceStored (2) + one in progress = 3.
        int interval = com.confect1on.dynetech.config.DTConfig.CRYO_ICE_INTERVAL_TICKS.get();
        tickCryo(be, helper, interval);

        helper.assertTrue(be.iceCount() == 2,
                "one ice should have been consumed after INTERVAL ticks total, got " + be.iceCount());
        helper.assertTrue(be.iceProgress() == 0,
                "burn should have just cleanly finished, got iceProgress=" + be.iceProgress());
        helper.succeed();
    }

    /**
     * Every server tick advances the vial's expiry by one tick while preservation is active.
     * Over N ticks the delta should be exactly N.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void cryo_freezes_vial_expiry_while_preserving(GameTestHelper helper) {
        ensureBootstrapped();
        var be = placeCryo(helper);
        VialContents template = playerBloodWith(helper, UUID.randomUUID(), "Alice",
                List.of(new PerkEntry(Perks.VITALITY.getId(), 1.0F, Optional.empty(), Optional.empty())));
        long startExpiry = template.expiresAtGameTime().orElseThrow();

        be.inventory().setItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_BLOOD,
                com.confect1on.dynetech.item.GeneVialItem.withContents(template));
        be.inventory().setItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_ICE_A,
                new ItemStack(net.minecraft.world.item.Items.ICE, 4));

        int ticks = 150;
        tickCryo(be, helper, ticks);

        VialContents after = com.confect1on.dynetech.item.GeneVialItem.getContents(
                be.inventory().getItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_BLOOD));
        long endExpiry = after.expiresAtGameTime().orElseThrow();
        helper.assertTrue(endExpiry - startExpiry == ticks,
                "expiry must advance one tick per preservation tick, delta = " + (endExpiry - startExpiry));
        helper.succeed();
    }

    /**
     * If a valid vial and full reservoir are already in place and the game clock jumps forward
     * (simulating a chunk unload), the very next tick must catch up in a single call: it should
     * consume the right amount of ice and push the vial expiry forward by the offline delta.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void cryo_catches_up_after_chunk_reload(GameTestHelper helper) {
        ensureBootstrapped();
        var be = placeCryo(helper);
        VialContents template = playerBloodWith(helper, UUID.randomUUID(), "Alice",
                List.of(new PerkEntry(Perks.VITALITY.getId(), 1.0F, Optional.empty(), Optional.empty())));
        long startExpiry = template.expiresAtGameTime().orElseThrow();

        be.inventory().setItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_BLOOD,
                com.confect1on.dynetech.item.GeneVialItem.withContents(template));
        be.inventory().setItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_ICE_A,
                new ItemStack(net.minecraft.world.item.Items.ICE, 3));
        // Priming: ice moves into the reservoir and the first ice starts burning (iceStored=2,
        // iceProgress=1). This also sets lastTickGameTime so the next tick can compute a delta.
        tickCryo(be, helper, 1);
        helper.assertTrue(be.iceCount() == 2 && be.iceProgress() == 1,
                "priming tick should load ice and start the first burn, got iceStored="
                        + be.iceCount() + " iceProgress=" + be.iceProgress());

        int interval = com.confect1on.dynetech.config.DTConfig.CRYO_ICE_INTERVAL_TICKS.get();
        // Simulate an unloaded chunk: the world's game time keeps advancing while the BE
        // doesn't tick. Fake this by round-tripping the BE through save + load with a synthetic
        // gap in the persisted last-tick timestamp.
        long offlineTicks = (long) interval + interval / 2;
        var registries = helper.getLevel().registryAccess();
        net.minecraft.nbt.CompoundTag saved = be.getUpdateTag(registries);
        saved.putLong("LastTick", helper.getLevel().getGameTime() - offlineTicks);
        be.loadWithComponents(saved, registries);

        tickCryo(be, helper, 1);

        helper.assertTrue(be.iceCount() == 1,
                "one ice should be fully consumed by the catch-up, got iceStored=" + be.iceCount());
        helper.assertTrue(be.iceProgress() >= interval / 2 - 2 && be.iceProgress() <= interval / 2 + 2,
                "second ice should be half-burnt, got iceProgress=" + be.iceProgress());

        VialContents after = com.confect1on.dynetech.item.GeneVialItem.getContents(
                be.inventory().getItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_BLOOD));
        long endExpiry = after.expiresAtGameTime().orElseThrow();
        long delta = endExpiry - startExpiry;
        helper.assertTrue(delta >= offlineTicks && delta <= offlineTicks + 2,
                "vial expiry should have caught up by the offline delta, got delta=" + delta);
        helper.succeed();
    }

    /**
     * Reservoir exhausted: preservation stops. The vial's expiry no longer advances after the
     * last ice is spent.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void cryo_stops_preserving_when_ice_runs_out(GameTestHelper helper) {
        ensureBootstrapped();
        var be = placeCryo(helper);
        VialContents template = playerBloodWith(helper, UUID.randomUUID(), "Alice",
                List.of(new PerkEntry(Perks.VITALITY.getId(), 1.0F, Optional.empty(), Optional.empty())));
        long startExpiry = template.expiresAtGameTime().orElseThrow();

        be.inventory().setItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_BLOOD,
                com.confect1on.dynetech.item.GeneVialItem.withContents(template));
        be.inventory().setItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_ICE_A,
                new ItemStack(net.minecraft.world.item.Items.ICE, 1));

        int interval = com.confect1on.dynetech.config.DTConfig.CRYO_ICE_INTERVAL_TICKS.get();
        tickCryo(be, helper, 1);
        tickCryo(be, helper, interval);
        helper.assertTrue(be.iceCount() == 0, "reservoir should be empty after burning the sole ice");

        VialContents afterBurn = com.confect1on.dynetech.item.GeneVialItem.getContents(
                be.inventory().getItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_BLOOD));
        long midExpiry = afterBurn.expiresAtGameTime().orElseThrow();
        long preserved = midExpiry - startExpiry;
        helper.assertTrue(preserved == interval,
                "expiry should have advanced by exactly the ice's burn duration, got " + preserved);

        tickCryo(be, helper, 5);
        VialContents afterIdle = com.confect1on.dynetech.item.GeneVialItem.getContents(
                be.inventory().getItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_BLOOD));
        helper.assertTrue(afterIdle.expiresAtGameTime().orElseThrow() == midExpiry,
                "expiry must not advance once the reservoir is dry");
        helper.succeed();
    }

    /**
     * An expired player-blood vial must not be accepted as a template. Even with a full
     * reservoir, the machine won't burn ice for a dead sample.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void cryo_refuses_expired_template(GameTestHelper helper) {
        ensureBootstrapped();
        var be = placeCryo(helper);
        ResourceLocation playerType = BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.PLAYER);
        long alreadyPast = helper.getLevel().getGameTime() - 1L;
        VialContents expired = VialContents.rawPlayerBlood(UUID.randomUUID(), "Alice", playerType,
                List.of(new PerkEntry(Perks.VITALITY.getId(), 1.0F, Optional.empty(), Optional.empty())),
                alreadyPast);

        be.inventory().setItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_BLOOD,
                com.confect1on.dynetech.item.GeneVialItem.withContents(expired));
        be.inventory().setItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_ICE_A,
                new ItemStack(net.minecraft.world.item.Items.ICE, 4));

        tickCryo(be, helper, 200);
        helper.assertTrue(be.iceCount() == 4, "reservoir should hold all 4 ice, none burnt on an expired vial");
        helper.assertTrue(be.iceProgress() == 0, "no ice may be in progress against an expired vial");
        helper.succeed();
    }

    /**
     * Direct injection: right-clicking a fresh player-blood RAW vial applies the snapshot perks
     * to the donor and empties the vial. This is the "recreate my gene combo" path.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void player_blood_direct_inject_applies_perks_to_donor(GameTestHelper helper) {
        ensureBootstrapped();
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        ResourceLocation playerType = BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.PLAYER);
        PerkEntry reach = new PerkEntry(Perks.REACHMINER.getId(), 1.0F, Optional.empty(), Optional.empty());
        VialContents blood = VialContents.rawPlayerBlood(player.getUUID(), player.getName().getString(),
                playerType, List.of(reach), helper.getLevel().getGameTime() + 6000L);

        ItemStack held = com.confect1on.dynetech.item.GeneVialItem.withContents(blood);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);

        com.confect1on.dynetech.item.DTItems.GENE_VIAL.get().use(
                helper.getLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND);

        EquippedPerks eq = player.getData(DTAttachments.EQUIPPED_PERKS.get());
        helper.assertTrue(eq.has(Perks.REACHMINER.getId()),
                "donor should have the snapshot perk equipped after right-clicking their own blood vial");
        VialContents afterUse = com.confect1on.dynetech.item.GeneVialItem.getContents(
                player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND));
        helper.assertTrue(afterUse.state() == VialState.EMPTY,
                "vial should empty out after a successful self-inject, got " + afterUse.state());
        player.discard();
        helper.succeed();
    }

    /**
     * Thief scenario for the direct-injection path: another player right-clicks a blood vial
     * keyed to someone else. They must NOT gain the perk and must receive the punishment
     * cocktail.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void player_blood_direct_inject_punishes_foreigner(GameTestHelper helper) {
        ensureBootstrapped();
        var thief = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        ResourceLocation playerType = BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.PLAYER);
        UUID donorId = UUID.randomUUID();
        helper.assertTrue(!donorId.equals(thief.getUUID()), "thief UUID must differ from donor UUID");

        PerkEntry vit = new PerkEntry(Perks.VITALITY.getId(), 1.0F, Optional.empty(), Optional.empty());
        VialContents blood = VialContents.rawPlayerBlood(donorId, "Alice",
                playerType, List.of(vit), helper.getLevel().getGameTime() + 6000L);
        ItemStack held = com.confect1on.dynetech.item.GeneVialItem.withContents(blood);
        thief.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);

        com.confect1on.dynetech.item.DTItems.GENE_VIAL.get().use(
                helper.getLevel(), thief, net.minecraft.world.InteractionHand.MAIN_HAND);

        EquippedPerks eq = thief.getData(DTAttachments.EQUIPPED_PERKS.get());
        helper.assertTrue(!eq.has(Perks.VITALITY.getId()), "thief must NOT gain the donor's perk");
        helper.assertTrue(thief.hasEffect(MobEffects.WITHER),
                "thief must receive Wither from the wrong-donor punishment");
        helper.assertTrue(thief.hasEffect(MobEffects.BLINDNESS),
                "thief must receive Blindness from the wrong-donor punishment");
        helper.assertTrue(thief.hasEffect(MobEffects.CONFUSION),
                "thief must receive Nausea from the wrong-donor punishment");
        thief.discard();
        helper.succeed();
    }

    /**
     * Expired player-blood vials are refused for direct injection: nothing equips, no
     * punishment fires, the vial stays in-hand for the player to discard.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void player_blood_expired_refuses_direct_inject(GameTestHelper helper) {
        ensureBootstrapped();
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        ResourceLocation playerType = BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.PLAYER);
        PerkEntry reach = new PerkEntry(Perks.REACHMINER.getId(), 1.0F, Optional.empty(), Optional.empty());
        VialContents blood = VialContents.rawPlayerBlood(player.getUUID(), player.getName().getString(),
                playerType, List.of(reach), helper.getLevel().getGameTime() - 1L);
        ItemStack held = com.confect1on.dynetech.item.GeneVialItem.withContents(blood);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);

        com.confect1on.dynetech.item.DTItems.GENE_VIAL.get().use(
                helper.getLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND);

        EquippedPerks eq = player.getData(DTAttachments.EQUIPPED_PERKS.get());
        helper.assertTrue(!eq.has(Perks.REACHMINER.getId()),
                "expired sample must not equip anything on the donor");
        helper.assertTrue(!player.hasEffect(MobEffects.WITHER),
                "expired sample must not fire the punishment cocktail on the donor either");
        player.discard();
        helper.succeed();
    }

    /**
     * Regression: taking the blood vial out of the machine must not touch the ice slots'
     * client-visible state. Simulates the exact sequence that caused visible "snap": place the
     * BE, load blood + ice, tick to flip preservingLastTick=true, then remove the blood and
     * capture the update tag. That tag must not carry an inventory payload that would trigger
     * a client-side clear-and-refill on the ice slots.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void cryo_state_update_tag_omits_inventory(GameTestHelper helper) {
        ensureBootstrapped();
        var be = placeCryo(helper);
        VialContents template = playerBloodWith(helper, UUID.randomUUID(), "Alice",
                List.of(new PerkEntry(Perks.VITALITY.getId(), 1.0F, Optional.empty(), Optional.empty())));
        be.inventory().setItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_BLOOD,
                com.confect1on.dynetech.item.GeneVialItem.withContents(template));
        be.inventory().setItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_ICE_A,
                new ItemStack(net.minecraft.world.item.Items.ICE, 32));

        tickCryo(be, helper, 2);
        net.minecraft.nbt.CompoundTag update = be.getUpdateTag(helper.getLevel().registryAccess());
        helper.assertTrue(!update.contains("Inv"),
                "state update tag must not include Inv (would cause client-side slot flash)");
        helper.assertTrue(update.contains("IceProgress"),
                "state update tag must still carry IceProgress for burn-meter rendering");
        helper.succeed();
    }

    /**
     * Regression: taking the blood vial out and putting it back must not delete or shuffle ice
     * slot contents. Exercises the swap sequence directly on the container.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void cryo_ice_stays_put_across_blood_swap(GameTestHelper helper) {
        ensureBootstrapped();
        var be = placeCryo(helper);
        VialContents template = playerBloodWith(helper, UUID.randomUUID(), "Alice",
                List.of(new PerkEntry(Perks.VITALITY.getId(), 1.0F, Optional.empty(), Optional.empty())));
        ItemStack bloodStack = com.confect1on.dynetech.item.GeneVialItem.withContents(template);

        be.inventory().setItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_BLOOD, bloodStack);
        be.inventory().setItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_ICE_A,
                new ItemStack(net.minecraft.world.item.Items.ICE, 32));
        be.inventory().setItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_ICE_C,
                new ItemStack(net.minecraft.world.item.Items.PACKED_ICE, 16));
        tickCryo(be, helper, 2);

        // Pull the blood, tick a few times (preservingLastTick should flip), reinsert.
        ItemStack removed = be.inventory().removeItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_BLOOD, 1);
        tickCryo(be, helper, 5);
        be.inventory().setItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_BLOOD, removed);
        tickCryo(be, helper, 2);

        ItemStack iceA = be.inventory().getItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_ICE_A);
        ItemStack iceC = be.inventory().getItem(com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity.SLOT_ICE_C);
        helper.assertTrue(iceA.getItem() == net.minecraft.world.item.Items.ICE && iceA.getCount() >= 31,
                "SLOT_ICE_A ice must remain (allow for one burn start), got " + iceA);
        helper.assertTrue(iceC.getItem() == net.minecraft.world.item.Items.PACKED_ICE && iceC.getCount() == 16,
                "SLOT_ICE_C packed ice must be untouched, got " + iceC);
        helper.succeed();
    }

    /**
     * Regression: the injection gun must accept a fresh player-blood RAW vial as ammo, not
     * refuse to load and drop it on the floor.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void gun_loads_player_blood_raw(GameTestHelper helper) {
        ensureBootstrapped();
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        ResourceLocation playerType = BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.PLAYER);
        VialContents blood = VialContents.rawPlayerBlood(player.getUUID(), player.getName().getString(),
                playerType,
                List.of(new PerkEntry(Perks.REACHMINER.getId(), 1.0F, Optional.empty(), Optional.empty())),
                helper.getLevel().getGameTime() + 6000L);

        ItemStack gun = new ItemStack(com.confect1on.dynetech.item.DTItems.INJECTION_GUN.get());
        gun.set(com.confect1on.dynetech.component.DTDataComponents.LOADED_VIAL.get(),
                VialContents.EMPTY);
        // Fire the gun with the blood loaded as ammo (bypasses right-click into off-hand).
        gun.set(com.confect1on.dynetech.component.DTDataComponents.LOADED_VIAL.get(), blood);
        boolean fired = com.confect1on.dynetech.item.InjectionGunItem.fireAtSelf(player, gun);
        helper.assertTrue(fired, "gun must fire with a loaded player-blood RAW vial");

        EquippedPerks eq = player.getData(DTAttachments.EQUIPPED_PERKS.get());
        helper.assertTrue(eq.has(Perks.REACHMINER.getId()),
                "donor should have the snapshot perk equipped after firing their own blood");
        player.discard();
        helper.succeed();
    }

    /**
     * A vial drawn N ticks ago with a TTL of N must read as expired. Sanity check on the
     * expiry field independent of the machine.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void player_blood_reports_expired_after_ttl(GameTestHelper helper) {
        ensureBootstrapped();
        long now = helper.getLevel().getGameTime();
        ResourceLocation playerType = BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.PLAYER);
        VialContents fresh = VialContents.rawPlayerBlood(UUID.randomUUID(), "Alice", playerType,
                List.of(), now + 10L);
        helper.assertTrue(!fresh.isExpired(now), "fresh vial should not report expired at draw time");
        helper.assertTrue(!fresh.isExpired(now + 9L), "vial must remain fresh right up to the expiry tick");
        helper.assertTrue(fresh.isExpired(now + 10L), "vial must report expired at the expiry tick");
        helper.assertTrue(fresh.isExpired(now + 100L), "vial must remain expired past the expiry tick");
        helper.succeed();
    }

    //  Remaining attribute / effect / immunity perks

    /**
     * Writes an entry straight into the attachment and returns it. For marker perks whose logic
     * lives in {@code PerkEvents}: their onEquip is a no-op, so the attachment write is the
     * whole equip.
     */
    private static PerkEntry equipMarker(LivingEntity entity, ResourceLocation perkId, float quality) {
        PerkEntry entry = new PerkEntry(perkId, quality, Optional.empty(), Optional.empty());
        EquippedPerks eq = entity.getData(DTAttachments.EQUIPPED_PERKS.get());
        entity.setData(DTAttachments.EQUIPPED_PERKS.get(), eq.add(entry));
        return entry;
    }

    /**
     * Jumps the level clock forward to the next noon when it is not currently day. Forward-only
     * so any other test measuring elapsed day time never sees the clock run backwards, and the
     * sky brightness cache is refreshed immediately so {@code isDay} agrees within this tick.
     */
    private static void ensureDaytime(GameTestHelper helper) {
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        if (level.isDay()) return;
        long time = level.getDayTime();
        long target = time - (time % 24_000L) + 6_000L;
        if (target <= time) target += 24_000L;
        level.setDayTime(target);
        level.updateSkyBrightness();
    }

    /** Shared shape for ImmunityPerk tests: equip strips an existing effect, tick strips a re-applied one. */
    private static void assertImmunityStrips(GameTestHelper helper, Perk perk,
                                             Holder<net.minecraft.world.effect.MobEffect> guarded) {
        Villager v = spawnVillager(helper);
        v.addEffect(new net.minecraft.world.effect.MobEffectInstance(guarded, 600));
        helper.assertTrue(v.hasEffect(guarded), "precondition: effect must land before the immunity equips");

        PerkEntry entry = new PerkEntry(perk.id(), 1.0F, Optional.empty(), Optional.empty());
        perk.onEquip(v, entry);
        helper.assertTrue(!v.hasEffect(guarded), perk.id() + " must strip " + guarded.getRegisteredName() + " on equip");

        v.addEffect(new net.minecraft.world.effect.MobEffectInstance(guarded, 600));
        perk.tick(v, entry);
        helper.assertTrue(!v.hasEffect(guarded), perk.id() + " must strip a re-applied effect on tick");
        v.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_climber_attribute_cycle(GameTestHelper helper) {
        assertAttributePerkCycles(helper, Perks.CLIMBER.get(), Attributes.STEP_HEIGHT, spawnZombie(helper));
    }

    /** Hoglin because it is one of the few vanilla mobs whose attribute map carries ATTACK_KNOCKBACK. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_ram_attribute_cycle(GameTestHelper helper) {
        ensureBootstrapped();
        LivingEntity hoglin = helper.spawn(EntityType.HOGLIN, SUBJECT_POS);
        assertAttributePerkCycles(helper, Perks.RAM.get(), Attributes.ATTACK_KNOCKBACK, hoglin);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_swift_strike_attribute_cycle(GameTestHelper helper) {
        assertAttributePerkCyclesOnMockPlayer(helper, Perks.SWIFT_STRIKE.get(), Attributes.ATTACK_SPEED);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_composure_applies(GameTestHelper helper) {
        assertMobEffectPerkCycles(helper, Perks.COMPOSURE.get(), MobEffects.DAMAGE_RESISTANCE, spawnVillager(helper));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_slow_fall_applies(GameTestHelper helper) {
        assertMobEffectPerkCycles(helper, Perks.SLOW_FALL.get(), MobEffects.SLOW_FALLING, spawnVillager(helper));
    }

    /**
     * Chameleon layers the dynetech:camouflage marker on top of vanilla invisibility - the marker
     * is what the render mixin keys on to draw the host translucent instead of hidden. The plain
     * invisibility half is covered by perk_invisibility_applies; this checks the marker half.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void perk_invisibility_applies_camouflage_marker(GameTestHelper helper) {
        ensureBootstrapped();
        Villager v = spawnVillager(helper);
        PerkEntry entry = new PerkEntry(Perks.INVISIBILITY.getId(), 1.0F, Optional.empty(), Optional.empty());
        Perks.INVISIBILITY.get().onEquip(v, entry);
        helper.assertTrue(v.hasEffect(com.confect1on.dynetech.effect.DTEffects.CAMOUFLAGE),
                "camouflage marker effect must accompany vanilla invisibility on equip");
        Perks.INVISIBILITY.get().onUnequip(v, entry);
        helper.assertTrue(!v.hasEffect(com.confect1on.dynetech.effect.DTEffects.CAMOUFLAGE),
                "camouflage marker must be gone after unequip");
        v.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void immunity_poison_strips_effect(GameTestHelper helper) {
        ensureBootstrapped();
        assertImmunityStrips(helper, Perks.POISON_IMMUNE.get(), MobEffects.POISON);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void immunity_wither_strips_effect(GameTestHelper helper) {
        ensureBootstrapped();
        assertImmunityStrips(helper, Perks.WITHER_IMMUNE.get(), MobEffects.WITHER);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void immunity_levitation_strips_effect(GameTestHelper helper) {
        ensureBootstrapped();
        assertImmunityStrips(helper, Perks.LEVITATION_IMMUNE.get(), MobEffects.LEVITATION);
    }

    // ============================================================================
    //  Absorption + damage timer
    // ============================================================================

    /**
     * A never-damaged host (LAST_DAMAGED_TICK at its -1 default) at full health gets the shield
     * on the first observation tick. Amplifier comes from quality: 1.0 clamps to the max (I),
     * 0.4 floors to 0.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void absorption_grants_when_unhurt(GameTestHelper helper) {
        ensureBootstrapped();
        var strong = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        PerkEntry pristine = new PerkEntry(Perks.ABSORPTION.getId(), 1.0F, Optional.empty(), Optional.empty());
        Perks.ABSORPTION.get().tick(strong, pristine);
        helper.assertTrue(strong.hasEffect(MobEffects.ABSORPTION),
                "unhurt full-health host must receive absorption on the observation tick");
        helper.assertTrue(strong.getEffect(MobEffects.ABSORPTION).getAmplifier() == 1,
                "quality 1.0 must clamp to the max amplifier, got "
                        + strong.getEffect(MobEffects.ABSORPTION).getAmplifier());

        var weak = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        PerkEntry viable = new PerkEntry(Perks.ABSORPTION.getId(), 0.4F, Optional.empty(), Optional.empty());
        Perks.ABSORPTION.get().tick(weak, viable);
        helper.assertTrue(weak.hasEffect(MobEffects.ABSORPTION)
                        && weak.getEffect(MobEffects.ABSORPTION).getAmplifier() == 0,
                "quality 0.4 must floor to amplifier 0");
        strong.discard();
        weak.discard();
        helper.succeed();
    }

    /** Within REGEN_DELAY_TICKS of the last hit the shield must not come back; after it, it must. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void absorption_waits_out_regen_cooldown(GameTestHelper helper) {
        ensureBootstrapped();
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        player.setData(DTAttachments.LAST_DAMAGED_TICK.get(), 0L);
        PerkEntry entry = new PerkEntry(Perks.ABSORPTION.getId(), 1.0F, Optional.empty(), Optional.empty());

        Perks.ABSORPTION.get().tick(player, entry);
        helper.assertTrue(!player.hasEffect(MobEffects.ABSORPTION),
                "absorption must not regenerate inside the 600-tick cooldown");

        // 600 ticks after the stamp (and on the 20-tick observation boundary) it regenerates.
        player.tickCount = 600;
        Perks.ABSORPTION.get().tick(player, entry);
        helper.assertTrue(player.hasEffect(MobEffects.ABSORPTION),
                "absorption must regenerate once the cooldown has elapsed");
        player.discard();
        helper.succeed();
    }

    /** Real damage through the event pipeline must stamp LAST_DAMAGED_TICK for the regen timer. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void damage_stamps_last_damaged_tick(GameTestHelper helper) {
        ensureBootstrapped();
        Villager v = spawnVillager(helper);
        helper.assertTrue(v.getData(DTAttachments.LAST_DAMAGED_TICK.get()) == -1L,
                "fresh entity must carry the -1 sentinel before any damage");

        v.hurt(v.damageSources().generic(), 2.0F);
        helper.assertTrue(v.getData(DTAttachments.LAST_DAMAGED_TICK.get()) == (long) v.tickCount,
                "a real hit must stamp the victim's current tickCount");
        v.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Photosynthesis
    // ============================================================================

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void photosynthesis_heals_in_sunlight(GameTestHelper helper) {
        ensureBootstrapped();
        ensureDaytime(helper);
        Villager v = spawnVillager(helper);
        helper.assertTrue(helper.getLevel().canSeeSky(v.blockPosition()),
                "test environment must have open sky above the subject");
        v.setHealth(5.0F);
        PerkEntry entry = new PerkEntry(Perks.PHOTOSYNTHESIS.getId(), 1.0F, Optional.empty(), Optional.empty());
        Perks.PHOTOSYNTHESIS.get().tick(v, entry);
        helper.assertTrue(Math.abs(v.getHealth() - 6.0F) < 0.01F,
                "sunlit host must heal quality * 1.0 hp, got " + v.getHealth());
        v.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void photosynthesis_requires_open_sky(GameTestHelper helper) {
        ensureBootstrapped();
        ensureDaytime(helper);
        Villager v = spawnVillager(helper);
        v.setNoAi(true);
        // Roof one block above head level blocks the sky column. canSeeSky reads sky light,
        // which the light engine only recomputes a tick or two after the block change, so
        // wait for the shadow to actually land before ticking the perk.
        helper.setBlock(new BlockPos(2, 4, 2), net.minecraft.world.level.block.Blocks.STONE);
        v.setHealth(5.0F);
        PerkEntry entry = new PerkEntry(Perks.PHOTOSYNTHESIS.getId(), 1.0F, Optional.empty(), Optional.empty());
        helper.succeedWhen(() -> {
            helper.assertTrue(!helper.getLevel().canSeeSky(v.blockPosition()),
                    "waiting for sky light to update under the roof");
            v.tickCount = 40; // keep the perk's own interval gate open
            Perks.PHOTOSYNTHESIS.get().tick(v, entry);
            helper.assertTrue(Math.abs(v.getHealth() - 5.0F) < 0.01F,
                    "covered host must not photosynthesize, got " + v.getHealth());
            v.discard();
        });
    }

    /** Player hosts additionally need 18+ food; mobs have no food data and skip the gate. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void photosynthesis_requires_player_hunger(GameTestHelper helper) {
        ensureBootstrapped();
        ensureDaytime(helper);
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        net.minecraft.world.phys.Vec3 center = helper.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 1.0, 2.5));
        player.moveTo(center.x, center.y, center.z);
        helper.assertTrue(helper.getLevel().canSeeSky(player.blockPosition()),
                "test environment must have open sky above the subject");
        player.setHealth(10.0F);
        PerkEntry entry = new PerkEntry(Perks.PHOTOSYNTHESIS.getId(), 1.0F, Optional.empty(), Optional.empty());

        player.getFoodData().setFoodLevel(10);
        Perks.PHOTOSYNTHESIS.get().tick(player, entry);
        helper.assertTrue(Math.abs(player.getHealth() - 10.0F) < 0.01F,
                "hungry player must not photosynthesize, got " + player.getHealth());

        player.getFoodData().setFoodLevel(20);
        Perks.PHOTOSYNTHESIS.get().tick(player, entry);
        helper.assertTrue(Math.abs(player.getHealth() - 11.0F) < 0.01F,
                "well-fed player must heal 1.0 hp, got " + player.getHealth());
        player.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Behavior defects
    // ============================================================================

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void photophobia_burns_in_sunlight(GameTestHelper helper) {
        ensureBootstrapped();
        ensureDaytime(helper);
        Villager v = spawnVillager(helper);
        helper.assertTrue(helper.getLevel().canSeeSky(v.blockPosition()),
                "test environment must have open sky above the subject");
        PerkEntry entry = new PerkEntry(Perks.PHOTOPHOBIA_DEFECT.getId(), 1.0F, Optional.empty(), Optional.empty());
        Perks.PHOTOPHOBIA_DEFECT.get().tick(v, entry);
        helper.assertTrue(Math.abs(v.getHealth() - 19.0F) < 0.01F,
                "sunlit photophobe must take 1.0 fire damage, got " + v.getHealth());
        v.discard();
        helper.succeed();
    }

    /** Carved pumpkin is the deliberate escape hatch; a denatured entry never expresses at all. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void photophobia_blocked_by_pumpkin_and_denature(GameTestHelper helper) {
        ensureBootstrapped();
        ensureDaytime(helper);
        Villager v = spawnVillager(helper);
        v.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                new ItemStack(net.minecraft.world.item.Items.CARVED_PUMPKIN));
        PerkEntry entry = new PerkEntry(Perks.PHOTOPHOBIA_DEFECT.getId(), 1.0F, Optional.empty(), Optional.empty());
        Perks.PHOTOPHOBIA_DEFECT.get().tick(v, entry);
        helper.assertTrue(Math.abs(v.getHealth() - 20.0F) < 0.01F,
                "pumpkin-wearing photophobe must take no damage, got " + v.getHealth());

        v.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, ItemStack.EMPTY);
        PerkEntry denatured = new PerkEntry(Perks.PHOTOPHOBIA_DEFECT.getId(), 0.02F, Optional.empty(), Optional.empty());
        Perks.PHOTOPHOBIA_DEFECT.get().tick(v, denatured);
        helper.assertTrue(Math.abs(v.getHealth() - 20.0F) < 0.01F,
                "denatured photophobia must be inert, got " + v.getHealth());
        v.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void gluttony_drains_player_food(GameTestHelper helper) {
        ensureBootstrapped();
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        float before = player.getFoodData().getExhaustionLevel();
        PerkEntry entry = new PerkEntry(Perks.GLUTTONY_DEFECT.getId(), 1.0F, Optional.empty(), Optional.empty());
        Perks.GLUTTONY_DEFECT.get().tick(player, entry);
        float delta = player.getFoodData().getExhaustionLevel() - before;
        helper.assertTrue(Math.abs(delta - 1.5F) < 0.01F,
                "quality 1.0 gluttony must add 0.5 + 1.0 exhaustion per interval, got " + delta);

        // Non-players carry no food data; the tick must be a silent no-op.
        Villager v = spawnVillager(helper);
        Perks.GLUTTONY_DEFECT.get().tick(v, entry);
        v.discard();
        player.discard();
        helper.succeed();
    }

    /** Spider rather than zombie so the hostile has no natural reason to already target a villager. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void screamer_retargets_idle_hostiles(GameTestHelper helper) {
        ensureBootstrapped();
        var spider = helper.spawn(EntityType.SPIDER, new BlockPos(1, 1, 1));
        Villager host = helper.spawn(EntityType.VILLAGER, new BlockPos(3, 1, 3));
        helper.assertTrue(spider.getTarget() == null, "freshly spawned spider must be idle");

        PerkEntry entry = new PerkEntry(Perks.SCREAMER_DEFECT.getId(), 1.0F, Optional.empty(), Optional.empty());
        Perks.SCREAMER_DEFECT.get().tick(host, entry);
        helper.assertTrue(spider.getTarget() == host,
                "the scream must retarget an idle hostile at the host");

        // A hostile already locked onto a live target keeps it.
        Villager decoy = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 1, 3));
        spider.setTarget(decoy);
        Perks.SCREAMER_DEFECT.get().tick(host, entry);
        helper.assertTrue(spider.getTarget() == decoy,
                "the scream must not override an existing live target");
        spider.discard();
        host.discard();
        decoy.discard();
        helper.succeed();
    }

    /**
     * Forces thunder (rain + thunder levels set directly so isThundering flips within this
     * tick), rolls the 0.55-chance strike up to 30 times, and expects at least one lightning
     * bolt entity. Bolts are discarded before they ever tick so no fire or damage leaks, and
     * the weather is restored synchronously so concurrent tests never observe the storm.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void static_defect_draws_lightning_in_thunder(GameTestHelper helper) {
        ensureBootstrapped();
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        Villager v = spawnVillager(helper);
        PerkEntry entry = new PerkEntry(Perks.STATIC_DEFECT.getId(), 1.0F, Optional.empty(), Optional.empty());

        level.setWeatherParameters(0, 6000, true, true);
        level.setRainLevel(1.0F);
        level.setThunderLevel(1.0F);
        try {
            helper.assertTrue(level.isThundering(), "forced storm must register as thundering");
            helper.assertTrue(level.canSeeSky(v.blockPosition()),
                    "test environment must have open sky above the subject");

            boolean struck = false;
            for (int i = 0; i < 30 && !struck; i++) {
                Perks.STATIC_DEFECT.get().tick(v, entry);
                var bolts = level.getEntitiesOfClass(net.minecraft.world.entity.LightningBolt.class,
                        v.getBoundingBox().inflate(3.0D));
                if (!bolts.isEmpty()) {
                    bolts.forEach(net.minecraft.world.entity.LightningBolt::discard);
                    struck = true;
                }
            }
            helper.assertTrue(struck, "30 rolls at 55% per roll must produce a strike");
        } finally {
            level.setWeatherParameters(6000, 0, false, false);
            level.setRainLevel(0.0F);
            level.setThunderLevel(0.0F);
        }
        v.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void vertigo_triggers_near_drop(GameTestHelper helper) {
        ensureBootstrapped();
        PerkEntry entry = new PerkEntry(Perks.VERTIGO_DEFECT.getId(), 1.0F, Optional.empty(), Optional.empty());

        // Flat ground: no ledge within the 5x5 probe, no nausea.
        Villager grounded = spawnVillager(helper);
        Perks.VERTIGO_DEFECT.get().tick(grounded, entry);
        helper.assertTrue(!grounded.hasEffect(MobEffects.CONFUSION),
                "host on flat ground must not get nausea");
        grounded.discard();

        // Atop a four-block pillar every neighboring column drops past the threshold.
        for (int y = 1; y <= 4; y++) {
            helper.setBlock(new BlockPos(2, y, 2), net.minecraft.world.level.block.Blocks.STONE);
        }
        Villager climber = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 5, 2));
        Perks.VERTIGO_DEFECT.get().tick(climber, entry);
        helper.assertTrue(climber.hasEffect(MobEffects.CONFUSION),
                "host beside a >3 block drop must get nausea");

        Perks.VERTIGO_DEFECT.get().onUnequip(climber, entry);
        helper.assertTrue(!climber.hasEffect(MobEffects.CONFUSION),
                "unequip must clear the lingering nausea");
        climber.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Event-driven combat perks, through the real damage pipeline
    //   These do not call perk code directly: they equip the marker and drive a real
    //   hurt()/fall/death so the registered PerkEvents listeners do the work.
    // ============================================================================

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void bloodthirst_heals_attacker_from_damage_dealt(GameTestHelper helper) {
        ensureBootstrapped();
        var attacker = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        equipMarker(attacker, Perks.BLOODTHIRST.getId(), 1.0F);
        attacker.setHealth(10.0F);

        Zombie victim = spawnZombie(helper);
        victim.getAttribute(Attributes.ARMOR).setBaseValue(0.0D);
        victim.hurt(victim.damageSources().playerAttack(attacker), 6.0F);

        float victimLoss = 20.0F - victim.getHealth();
        helper.assertTrue(Math.abs(victimLoss - 6.0F) < 0.01F,
                "unarmored victim must take the full 6.0, lost " + victimLoss);
        float expected = 10.0F + 6.0F * com.confect1on.dynetech.gene.perks.BloodthirstPerk.HEAL_FRACTION_AT_FULL_QUALITY;
        helper.assertTrue(Math.abs(attacker.getHealth() - expected) < 0.01F,
                "attacker must heal 35% of damage dealt, got " + attacker.getHealth() + " want " + expected);
        victim.discard();
        attacker.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void bane_of_undead_boosts_damage_to_undead(GameTestHelper helper) {
        ensureBootstrapped();
        var baned = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        equipMarker(baned, Perks.BANE_OF_UNDEAD.getId(), 1.0F);
        var plain = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);

        Zombie boosted = spawnZombie(helper);
        boosted.getAttribute(Attributes.ARMOR).setBaseValue(0.0D);
        boosted.hurt(boosted.damageSources().playerAttack(baned), 4.0F);
        float boostedLoss = 20.0F - boosted.getHealth();

        Zombie control = spawnZombie(helper);
        control.getAttribute(Attributes.ARMOR).setBaseValue(0.0D);
        control.hurt(control.damageSources().playerAttack(plain), 4.0F);
        float controlLoss = 20.0F - control.getHealth();

        helper.assertTrue(Math.abs(controlLoss - 4.0F) < 0.01F,
                "control zombie must take the unmodified 4.0, lost " + controlLoss);
        float expected = 4.0F * (1.0F + com.confect1on.dynetech.gene.perks.BaneOfUndeadPerk.DAMAGE_BONUS_AT_FULL_QUALITY);
        helper.assertTrue(Math.abs(boostedLoss - expected) < 0.01F,
                "bane must scale damage to undead by 1.75x, lost " + boostedLoss + " want " + expected);
        boosted.discard();
        control.discard();
        baned.discard();
        plain.discard();
        helper.succeed();
    }

    /**
     * Reflect fires only for a direct melee source. Expected reflect is computed from the
     * damage the victim actually took, so the assertion holds even if the world difficulty
     * scales player-received damage.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void cactus_skin_reflects_close_melee_only(GameTestHelper helper) {
        ensureBootstrapped();
        var victim = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        equipMarker(victim, Perks.CACTUS_SKIN.getId(), 1.0F);
        Zombie attacker = spawnZombie(helper);
        attacker.getAttribute(Attributes.ARMOR).setBaseValue(0.0D);

        victim.hurt(victim.damageSources().mobAttack(attacker), 5.0F);
        float victimLoss = 20.0F - victim.getHealth();
        helper.assertTrue(victimLoss > 0.0F, "victim must actually take melee damage");
        float expectedReflect = victimLoss
                * com.confect1on.dynetech.gene.perks.CactusSkinPerk.REFLECT_FRACTION_AT_FULL_QUALITY;
        float attackerLoss = 20.0F - attacker.getHealth();
        helper.assertTrue(Math.abs(attackerLoss - expectedReflect) < 0.01F,
                "melee attacker must eat 40% of dealt damage as thorns, lost "
                        + attackerLoss + " want " + expectedReflect);

        // Projectile: direct entity differs from the causing entity, so no reflect.
        float attackerHealthBefore = attacker.getHealth();
        var arrow = helper.spawn(EntityType.ARROW, new BlockPos(1, 2, 1));
        victim.invulnerableTime = 0;
        victim.hurt(victim.damageSources().arrow(arrow, attacker), 5.0F);
        helper.assertTrue(Math.abs(attacker.getHealth() - attackerHealthBefore) < 0.01F,
                "an arrow hit must not trigger the reflect");
        arrow.discard();
        attacker.discard();
        victim.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void venom_touch_poisons_melee_victim(GameTestHelper helper) {
        ensureBootstrapped();
        Zombie attacker = spawnZombie(helper);
        equipMarker(attacker, Perks.VENOM_TOUCH.getId(), 1.0F);
        Villager victim = spawnVillager(helper);

        victim.hurt(victim.damageSources().mobAttack(attacker), 3.0F);

        helper.assertTrue(victim.hasEffect(MobEffects.POISON),
                "melee hit from a venom-touch host must poison the victim");
        int duration = victim.getEffect(MobEffects.POISON).getDuration();
        int expected = com.confect1on.dynetech.gene.perks.VenomTouchPerk.BASE_DURATION_TICKS
                + com.confect1on.dynetech.gene.perks.VenomTouchPerk.BONUS_DURATION_TICKS_AT_FULL_QUALITY;
        helper.assertTrue(duration > expected - 10 && duration <= expected,
                "quality 1.0 poison duration should be " + expected + " ticks, got " + duration);
        helper.assertTrue(victim.getEffect(MobEffects.POISON).getAmplifier() == 0,
                "venom touch stays at Poison I by design");
        attacker.discard();
        victim.discard();
        helper.succeed();
    }

    /**
     * Landing from 10 blocks: no fall damage, upward launch of BASE + blocks * PER_BLOCK. From
     * 30 blocks the launch clamps to MAX_LAUNCH. Below MIN_FALL_DISTANCE nothing happens.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void slime_bounce_cancels_fall_and_launches(GameTestHelper helper) {
        ensureBootstrapped();
        Zombie bouncer = spawnZombie(helper);
        equipMarker(bouncer, Perks.SLIME_BOUNCE.getId(), 1.0F);
        bouncer.causeFallDamage(10.0F, 1.0F, bouncer.damageSources().fall());
        helper.assertTrue(Math.abs(bouncer.getHealth() - 20.0F) < 0.01F,
                "bounce must negate all fall damage, health " + bouncer.getHealth());
        float expected = com.confect1on.dynetech.gene.perks.SlimeBouncePerk.BASE_LAUNCH
                + 10.0F * com.confect1on.dynetech.gene.perks.SlimeBouncePerk.LAUNCH_PER_BLOCK;
        helper.assertTrue(Math.abs(bouncer.getDeltaMovement().y - expected) < 0.01D,
                "10-block fall must launch at " + expected + ", got " + bouncer.getDeltaMovement().y);
        bouncer.discard();

        Zombie skydiver = spawnZombie(helper);
        equipMarker(skydiver, Perks.SLIME_BOUNCE.getId(), 1.0F);
        skydiver.causeFallDamage(30.0F, 1.0F, skydiver.damageSources().fall());
        helper.assertTrue(Math.abs(skydiver.getHealth() - 20.0F) < 0.01F,
                "capped bounce must still negate all fall damage");
        helper.assertTrue(Math.abs(skydiver.getDeltaMovement().y
                        - com.confect1on.dynetech.gene.perks.SlimeBouncePerk.MAX_LAUNCH) < 0.01D,
                "30-block fall must clamp to MAX_LAUNCH, got " + skydiver.getDeltaMovement().y);
        skydiver.discard();

        Zombie stumbler = spawnZombie(helper);
        equipMarker(stumbler, Perks.SLIME_BOUNCE.getId(), 1.0F);
        stumbler.causeFallDamage(1.0F, 1.0F, stumbler.damageSources().fall());
        helper.assertTrue(stumbler.getDeltaMovement().y < 0.05D,
                "a hop below MIN_FALL_DISTANCE must not launch");
        stumbler.discard();
        helper.succeed();
    }

    /**
     * Quality kept low (0.25 -> radius 2.0) so the blast's entity range stays inside this
     * test's own structure and cannot splash entities in neighboring gametests.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void explosive_death_detonates_and_clears_genome(GameTestHelper helper) {
        ensureBootstrapped();
        Zombie bomber = helper.spawn(EntityType.ZOMBIE, new BlockPos(3, 1, 3));
        equipMarker(bomber, Perks.EXPLOSIVE_DEATH.getId(), 0.25F);
        Villager bystander = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 2));
        float before = bystander.getHealth();

        bomber.hurt(bomber.damageSources().genericKill(), 1000.0F);

        helper.assertTrue(bomber.isDeadOrDying(), "bomber must actually die");
        helper.assertTrue(bystander.isDeadOrDying() || bystander.getHealth() < before,
                "the death blast must damage the adjacent bystander");
        helper.assertTrue(bomber.getData(DTAttachments.EQUIPPED_PERKS.get()).perks().isEmpty(),
                "onDeath must wipe the genome after the explosion dispatch");
        bystander.discard();
        bomber.discard();
        helper.succeed();
    }

    /**
     * The teleport is a 25% roll per hit at quality 1.0, so 60 hits make a miss astronomically
     * unlikely (0.75^60 ~ 3e-8). Health and i-frames reset between hits so every hit lands.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void ender_blink_teleports_victim_under_fire(GameTestHelper helper) {
        ensureBootstrapped();
        var victim = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        net.minecraft.world.phys.Vec3 center = helper.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 1.0, 2.5));
        victim.moveTo(center.x, center.y, center.z);
        equipMarker(victim, Perks.ENDER_BLINK.getId(), 1.0F);
        net.minecraft.world.phys.Vec3 start = victim.position();

        // The blink handler only reacts to hits with a living attacker, so a plain generic
        // damage source would never roll the teleport chance.
        var attacker = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        attacker.moveTo(center.x + 1.0, center.y, center.z);

        boolean moved = false;
        for (int i = 0; i < 60 && !moved; i++) {
            victim.setHealth(20.0F);
            victim.invulnerableTime = 0;
            victim.hurt(victim.damageSources().playerAttack(attacker), 1.0F);
            moved = victim.position().distanceToSqr(start) > 1.0D;
        }
        helper.assertTrue(moved, "ender blink must fire at least once across 60 qualifying hits");
        victim.discard();
        attacker.discard();
        helper.succeed();
    }
}
