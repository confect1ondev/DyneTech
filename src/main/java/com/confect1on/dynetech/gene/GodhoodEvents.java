package com.confect1on.dynetech.gene;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.config.DTConfig;
import com.confect1on.dynetech.gene.perks.GodhoodPerk;
import com.confect1on.dynetech.network.DTPayloads;
import com.confect1on.dynetech.sound.DTSounds;

import java.util.List;

/**
 * Server-side listeners for the Godhood gene. Kept out of {@link PerkEvents} because Godhood
 * carries persistent state (charges + two timers) and is the only perk that conditionally
 * cancels death.
 *
 * <p>Design notes worth calling out because they're not obvious from the code:
 * <ul>
 *   <li>Void damage bypasses regeneration. A fall into the abyss is a true death regardless
 *   of charges - simpler than teleport-to-safe-block and avoids a repeat-consume loop.</li>
 *   <li>Cheat-death runs at {@link LivingDeathEvent}, not incoming damage: matches totem
 *   semantics and skips having to guess which damage sources are lethal.</li>
 *   <li>Vulnerability window state is authoritative in {@link GodhoodState}; the max-HP
 *   modifier and Weakness are only visible expressions of it and get re-applied on login.
 *   Timers use {@code getGameTime} so they survive a save round-trip.</li>
 *   <li>Blast damage source bypasses armor/shield/effects via tag overlays in
 *   {@code data/minecraft/tags/damage_type/}.</li>
 * </ul>
 *
 * <p>All the state-mutating logic sits in {@code public static} methods that take plain args,
 * so gametests can drive them without constructing real events. The {@code on*} listeners are
 * thin adapters over those helpers.
 */
public final class GodhoodEvents {

    public static final ResourceKey<DamageType> BLAST_DAMAGE_KEY =
            ResourceKey.create(Registries.DAMAGE_TYPE, DyneTech.id("godhood_blast"));

    private static final ResourceLocation VULNERABILITY_MODIFIER_ID = DyneTech.id("godhood_vulnerability");

    public static final int REGEN_DURATION_TICKS = 100;             // 5 seconds
    public static final int VULNERABILITY_DURATION_TICKS = 1200;    // 60 seconds

    private static final double BLAST_INNER_RADIUS = 5.0;
    private static final double BLAST_OUTER_RADIUS = 15.0;
    private static final float BLAST_INNER_DAMAGE = 200.0F;
    private static final double VULNERABILITY_MAX_HP_DELTA = -16.0; // 20 base drops to 4

    // Refresh weakness aggressively so a milk chug is masked by the next tick's reapply.
    private static final int WEAKNESS_REFRESH_INTERVAL_TICKS = 5;
    private static final int WEAKNESS_EFFECT_DURATION_TICKS = 40;

    // Whisper detection. Every {@code WHISPER_TICK_INTERVAL} ticks the world scans every
    // god-carrying entity and broadcasts whispers to nearby god listeners. All tuning lives
    // in {@link DTConfig}. Uses SoundSource.PLAYERS so a low Ambient slider doesn't mute it.

    private GodhoodEvents() {}

    // ============================================================================
    //  Mechanic 1 - essence on player kill
    // ============================================================================

    public static void onEssenceKill(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        LivingEntity victim = event.getEntity();
        if (!isEssenceSource(victim)) return;
        if (victim.level().isClientSide) return;
        Entity direct = event.getSource().getEntity();
        if (!(direct instanceof Player killer)) return;
        tryGrantEssence(killer, victim, event.getSource());
    }

    /**
     * True when killing this entity grants a Godhood charge. Real players always qualify;
     * mobs qualify only if flagged with {@link DTAttachments#TEST_ESSENCE_TARGET} (the
     * command-spawned test dummy). Kept as a single predicate so the event listener and the
     * core rule stay in sync.
     */
    public static boolean isEssenceSource(LivingEntity entity) {
        if (entity instanceof Player) return true;
        return entity.hasData(DTAttachments.TEST_ESSENCE_TARGET.get())
                && entity.getData(DTAttachments.TEST_ESSENCE_TARGET.get());
    }

    /**
     * Grants a charge to {@code killer} if the kill qualifies. Returns the new charge count on
     * success, or -1 if the kill did not qualify.
     */
    public static int tryGrantEssence(Player killer, LivingEntity victim, DamageSource src) {
        if (killer == victim) return -1;
        if (!isEssenceSource(victim)) return -1;
        if (!hasGodhood(killer)) return -1;
        if (src.is(BLAST_DAMAGE_KEY)) return -1;

        GodhoodState st = killer.getData(DTAttachments.GODHOOD_STATE.get());
        if (st.regenCharges() >= GodhoodState.MAX_CHARGES) return -1;
        GodhoodState next = st.withCharges(st.regenCharges() + 1);
        killer.setData(DTAttachments.GODHOOD_STATE.get(), next);
        if (killer instanceof ServerPlayer serverKiller) sendChargeSync(serverKiller, next);
        return next.regenCharges();
    }

    // ============================================================================
    //  Mechanic 2 - cheat death + burn-up entry
    // ============================================================================

    public static void onGodhoodDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof Player p)) return;
        if (p.level().isClientSide) return;
        if (tryInterceptDeath(p, event.getSource())) event.setCanceled(true);
    }

    /**
     * Attempts to intercept a death. Returns true when the death was cancelled and burn-up
     * started; false when the player should die a true death (no charges, in vulnerability
     * window, void damage, or not a god).
     */
    public static boolean tryInterceptDeath(Player sp, DamageSource src) {
        if (!hasGodhood(sp)) return false;
        if (src.is(DamageTypes.FELL_OUT_OF_WORLD)) return false;

        long now = sp.level().getGameTime();
        GodhoodState st = sp.getData(DTAttachments.GODHOOD_STATE.get());

        if (st.isVulnerable(now)) {
            sendTrueDeathMessage(sp);
            return false;
        }
        if (st.regenCharges() <= 0) {
            sendTrueDeathMessage(sp);
            return false;
        }

        GodhoodState next = st.withCharges(st.regenCharges() - 1)
                .withRegenEnd(now + REGEN_DURATION_TICKS);
        sp.setData(DTAttachments.GODHOOD_STATE.get(), next);

        sp.setHealth(1.0F);
        sp.setRemainingFireTicks(0);
        sp.setAirSupply(sp.getMaxAirSupply());
        sp.hurtMarked = true;
        sp.setInvulnerable(true);

        broadcastBurnupStart(sp);
        if (sp instanceof ServerPlayer serverSp) sendChargeSync(serverSp, next);
        return true;
    }

    // ============================================================================
    //  Mechanic 2 invincibility + Mechanic 3 heal block
    // ============================================================================

    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (p.level().isClientSide) return;
        if (isInvincibleFromRegen(p)) event.setCanceled(true);
    }

    /** True while the god is inside the 5-second burn-up window. */
    public static boolean isInvincibleFromRegen(Player sp) {
        if (!hasGodhood(sp)) return false;
        GodhoodState st = sp.getData(DTAttachments.GODHOOD_STATE.get());
        return st.isRegenerating(sp.level().getGameTime());
    }

    public static void onHeal(LivingHealEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (p.level().isClientSide) return;
        if (shouldBlockHeal(p)) event.setCanceled(true);
    }

    /**
     * True while the god is inside the vulnerability window. Direct {@code setHealth} bypasses;
     * that's intentional so window-end doesn't shove them back to max.
     */
    public static boolean shouldBlockHeal(Player sp) {
        if (!hasGodhood(sp)) return false;
        GodhoodState st = sp.getData(DTAttachments.GODHOOD_STATE.get());
        return st.isVulnerable(sp.level().getGameTime());
    }

    // ============================================================================
    //  Per-tick countdown driver
    // ============================================================================

    public static void onEntityTick(EntityTickEvent.Post event) {
        if (event.getEntity().level().isClientSide) return;
        if (!(event.getEntity() instanceof LivingEntity le)) return;
        if (!hasGodhood(le)) return;

        // Player-only state transitions (regen + vulnerability timers).
        if (le instanceof Player p) tickGodhood(p);

        // Whisper broadcast. Interval-gated so we run at ~1 broadcast per config interval
        // per emitter; adding entity id spreads the load across ticks rather than lumping
        // every god into the same tick.
        int interval = DTConfig.SPEC.isLoaded() ? DTConfig.WHISPER_TICK_INTERVAL.get() : 40;
        if ((le.tickCount + le.getId()) % interval == 0) {
            emitWhispers(le);
        }
    }

    /**
     * One-tick advance for a god's timers. Splits into two independent phases: burn-up (may
     * complete this tick, firing the blast and entering vulnerability) and vulnerability (may
     * complete this tick, stripping the modifier + Weakness). Between transitions, refreshes
     * the Weakness effect and emits idle particles/HUD.
     */
    public static void tickGodhood(Player sp) {
        long now = sp.level().getGameTime();
        GodhoodState st = sp.getData(DTAttachments.GODHOOD_STATE.get());

        if (st.regenEndGameTime() > 0) {
            if (now >= st.regenEndGameTime()) {
                completeRegeneration(sp);
                st = sp.getData(DTAttachments.GODHOOD_STATE.get());
            } else {
                // Pin the body server-side. Client-side MovementInputUpdateEvent zeroes the
                // walk inputs so the position packets the client sends are also zero-motion.
                sp.setDeltaMovement(0.0, 0.0, 0.0);
                sp.hasImpulse = false;
                sp.fallDistance = 0F;
            }
        }

        if (st.vulnerabilityEndGameTime() > 0) {
            if (now >= st.vulnerabilityEndGameTime()) {
                endVulnerability(sp);
                return;
            }
            if (sp.tickCount % WEAKNESS_REFRESH_INTERVAL_TICKS == 0) {
                sp.addEffect(new MobEffectInstance(MobEffects.WEAKNESS,
                        WEAKNESS_EFFECT_DURATION_TICKS, 1, false, true, true));
            }
            if (sp.tickCount % 5 == 0 && sp.level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                        sp.getX(), sp.getY() + 0.1, sp.getZ(),
                        2, 0.25, 0.05, 0.25, 0.01);
            }
            if (sp.tickCount % 20 == 0) {
                long secondsLeft = Math.max(0, (st.vulnerabilityEndGameTime() - now) / 20);
                sendActionBar(sp, Component.translatable("dynetech.godhood.vulnerable", secondsLeft)
                        .withStyle(ChatFormatting.RED));
            }
        }
    }

    // ============================================================================
    //  Regeneration completion
    // ============================================================================

    /**
     * Fires the blast, restores the body, and enters vulnerability. Order is intentional:
     * the blast reads the god's live position BEFORE we re-max HP, so no self-hit is possible
     * even under weird modded reflection.
     */
    public static void completeRegeneration(Player sp) {
        if (!(sp.level() instanceof ServerLevel sl)) return;

        detonateBlast(sp, sl);

        sp.setHealth(sp.getMaxHealth());
        sp.setInvulnerable(false);
        sp.setRemainingFireTicks(0);
        sp.removeAllEffects();

        long now = sl.getGameTime();
        applyVulnerabilityModifier(sp);
        sp.addEffect(new MobEffectInstance(MobEffects.WEAKNESS,
                WEAKNESS_EFFECT_DURATION_TICKS, 1, false, true, true));

        GodhoodState st = sp.getData(DTAttachments.GODHOOD_STATE.get());
        GodhoodState next = st.withRegenEnd(-1L)
                .withVulnerabilityEnd(now + VULNERABILITY_DURATION_TICKS);
        sp.setData(DTAttachments.GODHOOD_STATE.get(), next);

        sp.setHealth(Math.min(sp.getHealth(), sp.getMaxHealth()));
    }

    /**
     * Instant-lethal within 5 blocks, linear falloff to 0 at 15. Gods take half. Uses
     * {@link #BLAST_DAMAGE_KEY} so kills here grant no essence and bypass armor/shield/effects.
     */
    private static void detonateBlast(Player god, ServerLevel sl) {
        Holder<DamageType> holder;
        try {
            holder = sl.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(BLAST_DAMAGE_KEY);
        } catch (IllegalStateException e) {
            return;
        }
        DamageSource src = new DamageSource(holder, god, god);

        AABB box = new AABB(
                god.getX() - BLAST_OUTER_RADIUS, god.getY() - BLAST_OUTER_RADIUS, god.getZ() - BLAST_OUTER_RADIUS,
                god.getX() + BLAST_OUTER_RADIUS, god.getY() + BLAST_OUTER_RADIUS, god.getZ() + BLAST_OUTER_RADIUS);
        List<LivingEntity> targets = sl.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != god && e.isAlive());
        for (LivingEntity target : targets) {
            double d = target.distanceTo(god);
            if (d > BLAST_OUTER_RADIUS) continue;
            float dmg = computeBlastDamage(d);
            if (hasGodhood(target)) dmg *= 0.5F;
            if (dmg > 0) target.hurt(src, dmg);
        }

        // Sync the shockwave ring visuals with the actual damage tick. Sent AFTER hurt() so
        // any tracker who lost sight of the god mid-tick still gets the ring at the same
        // moment they see the health bar restore.
        if (god instanceof ServerPlayer serverGod && serverGod.connection != null) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(serverGod,
                    new DTPayloads.GodhoodDetonation(serverGod.getId(),
                            (float) BLAST_INNER_RADIUS, (float) BLAST_OUTER_RADIUS));
        }
    }

    /** Exposed for tests: damage as a function of distance from the blast center. */
    public static float computeBlastDamage(double distance) {
        if (distance <= BLAST_INNER_RADIUS) return BLAST_INNER_DAMAGE;
        if (distance >= BLAST_OUTER_RADIUS) return 0.0F;
        float t = (float) ((BLAST_OUTER_RADIUS - distance) / (BLAST_OUTER_RADIUS - BLAST_INNER_RADIUS));
        return BLAST_INNER_DAMAGE * t;
    }

    public static void endVulnerability(Player sp) {
        removeVulnerabilityModifier(sp);
        sp.removeEffect(MobEffects.WEAKNESS);
        GodhoodState st = sp.getData(DTAttachments.GODHOOD_STATE.get());
        sp.setData(DTAttachments.GODHOOD_STATE.get(), st.withVulnerabilityEnd(-1L));
        sendActionBar(sp, Component.empty());
    }

    private static void applyVulnerabilityModifier(Player sp) {
        AttributeInstance inst = sp.getAttribute(Attributes.MAX_HEALTH);
        if (inst == null) return;
        inst.removeModifier(VULNERABILITY_MODIFIER_ID);
        inst.addTransientModifier(new AttributeModifier(
                VULNERABILITY_MODIFIER_ID, VULNERABILITY_MAX_HP_DELTA, AttributeModifier.Operation.ADD_VALUE));
    }

    private static void removeVulnerabilityModifier(Player sp) {
        AttributeInstance inst = sp.getAttribute(Attributes.MAX_HEALTH);
        if (inst != null) inst.removeModifier(VULNERABILITY_MODIFIER_ID);
    }

    // ============================================================================
    //  Session / dimension resume
    // ============================================================================

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!hasGodhood(sp)) return;
        resumeState(sp);
        sendChargeSync(sp, sp.getData(DTAttachments.GODHOOD_STATE.get()));
    }

    /**
     * On logout, cut in-flight whispers on any listeners nearby the leaving god. The next
     * broadcast tick will restart whispers from any surviving emitters, so the audible cue
     * is "hush, then choir shrinks by one voice."
     */
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!hasGodhood(sp)) return;
        stopWhispers(sp);
    }

    /**
     * On any un-cancelled death of a godhood-carrying entity (player true death, dummy kill),
     * silence in-flight whispers so no ghostly sound lingers at the corpse.
     */
    public static void onGodhoodDeathStopWhispers(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof LivingEntity le)) return;
        if (!hasGodhood(le)) return;
        stopWhispers(le);
    }

    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!hasGodhood(sp)) return;
        resumeState(sp);
    }

    /**
     * Rebuilds any transient state that a save round-trip drops. Three cases:
     * <ul>
     *   <li>Still vulnerable: reapply the max-HP modifier (transient modifiers don't persist).</li>
     *   <li>Vulnerability elapsed offline: clean up whatever's left.</li>
     *   <li>Regen phase pending or elapsed offline: complete it silently (skip the blast, since
     *   any nearby targets aren't the ones we were fighting), heal to full, drop invulnerability,
     *   and open the vulnerability window from now.</li>
     * </ul>
     */
    public static void resumeState(Player sp) {
        long now = sp.level().getGameTime();
        GodhoodState st = sp.getData(DTAttachments.GODHOOD_STATE.get());

        if (st.regenEndGameTime() > 0) {
            finishRegenSilently(sp);
            st = sp.getData(DTAttachments.GODHOOD_STATE.get());
        }

        if (st.isVulnerable(now)) {
            applyVulnerabilityModifier(sp);
            sp.setHealth(Math.min(sp.getHealth(), sp.getMaxHealth()));
        } else if (st.vulnerabilityEndGameTime() > 0) {
            endVulnerability(sp);
        }
    }

    /**
     * Recovery path for a regen phase that ended while the player was offline (or is still
     * pending on login). Does everything {@code completeRegeneration} does except fire the
     * blast, because the blast targets were resolved at logout time and don't exist anymore.
     */
    private static void finishRegenSilently(Player sp) {
        sp.setInvulnerable(false);
        sp.setRemainingFireTicks(0);
        sp.removeAllEffects();
        sp.setHealth(sp.getMaxHealth());

        long now = sp.level().getGameTime();
        applyVulnerabilityModifier(sp);
        sp.addEffect(new MobEffectInstance(MobEffects.WEAKNESS,
                WEAKNESS_EFFECT_DURATION_TICKS, 1, false, true, true));

        GodhoodState st = sp.getData(DTAttachments.GODHOOD_STATE.get());
        sp.setData(DTAttachments.GODHOOD_STATE.get(),
                st.withRegenEnd(-1L).withVulnerabilityEnd(now + VULNERABILITY_DURATION_TICKS));
        sp.setHealth(Math.min(sp.getHealth(), sp.getMaxHealth()));
    }

    // ============================================================================
    //  Helpers
    // ============================================================================

    /**
     * Broadcast one whisper pulse from {@code emitter}. Radius and volume both scale linearly
     * with the emitter's charge count (0 charges = silent). Position is jittered per broadcast
     * so listeners can pick direction from the ear but can't triangulate by standing still.
     * Non-gods never hear anything.
     */
    public static void emitWhispers(LivingEntity emitter) {
        if (!(emitter.level() instanceof ServerLevel sl)) return;

        GodhoodState st = emitter.getData(DTAttachments.GODHOOD_STATE.get());
        int charges = st.regenCharges();
        if (charges <= 0) return;

        double radius = whisperRadius(charges);
        float volume = whisperVolume(charges);
        double jitter = DTConfig.SPEC.isLoaded() ? DTConfig.WHISPER_POSITION_JITTER.get() : 3.0;

        double px = emitter.getX() + (sl.random.nextDouble() * 2.0 - 1.0) * jitter;
        double py = emitter.getY() + (sl.random.nextDouble() * 2.0 - 1.0) * jitter;
        double pz = emitter.getZ() + (sl.random.nextDouble() * 2.0 - 1.0) * jitter;

        AABB box = emitter.getBoundingBox().inflate(radius);
        List<Player> nearby = sl.getEntitiesOfClass(Player.class, box, GodhoodEvents::hasGodhood);
        if (nearby.isEmpty()) return;

        ClientboundSoundPacket packet = new ClientboundSoundPacket(
                DTSounds.GODHOOD_WHISPERS.getDelegate(),
                SoundSource.PLAYERS,
                px, py, pz,
                volume, 1.0F,
                sl.random.nextLong());
        for (Player listener : nearby) {
            if (listener == emitter) continue;
            if (!(listener instanceof ServerPlayer serverListener)) continue;
            if (serverListener.connection == null) continue;
            serverListener.connection.send(packet);
        }
    }

    /**
     * Silences whispers on nearby god listeners. Called when an emitter dies or logs out so
     * in-flight sound packets do not keep playing at the last position. The stop is coarse
     * (kills every whisper on the listener's PLAYERS channel), but the next broadcast tick
     * reseeds whispers from any still-alive emitters, so the audible effect is a brief hush
     * followed by the surviving choir - which is itself a useful cue that someone dropped out.
     */
    public static void stopWhispers(LivingEntity emitter) {
        if (!(emitter.level() instanceof ServerLevel sl)) return;
        double radius = whisperRadius(GodhoodState.MAX_CHARGES);
        AABB box = emitter.getBoundingBox().inflate(radius);
        List<Player> nearby = sl.getEntitiesOfClass(Player.class, box, GodhoodEvents::hasGodhood);
        if (nearby.isEmpty()) return;

        ClientboundStopSoundPacket packet = new ClientboundStopSoundPacket(
                DTSounds.GODHOOD_WHISPERS.getId(), SoundSource.PLAYERS);
        for (Player listener : nearby) {
            if (listener == emitter) continue;
            if (!(listener instanceof ServerPlayer serverListener)) continue;
            if (serverListener.connection == null) continue;
            serverListener.connection.send(packet);
        }
    }

    /**
     * Detection radius for a whisper emitter with {@code charges}. Formula (defaults):
     * {@code base * (0.5 + charges * per_charge)}. At 10 charges with per_charge = 0.1 the
     * radius is 1.5x base. Exposed for tests.
     */
    public static double whisperRadius(int charges) {
        double base = DTConfig.SPEC.isLoaded() ? DTConfig.WHISPER_BASE_RADIUS.get() : 64.0;
        double perCharge = DTConfig.SPEC.isLoaded() ? DTConfig.WHISPER_RADIUS_PER_CHARGE.get() : 0.1;
        return base * (0.5 + charges * perCharge);
    }

    /**
     * Source volume for a whisper emitter with {@code charges}. Linear ramp from 0 to the
     * configured max at 10 charges. Exposed for tests.
     */
    public static float whisperVolume(int charges) {
        double max = DTConfig.SPEC.isLoaded() ? DTConfig.WHISPER_MAX_VOLUME.get() : 6.0;
        return (float) (max * (charges / (double) GodhoodState.MAX_CHARGES));
    }

    /**
     * True when the entity carries a non-denatured Godhood entry. Denatured perks never express
     * per {@link Perk#isCurrentlyActive}, so a denatured Godhood is functionally mortal.
     */
    public static boolean hasGodhood(LivingEntity entity) {
        if (!entity.hasData(DTAttachments.EQUIPPED_PERKS.get())) return false;
        EquippedPerks eq = entity.getData(DTAttachments.EQUIPPED_PERKS.get());
        for (PerkEntry entry : eq.perks()) {
            Perk perk = Perks.get(entry.perkId());
            if (perk instanceof GodhoodPerk && !entry.isDenatured()) return true;
        }
        return false;
    }

    public static void sendChargeSync(ServerPlayer sp, GodhoodState st) {
        if (sp.connection == null) return; // gametest mock players lack a real network layer
        PacketDistributor.sendToPlayer(sp,
                new DTPayloads.GodhoodChargeSync(st.regenCharges(), GodhoodState.MAX_CHARGES));
    }

    private static void broadcastBurnupStart(Player sp) {
        if (!(sp instanceof ServerPlayer serverSp) || serverSp.connection == null) return;
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(serverSp,
                new DTPayloads.GodhoodRegenStart(serverSp.getId(), REGEN_DURATION_TICKS));
        if (serverSp.level() instanceof ServerLevel sl) {
            // regen.ogg runs the full 65-second sequence (burn-up + regen + vulnerability),
            // so we play it once here and don't retrigger anything else along the way.
            // Client-side GodhoodBurnupManager owns all the particle animation from here on.
            sl.playSound(null, serverSp.getX(), serverSp.getY(), serverSp.getZ(),
                    DTSounds.GODHOOD_REGEN.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
        }
    }

    private static void sendTrueDeathMessage(Player sp) {
        if (!(sp instanceof ServerPlayer serverSp) || serverSp.connection == null) return;
        serverSp.sendSystemMessage(Component.translatable("dynetech.godhood.true_death")
                .withStyle(ChatFormatting.DARK_RED));
    }

    private static void sendActionBar(Player sp, Component msg) {
        if (!(sp instanceof ServerPlayer serverSp) || serverSp.connection == null) return;
        serverSp.displayClientMessage(msg, true);
    }
}
