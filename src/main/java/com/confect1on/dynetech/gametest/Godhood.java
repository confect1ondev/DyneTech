package com.confect1on.dynetech.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.confect1on.dynetech.gene.DTAttachments;
import com.confect1on.dynetech.gene.EquippedPerks;
import com.confect1on.dynetech.gene.GodhoodEvents;
import com.confect1on.dynetech.gene.GodhoodState;
import com.confect1on.dynetech.gene.PerkEntry;
import com.confect1on.dynetech.gene.Perks;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;

import java.util.List;
import java.util.Optional;

/**
 * GameTests for the Godhood gene. Uses the shared {@code empty} template so tests run against
 * a clean 5x5x5 arena. Prefixed {@code godhood.} so {@code /test runall godhood} targets this
 * suite specifically.
 *
 * <p>Most tests exercise the {@code public static} core methods on {@link GodhoodEvents}
 * directly since the event listeners themselves are trivial adapters. The {@code event_bus_*}
 * tests at the bottom of the file are the exception: they post real events through
 * {@link NeoForge#EVENT_BUS} to guard against a listener being accidentally unregistered or
 * having its priority/filter drift.
 */
@GameTestHolder(com.confect1on.dynetech.DyneTech.MODID)
@PrefixGameTestTemplate(true)
public final class Godhood {

    private static final String TEMPLATE = "empty";
    private static final BlockPos SPAWN = new BlockPos(2, 1, 2);

    private Godhood() {}

    // ============================================================================
    //  State + codec
    // ============================================================================

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void state_codec_roundtrips(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        DynamicOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);

        GodhoodState original = new GodhoodState(7, 123L, 456L);
        Tag encoded = GodhoodState.CODEC.encodeStart(ops, original).getOrThrow();
        GodhoodState decoded = GodhoodState.CODEC.parse(new Dynamic<>(ops, encoded)).getOrThrow();

        helper.assertTrue(decoded.regenCharges() == 7, "charges roundtrip");
        helper.assertTrue(decoded.regenEndGameTime() == 123L, "regen end roundtrip");
        helper.assertTrue(decoded.vulnerabilityEndGameTime() == 456L, "vulnerability end roundtrip");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void state_withCharges_clamps_to_range(GameTestHelper helper) {
        GodhoodState st = GodhoodState.EMPTY;
        helper.assertTrue(st.withCharges(-5).regenCharges() == 0, "negative clamps to 0");
        helper.assertTrue(st.withCharges(999).regenCharges() == GodhoodState.MAX_CHARGES, "over-max clamps");
        helper.assertTrue(st.withCharges(5).regenCharges() == 5, "in-range preserved");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void state_isRegenerating_and_isVulnerable_windows(GameTestHelper helper) {
        GodhoodState active = new GodhoodState(0, 100L, 200L);
        helper.assertTrue(active.isRegenerating(50L), "50 < 100 -> still regenerating");
        helper.assertTrue(!active.isRegenerating(100L), "100 == 100 -> no longer regenerating");
        helper.assertTrue(active.isVulnerable(150L), "150 < 200 -> still vulnerable");
        helper.assertTrue(!active.isVulnerable(300L), "300 > 200 -> not vulnerable");
        helper.assertTrue(!GodhoodState.EMPTY.isRegenerating(0L), "default state is not regenerating");
        helper.succeed();
    }

    // ============================================================================
    //  hasGodhood detection
    // ============================================================================

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void hasGodhood_true_when_equipped(GameTestHelper helper) {
        Player p = mockPlayer(helper);
        equipGodhood(p, 1.0F);
        helper.assertTrue(GodhoodEvents.hasGodhood(p), "equipped Godhood must register");
        p.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void hasGodhood_false_when_absent(GameTestHelper helper) {
        Player p = mockPlayer(helper);
        helper.assertTrue(!GodhoodEvents.hasGodhood(p), "no perks means no Godhood");
        p.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void hasGodhood_false_when_denatured(GameTestHelper helper) {
        Player p = mockPlayer(helper);
        // Denatured threshold is well under 0.05; use 0.0 to guarantee we're below it.
        equipGodhood(p, 0.0F);
        helper.assertTrue(!GodhoodEvents.hasGodhood(p),
                "denatured Godhood should be treated as mortal");
        p.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Mechanic 1 - essence on player kill
    // ============================================================================

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void essence_kill_grants_charge(GameTestHelper helper) {
        Player killer = mockPlayer(helper);
        Player victim = mockPlayer(helper);
        equipGodhood(killer, 1.0F);
        DamageSource src = killer.damageSources().playerAttack(killer);

        int result = GodhoodEvents.tryGrantEssence(killer, victim, src);
        helper.assertTrue(result == 1, "first kill grants charge 1, got " + result);
        GodhoodState st = killer.getData(DTAttachments.GODHOOD_STATE.get());
        helper.assertTrue(st.regenCharges() == 1, "attachment reflects the grant");

        killer.discard();
        victim.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void essence_kill_caps_at_max(GameTestHelper helper) {
        Player killer = mockPlayer(helper);
        Player victim = mockPlayer(helper);
        equipGodhood(killer, 1.0F);
        killer.setData(DTAttachments.GODHOOD_STATE.get(),
                GodhoodState.EMPTY.withCharges(GodhoodState.MAX_CHARGES));

        DamageSource src = killer.damageSources().playerAttack(killer);
        int result = GodhoodEvents.tryGrantEssence(killer, victim, src);
        helper.assertTrue(result == -1, "kill at cap returns -1");
        GodhoodState st = killer.getData(DTAttachments.GODHOOD_STATE.get());
        helper.assertTrue(st.regenCharges() == GodhoodState.MAX_CHARGES, "charges unchanged at cap");

        killer.discard();
        victim.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void essence_non_god_killer_grants_nothing(GameTestHelper helper) {
        Player killer = mockPlayer(helper);
        Player victim = mockPlayer(helper);
        // Killer has no Godhood equipped.
        DamageSource src = killer.damageSources().playerAttack(killer);
        int result = GodhoodEvents.tryGrantEssence(killer, victim, src);
        helper.assertTrue(result == -1, "non-god killer must not gain charge");
        killer.discard();
        victim.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void essence_mob_victim_grants_nothing(GameTestHelper helper) {
        Player killer = mockPlayer(helper);
        equipGodhood(killer, 1.0F);
        Cow cow = helper.spawn(EntityType.COW, SPAWN);
        DamageSource src = killer.damageSources().playerAttack(killer);
        int result = GodhoodEvents.tryGrantEssence(killer, cow, src);
        helper.assertTrue(result == -1, "mob kills do not grant essence in MVP");
        killer.discard();
        cow.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void essence_marked_dummy_grants_charge(GameTestHelper helper) {
        Player killer = mockPlayer(helper);
        equipGodhood(killer, 1.0F);
        Zombie dummy = helper.spawn(EntityType.ZOMBIE, SPAWN);
        dummy.setData(DTAttachments.TEST_ESSENCE_TARGET.get(), Boolean.TRUE);
        DamageSource src = killer.damageSources().playerAttack(killer);

        int result = GodhoodEvents.tryGrantEssence(killer, dummy, src);
        helper.assertTrue(result == 1,
                "marked dummy must grant a charge like a player kill would, got " + result);

        killer.discard();
        dummy.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void essence_unmarked_zombie_grants_nothing(GameTestHelper helper) {
        Player killer = mockPlayer(helper);
        equipGodhood(killer, 1.0F);
        Zombie z = helper.spawn(EntityType.ZOMBIE, SPAWN);
        // No marker attachment set.
        DamageSource src = killer.damageSources().playerAttack(killer);
        int result = GodhoodEvents.tryGrantEssence(killer, z, src);
        helper.assertTrue(result == -1, "unmarked mob must not grant essence");
        killer.discard();
        z.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void hasGodhood_true_for_zombie_with_gene_equipped(GameTestHelper helper) {
        // The essence dummy is a zombie carrying the Godhood gene so the whisper broadcaster
        // treats it as an ambience emitter. Verify hasGodhood picks up on any LivingEntity,
        // not just players.
        Zombie z = helper.spawn(EntityType.ZOMBIE, SPAWN);
        helper.assertTrue(!GodhoodEvents.hasGodhood(z), "bare zombie has no gene");
        equipGodhood(z, 1.0F);
        helper.assertTrue(GodhoodEvents.hasGodhood(z), "zombie with Godhood gene registers");
        z.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void emit_whispers_from_mob_does_not_throw(GameTestHelper helper) {
        // Smoke test: emitWhispers reads state + calls level().getEntitiesOfClass. Verifies
        // the non-player emitter path doesn't blow up on missing packets/connections when
        // there are no god listeners nearby.
        Zombie z = helper.spawn(EntityType.ZOMBIE, SPAWN);
        equipGodhood(z, 1.0F);
        z.setData(DTAttachments.GODHOOD_STATE.get(), GodhoodState.EMPTY.withCharges(5));
        GodhoodEvents.emitWhispers(z);
        z.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Whisper detection formulas
    // ============================================================================

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void whisper_volume_scales_linearly_with_charges(GameTestHelper helper) {
        float v0 = GodhoodEvents.whisperVolume(0);
        float v5 = GodhoodEvents.whisperVolume(5);
        float v10 = GodhoodEvents.whisperVolume(GodhoodState.MAX_CHARGES);
        helper.assertTrue(v0 == 0.0F, "0 charges must yield 0 volume, got " + v0);
        helper.assertTrue(v10 > v5 && v5 > v0, "volume must grow monotonically with charges");
        // Linear: v5 should be halfway between v0 and v10 within a small tolerance.
        float expectedMid = (v0 + v10) / 2.0F;
        helper.assertTrue(Math.abs(v5 - expectedMid) < 0.01F,
                "expected v5 near " + expectedMid + " (linear midpoint), got " + v5);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void whisper_radius_scales_with_charges(GameTestHelper helper) {
        // Formula: base * (0.5 + charges * per_charge). Defaults: base=64, per_charge=0.1.
        // At 0 charges the emitter is silent so radius is only used for the potential-listener
        // scan; still expect the formula to give a sensible value.
        double r0 = GodhoodEvents.whisperRadius(0);
        double r10 = GodhoodEvents.whisperRadius(GodhoodState.MAX_CHARGES);
        helper.assertTrue(r10 > r0, "radius must grow with charges");
        double expected10over0 = 1.5 / 0.5; // (0.5 + 10 * 0.1) / 0.5 = 3.0
        double actualRatio = r10 / r0;
        helper.assertTrue(Math.abs(actualRatio - expected10over0) < 0.01,
                "10-charge / 0-charge radius ratio expected " + expected10over0
                        + ", got " + actualRatio);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void stop_whispers_handler_fires_for_dying_dummy(GameTestHelper helper) {
        // Confirms the same event-listener code path that handles a player true death also
        // handles a dummy death: hasGodhood check succeeds pre-clearAllPerks, and the stop
        // helper is invoked without regard to player-vs-mob type.
        Zombie z = helper.spawn(EntityType.ZOMBIE, SPAWN);
        equipGodhood(z, 1.0F);
        z.setData(DTAttachments.GODHOOD_STATE.get(), GodhoodState.EMPTY.withCharges(4));
        helper.assertTrue(GodhoodEvents.hasGodhood(z), "dummy has Godhood before death");

        LivingDeathEvent event = new LivingDeathEvent(z, z.damageSources().generic());
        GodhoodEvents.onGodhoodDeathStopWhispers(event);
        // No listeners in range: stopWhispers returns silently. Test passes if no throw.
        z.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void stop_whispers_from_mob_does_not_throw(GameTestHelper helper) {
        // Death and logout hooks call stopWhispers on the dying/leaving emitter. Verify the
        // packet-broadcast path handles a lonely emitter (no god listeners in range) cleanly.
        Zombie z = helper.spawn(EntityType.ZOMBIE, SPAWN);
        equipGodhood(z, 1.0F);
        z.setData(DTAttachments.GODHOOD_STATE.get(), GodhoodState.EMPTY.withCharges(7));
        GodhoodEvents.stopWhispers(z);
        z.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void whisper_zero_charges_emits_nothing(GameTestHelper helper) {
        // Volume-of-0 is the observable signal that emitWhispers short-circuits at 0 charges.
        // Testing the ClientboundSoundPacket send count directly would require a real network
        // stack; the volume floor is the contract that matters.
        helper.assertTrue(GodhoodEvents.whisperVolume(0) == 0.0F,
                "0 charges must produce silent output");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void isEssenceSource_recognises_player_and_marked_mob(GameTestHelper helper) {
        Player p = mockPlayer(helper);
        helper.assertTrue(GodhoodEvents.isEssenceSource(p), "players are always essence sources");

        Zombie z = helper.spawn(EntityType.ZOMBIE, SPAWN);
        helper.assertTrue(!GodhoodEvents.isEssenceSource(z), "unmarked mob is not an essence source");
        z.setData(DTAttachments.TEST_ESSENCE_TARGET.get(), Boolean.TRUE);
        helper.assertTrue(GodhoodEvents.isEssenceSource(z), "marked mob becomes an essence source");

        p.discard();
        z.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void essence_blast_source_grants_nothing(GameTestHelper helper) {
        Player killer = mockPlayer(helper);
        Player victim = mockPlayer(helper);
        equipGodhood(killer, 1.0F);

        DamageSource blast = new DamageSource(
                helper.getLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                        .getHolderOrThrow(GodhoodEvents.BLAST_DAMAGE_KEY),
                killer, killer);
        int result = GodhoodEvents.tryGrantEssence(killer, victim, blast);
        helper.assertTrue(result == -1, "blast kills grant no essence");

        killer.discard();
        victim.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void essence_self_kill_grants_nothing(GameTestHelper helper) {
        Player p = mockPlayer(helper);
        equipGodhood(p, 1.0F);
        DamageSource src = p.damageSources().playerAttack(p);
        int result = GodhoodEvents.tryGrantEssence(p, p, src);
        helper.assertTrue(result == -1, "killer == victim never grants");
        p.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Mechanic 2 - cheat death
    // ============================================================================

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void intercept_death_consumes_charge_and_starts_regen(GameTestHelper helper) {
        Player p = mockPlayer(helper);
        equipGodhood(p, 1.0F);
        p.setData(DTAttachments.GODHOOD_STATE.get(), GodhoodState.EMPTY.withCharges(3));
        DamageSource src = p.damageSources().generic();

        boolean intercepted = GodhoodEvents.tryInterceptDeath(p, src);
        helper.assertTrue(intercepted, "god with charges should cheat death");

        GodhoodState st = p.getData(DTAttachments.GODHOOD_STATE.get());
        helper.assertTrue(st.regenCharges() == 2, "one charge consumed, got " + st.regenCharges());
        helper.assertTrue(st.regenEndGameTime() > p.level().getGameTime(),
                "regen timer set into the future");
        helper.assertTrue(p.isInvulnerable(), "god is invulnerable during burn-up");
        helper.assertTrue(p.getHealth() == 1.0F, "parked at 1HP for the burn-up phase");
        p.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void intercept_death_at_zero_charges_returns_false(GameTestHelper helper) {
        Player p = mockPlayer(helper);
        equipGodhood(p, 1.0F);
        // Charges default to 0.
        DamageSource src = p.damageSources().generic();

        boolean intercepted = GodhoodEvents.tryInterceptDeath(p, src);
        helper.assertTrue(!intercepted, "god at 0 charges should die a true death");
        GodhoodState st = p.getData(DTAttachments.GODHOOD_STATE.get());
        helper.assertTrue(st.regenEndGameTime() < 0, "no regen timer set");
        helper.assertTrue(!p.isInvulnerable(), "not made invulnerable");
        p.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void intercept_death_during_vulnerability_returns_false(GameTestHelper helper) {
        Player p = mockPlayer(helper);
        equipGodhood(p, 1.0F);
        long now = p.level().getGameTime();
        p.setData(DTAttachments.GODHOOD_STATE.get(),
                GodhoodState.EMPTY.withCharges(5).withVulnerabilityEnd(now + 100));
        DamageSource src = p.damageSources().generic();

        boolean intercepted = GodhoodEvents.tryInterceptDeath(p, src);
        helper.assertTrue(!intercepted, "vulnerability window prevents cheating death");
        GodhoodState st = p.getData(DTAttachments.GODHOOD_STATE.get());
        helper.assertTrue(st.regenCharges() == 5, "charges not consumed on true death");
        p.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void void_damage_bypasses_intercept(GameTestHelper helper) {
        Player p = mockPlayer(helper);
        equipGodhood(p, 1.0F);
        p.setData(DTAttachments.GODHOOD_STATE.get(), GodhoodState.EMPTY.withCharges(5));
        DamageSource voidSrc = p.damageSources().fellOutOfWorld();

        boolean intercepted = GodhoodEvents.tryInterceptDeath(p, voidSrc);
        helper.assertTrue(!intercepted, "void damage is a true death regardless of charges");
        GodhoodState st = p.getData(DTAttachments.GODHOOD_STATE.get());
        helper.assertTrue(st.regenCharges() == 5, "void death should not consume a charge");
        p.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void intercept_death_no_godhood_returns_false(GameTestHelper helper) {
        Player p = mockPlayer(helper);
        // No perks; state charges are irrelevant.
        p.setData(DTAttachments.GODHOOD_STATE.get(), GodhoodState.EMPTY.withCharges(5));
        boolean intercepted = GodhoodEvents.tryInterceptDeath(p, p.damageSources().generic());
        helper.assertTrue(!intercepted, "no gene means no save even if state has charges");
        p.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Mechanic 2 - invincibility during burn-up
    // ============================================================================

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void invincibility_true_during_regen(GameTestHelper helper) {
        Player p = mockPlayer(helper);
        equipGodhood(p, 1.0F);
        long now = p.level().getGameTime();
        p.setData(DTAttachments.GODHOOD_STATE.get(),
                GodhoodState.EMPTY.withRegenEnd(now + 100));
        helper.assertTrue(GodhoodEvents.isInvincibleFromRegen(p), "mid-burn should block damage");
        p.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void invincibility_false_outside_regen(GameTestHelper helper) {
        Player p = mockPlayer(helper);
        equipGodhood(p, 1.0F);
        helper.assertTrue(!GodhoodEvents.isInvincibleFromRegen(p), "no burn-up state => damage allowed");
        p.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Mechanic 3 - heal blocking
    // ============================================================================

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void heal_blocked_during_vulnerability(GameTestHelper helper) {
        Player p = mockPlayer(helper);
        equipGodhood(p, 1.0F);
        long now = p.level().getGameTime();
        p.setData(DTAttachments.GODHOOD_STATE.get(),
                GodhoodState.EMPTY.withVulnerabilityEnd(now + 100));
        helper.assertTrue(GodhoodEvents.shouldBlockHeal(p), "healing must be blocked during window");
        p.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void heal_allowed_outside_vulnerability(GameTestHelper helper) {
        Player p = mockPlayer(helper);
        equipGodhood(p, 1.0F);
        helper.assertTrue(!GodhoodEvents.shouldBlockHeal(p),
                "no vulnerability window => heal allowed");
        p.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Blast damage curve
    // ============================================================================

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void blast_damage_lethal_inside_inner_radius(GameTestHelper helper) {
        helper.assertTrue(GodhoodEvents.computeBlastDamage(0.0) >= 200.0F, "point-blank lethal");
        helper.assertTrue(GodhoodEvents.computeBlastDamage(5.0) >= 200.0F, "inner edge lethal");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void blast_damage_falls_off_linearly(GameTestHelper helper) {
        // 10 blocks = halfway between 5 and 15, so damage should be about half of BLAST_INNER_DAMAGE.
        float half = GodhoodEvents.computeBlastDamage(10.0);
        helper.assertTrue(half > 90.0F && half < 110.0F,
                "midpoint should be ~100, got " + half);
        helper.assertTrue(GodhoodEvents.computeBlastDamage(15.0) == 0.0F, "outer edge is zero");
        helper.assertTrue(GodhoodEvents.computeBlastDamage(20.0) == 0.0F, "beyond radius is zero");
        helper.succeed();
    }

    // ============================================================================
    //  Tick-driven transitions
    // ============================================================================

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void tick_completes_regen_at_end_time(GameTestHelper helper) {
        Player p = mockPlayer(helper);
        equipGodhood(p, 1.0F);
        long now = p.level().getGameTime();
        // Set the regen to end this tick.
        p.setData(DTAttachments.GODHOOD_STATE.get(),
                GodhoodState.EMPTY.withCharges(2).withRegenEnd(now));
        p.setInvulnerable(true);
        p.setHealth(1.0F);

        GodhoodEvents.tickGodhood(p);

        GodhoodState st = p.getData(DTAttachments.GODHOOD_STATE.get());
        helper.assertTrue(st.regenEndGameTime() < 0, "regen end cleared after completion");
        helper.assertTrue(st.vulnerabilityEndGameTime() > now, "vulnerability window opened");
        helper.assertTrue(!p.isInvulnerable(), "invulnerability removed after regen");
        AttributeInstance maxHp = p.getAttribute(Attributes.MAX_HEALTH);
        helper.assertTrue(maxHp.getValue() < 20.0,
                "max HP modifier applied, got " + maxHp.getValue());
        helper.assertTrue(p.hasEffect(MobEffects.WEAKNESS), "weakness applied entering vulnerability");
        p.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void tick_ends_vulnerability_at_end_time(GameTestHelper helper) {
        Player p = mockPlayer(helper);
        equipGodhood(p, 1.0F);
        long now = p.level().getGameTime();
        p.setData(DTAttachments.GODHOOD_STATE.get(),
                GodhoodState.EMPTY.withVulnerabilityEnd(now));
        // Simulate the modifier + effect being present.
        GodhoodEvents.completeRegeneration(p); // sets modifier + weakness, but also opens a new window
        // Force the window back to "now" so the tick ends it.
        p.setData(DTAttachments.GODHOOD_STATE.get(),
                p.getData(DTAttachments.GODHOOD_STATE.get()).withVulnerabilityEnd(now));

        GodhoodEvents.tickGodhood(p);

        GodhoodState st = p.getData(DTAttachments.GODHOOD_STATE.get());
        helper.assertTrue(st.vulnerabilityEndGameTime() < 0, "vulnerability end cleared");
        helper.assertTrue(!p.hasEffect(MobEffects.WEAKNESS), "weakness stripped");
        AttributeInstance maxHp = p.getAttribute(Attributes.MAX_HEALTH);
        helper.assertTrue(maxHp.getValue() == 20.0,
                "max HP back to base 20, got " + maxHp.getValue());
        p.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Session resume
    // ============================================================================

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void resume_state_reapplies_vulnerability_modifier(GameTestHelper helper) {
        Player p = mockPlayer(helper);
        equipGodhood(p, 1.0F);
        long now = p.level().getGameTime();
        // Simulate a saved state where the vulnerability window is still active. Modifier is
        // NOT present (transient modifiers don't survive a save round-trip).
        p.setData(DTAttachments.GODHOOD_STATE.get(),
                GodhoodState.EMPTY.withVulnerabilityEnd(now + 200));
        AttributeInstance maxHp = p.getAttribute(Attributes.MAX_HEALTH);
        helper.assertTrue(maxHp.getValue() == 20.0, "sanity: modifier absent before resume");

        GodhoodEvents.resumeState(p);

        helper.assertTrue(maxHp.getValue() < 20.0,
                "resume must reapply the max-HP modifier, got " + maxHp.getValue());
        p.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void resume_state_completes_pending_regen_offline(GameTestHelper helper) {
        Player p = mockPlayer(helper);
        equipGodhood(p, 1.0F);
        long now = p.level().getGameTime();
        // Saved state was mid-regen; the timer has since elapsed.
        p.setData(DTAttachments.GODHOOD_STATE.get(),
                GodhoodState.EMPTY.withRegenEnd(now - 10));
        p.setInvulnerable(true);
        p.setHealth(1.0F);

        GodhoodEvents.resumeState(p);

        GodhoodState st = p.getData(DTAttachments.GODHOOD_STATE.get());
        helper.assertTrue(st.regenEndGameTime() < 0, "regen phase cleared on resume");
        helper.assertTrue(st.vulnerabilityEndGameTime() > now,
                "vulnerability window opened on resume");
        helper.assertTrue(!p.isInvulnerable(), "no longer invulnerable after resume");
        AttributeInstance maxHp = p.getAttribute(Attributes.MAX_HEALTH);
        helper.assertTrue(maxHp.getValue() < 20.0,
                "vulnerability modifier applied on resume, got " + maxHp.getValue());
        p.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void resume_state_clears_elapsed_vulnerability(GameTestHelper helper) {
        Player p = mockPlayer(helper);
        equipGodhood(p, 1.0F);
        long now = p.level().getGameTime();
        // Window elapsed while offline.
        p.setData(DTAttachments.GODHOOD_STATE.get(),
                GodhoodState.EMPTY.withVulnerabilityEnd(now - 5));

        GodhoodEvents.resumeState(p);

        GodhoodState st = p.getData(DTAttachments.GODHOOD_STATE.get());
        helper.assertTrue(st.vulnerabilityEndGameTime() < 0, "elapsed vulnerability cleared");
        p.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Blast detonation - end to end
    // ============================================================================

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void blast_damages_nearby_victim(GameTestHelper helper) {
        // Full detonation path: registry-resolved damage source, real hurt() call, real damage
        // applied. Guards against the tag/damage-type wiring silently drifting.
        Player god = mockPlayer(helper);
        equipGodhood(god, 1.0F);
        Vec3 center = helper.absolutePos(SPAWN).getCenter();
        god.moveTo(center.x, center.y, center.z);

        Zombie victim = helper.spawn(EntityType.ZOMBIE, SPAWN.offset(1, 0, 0));
        boostMaxHealth(victim, 500F);
        float startHealth = victim.getHealth();

        GodhoodEvents.completeRegeneration(god);

        float taken = startHealth - victim.getHealth();
        helper.assertTrue(taken > 0F, "victim inside blast radius must take damage, took " + taken);
        god.discard();
        victim.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void blast_halves_damage_for_other_gods(GameTestHelper helper) {
        // Two zombies at the same offset from the detonating god. One carries the gene, so the
        // half-damage clause should give it roughly 50% of the mortal victim's loss. Both are
        // HP-boosted so neither dies; a survivor lets us compare deltas cleanly.
        Player god = mockPlayer(helper);
        equipGodhood(god, 1.0F);
        Vec3 center = helper.absolutePos(SPAWN).getCenter();
        god.moveTo(center.x, center.y, center.z);

        Zombie godVictim = helper.spawn(EntityType.ZOMBIE, SPAWN.offset(1, 0, 0));
        Zombie mortalVictim = helper.spawn(EntityType.ZOMBIE, SPAWN.offset(1, 0, 0));
        equipGodhood(godVictim, 1.0F);
        boostMaxHealth(godVictim, 500F);
        boostMaxHealth(mortalVictim, 500F);
        float godStart = godVictim.getHealth();
        float mortalStart = mortalVictim.getHealth();

        GodhoodEvents.completeRegeneration(god);

        float godTaken = godStart - godVictim.getHealth();
        float mortalTaken = mortalStart - mortalVictim.getHealth();
        helper.assertTrue(mortalTaken > 0F, "mortal victim must take non-zero damage");
        helper.assertTrue(godTaken > 0F, "god victim must take non-zero damage");
        float ratio = godTaken / mortalTaken;
        helper.assertTrue(ratio > 0.45F && ratio < 0.55F,
                "god should take ~half damage of mortal, ratio was " + ratio
                        + " (god " + godTaken + " / mortal " + mortalTaken + ")");

        god.discard();
        godVictim.discard();
        mortalVictim.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void blast_does_not_recurse_via_self_hit(GameTestHelper helper) {
        // If the "e != god" clause in detonateBlast is ever removed, the god takes 200 damage
        // at distance 0, hurt() posts LivingDeathEvent, and the HIGHEST-priority
        // onGodhoodDeath listener burns a charge to cheat that (recursive) death. Seed enough
        // charges to detect the consumption: after a normal completeRegeneration the count
        // must match the seed exactly. NOT setting invulnerable is deliberate; the whole
        // point is to let a broken filter's hurt() reach the death path.
        Player god = mockPlayer(helper);
        equipGodhood(god, 1.0F);
        Vec3 center = helper.absolutePos(SPAWN).getCenter();
        god.moveTo(center.x, center.y, center.z);
        // makeMockPlayer creates a Player but does NOT register it with the level's entity
        // index. Without this addFreshEntity the AABB scan in detonateBlast can never see the
        // god, so the "e != god" filter would appear to work even if deleted. Registering
        // the entity is what actually puts this test in a position to catch a regression.
        helper.getLevel().addFreshEntity(god);
        long now = god.level().getGameTime();
        god.setData(DTAttachments.GODHOOD_STATE.get(),
                GodhoodState.EMPTY.withCharges(3).withRegenEnd(now));
        god.setHealth(god.getMaxHealth());

        GodhoodEvents.completeRegeneration(god);

        GodhoodState st = god.getData(DTAttachments.GODHOOD_STATE.get());
        helper.assertTrue(st.regenCharges() == 3,
                "self-exclusion filter must prevent recursive charge consumption, got "
                        + st.regenCharges());
        helper.assertTrue(god.getHealth() == god.getMaxHealth(),
                "god should be at max health, got " + god.getHealth());
        god.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Event bus wiring
    // ============================================================================

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void event_bus_intercepts_real_death_event(GameTestHelper helper) {
        // Posts the actual LivingDeathEvent through NeoForge's bus. If GodhoodEvents::onGodhoodDeath
        // ever loses its registration or priority, this test flips.
        Player god = mockPlayer(helper);
        equipGodhood(god, 1.0F);
        god.setData(DTAttachments.GODHOOD_STATE.get(), GodhoodState.EMPTY.withCharges(2));

        LivingDeathEvent event = new LivingDeathEvent(god, god.damageSources().generic());
        NeoForge.EVENT_BUS.post(event);

        helper.assertTrue(event.isCanceled(), "listener must cancel death when god has charges");
        GodhoodState st = god.getData(DTAttachments.GODHOOD_STATE.get());
        helper.assertTrue(st.regenCharges() == 1,
                "charge consumed via listener path, got " + st.regenCharges());
        helper.assertTrue(st.regenEndGameTime() > god.level().getGameTime(),
                "regen timer opened via listener path");
        god.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void event_bus_grants_essence_on_real_death_event(GameTestHelper helper) {
        Player killer = mockPlayer(helper);
        Player victim = mockPlayer(helper);
        equipGodhood(killer, 1.0F);

        LivingDeathEvent event = new LivingDeathEvent(victim,
                killer.damageSources().playerAttack(killer));
        NeoForge.EVENT_BUS.post(event);

        GodhoodState st = killer.getData(DTAttachments.GODHOOD_STATE.get());
        helper.assertTrue(st.regenCharges() == 1,
                "essence listener must grant a charge from a real event, got " + st.regenCharges());
        killer.discard();
        victim.discard();
        helper.succeed();
    }

    // ============================================================================
    //  Helpers
    // ============================================================================

    private static Player mockPlayer(GameTestHelper helper) {
        return helper.makeMockPlayer(GameType.SURVIVAL);
    }

    /** Equips only the Godhood perk at the specified quality via a direct attachment write. */
    private static void equipGodhood(LivingEntity entity, float quality) {
        PerkEntry entry = new PerkEntry(Perks.GODHOOD.getId(), quality,
                Optional.empty(), Optional.empty());
        entity.setData(DTAttachments.EQUIPPED_PERKS.get(),
                new EquippedPerks(List.of(entry)));
    }

    /**
     * Bumps max HP with a transient modifier and refills to full. Lets blast-damage tests
     * pick a target that survives an inner-radius hit so the resulting health delta is
     * observable rather than just "dead."
     */
    private static void boostMaxHealth(LivingEntity entity, float bonusHp) {
        AttributeInstance maxHp = entity.getAttribute(Attributes.MAX_HEALTH);
        if (maxHp == null) return;
        maxHp.addTransientModifier(new AttributeModifier(
                ResourceLocation.parse("dynetech:test_hp_boost"),
                bonusHp, AttributeModifier.Operation.ADD_VALUE));
        entity.setHealth(entity.getMaxHealth());
    }
}
