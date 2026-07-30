package com.confect1on.dynetech.gene;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Explosion;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.minecraft.world.phys.Vec3;
import com.confect1on.dynetech.gene.perks.BaneOfUndeadPerk;
import com.confect1on.dynetech.gene.perks.BloodthirstPerk;
import com.confect1on.dynetech.gene.perks.CactusSkinPerk;
import com.confect1on.dynetech.gene.perks.EnderBlinkPerk;
import com.confect1on.dynetech.gene.perks.ExplosiveDeathPerk;
import com.confect1on.dynetech.gene.perks.SlimeBouncePerk;
import com.confect1on.dynetech.gene.perks.VenomTouchPerk;

/**
 * Server-side game-bus listeners that route damage and death events into perk-specific logic
 * so the individual perk classes can stay marker-shaped. All handlers are idempotent no-ops if
 * the involved entities don't have the relevant perks equipped.
 */
public final class PerkEvents {

    private PerkEvents() {}

    /**
     * Fires before damage is applied. Only Bane of Undead lives here - it needs to modify the
     * swing <em>before</em> armor/resistance mitigate it. Cactus Skin and Bloodthirst both read
     * {@link #onPostDamage} so their fractions scale off actual damage dealt.
     */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;

        Entity attacker = event.getSource().getEntity();
        if (!(attacker instanceof LivingEntity le)) return;

        if (victim.getType().is(EntityTypeTags.UNDEAD)) {
            findPerk(le, BaneOfUndeadPerk.class).ifPresent(entry -> {
                Perk perk = Perks.get(entry.perkId());
                if (perk == null || !perk.isCurrentlyActive(le, entry)) return;
                event.setAmount(event.getAmount() * (1.0F + BaneOfUndeadPerk.DAMAGE_BONUS_AT_FULL_QUALITY * entry.quality()));
            });
        }

        // Ender Blink: the victim may teleport away when hit. Damage still applies - this is a
        // chase-breaker, not a save. Skip on zero-amount hits so cactus/thorns feedback loops
        // and other cosmetic damage events don't fire the teleport.
        if (event.getAmount() > 0) {
            findPerk(victim, EnderBlinkPerk.class).ifPresent(entry -> {
                Perk perk = Perks.get(entry.perkId());
                if (perk == null || !perk.isCurrentlyActive(victim, entry)) return;
                float chance = EnderBlinkPerk.CHANCE_AT_FULL_QUALITY * entry.quality();
                if (victim.level().random.nextFloat() >= chance) return;
                tryEnderTeleport(victim, victim.level().random);
            });
        }
    }

    /**
     * Fires after damage has been applied. Bloodthirst heals the attacker and Cactus Skin
     * reflects both scale from {@link LivingDamageEvent.Post#getNewDamage()} - the amount the
     * victim actually took after armor and resistance.
     */
    public static void onPostDamage(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;
        Entity attacker = event.getSource().getEntity();
        if (!(attacker instanceof LivingEntity le)) return;

        // Bloodthirst - attacker heals from actual damage dealt.
        findPerk(le, BloodthirstPerk.class).ifPresent(entry -> {
            Perk perk = Perks.get(entry.perkId());
            if (perk == null || !perk.isCurrentlyActive(le, entry)) return;
            float heal = event.getNewDamage() * BloodthirstPerk.HEAL_FRACTION_AT_FULL_QUALITY * entry.quality();
            if (heal > 0) le.heal(heal);
        });

        // Cactus Skin - victim reflects a fraction of the post-mitigation damage back at a
        // close-range melee attacker.
        findPerk(victim, CactusSkinPerk.class).ifPresent(entry -> {
            Perk perk = Perks.get(entry.perkId());
            if (perk == null || !perk.isCurrentlyActive(victim, entry)) return;
            if (!isCloseRangeMelee(event.getSource())) return;
            float reflect = event.getNewDamage() * CactusSkinPerk.REFLECT_FRACTION_AT_FULL_QUALITY * entry.quality();
            if (reflect <= 0) return;
            DamageSource thorns = victim.damageSources().thorns(victim);
            le.hurt(thorns, reflect);
        });

        // Venom Touch: melee hits from the attacker inflict Poison on the victim. Duration
        // scales with quality; amplifier stays at I so this stacks predictably with other
        // poison sources rather than fighting a higher-amplifier instance for slot ownership.
        findPerk(le, VenomTouchPerk.class).ifPresent(entry -> {
            Perk perk = Perks.get(entry.perkId());
            if (perk == null || !perk.isCurrentlyActive(le, entry)) return;
            if (!isCloseRangeMelee(event.getSource())) return;
            int duration = VenomTouchPerk.BASE_DURATION_TICKS
                    + Math.round(VenomTouchPerk.BONUS_DURATION_TICKS_AT_FULL_QUALITY * entry.quality());
            victim.addEffect(new MobEffectInstance(MobEffects.POISON, duration, 0, false, true), le);
        });
    }

    /**
     * Slime Bounce: cancels fall damage and launches the host back up when they land while the
     * perk is active. Ignores tiny drops so a stubbed toe doesn't count as a bounce, and caps
     * the launch so a fall from build limit doesn't turn into a rocket.
     */
    public static void onLivingFall(LivingFallEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) return;
        if (event.getDistance() < SlimeBouncePerk.MIN_FALL_DISTANCE) return;
        findPerk(entity, SlimeBouncePerk.class).ifPresent(entry -> {
            Perk perk = Perks.get(entry.perkId());
            if (perk == null || !perk.isCurrentlyActive(entity, entry)) return;
            float upward = SlimeBouncePerk.BASE_LAUNCH
                    + event.getDistance() * SlimeBouncePerk.LAUNCH_PER_BLOCK * entry.quality();
            if (upward > SlimeBouncePerk.MAX_LAUNCH) upward = SlimeBouncePerk.MAX_LAUNCH;
            Vec3 delta = entity.getDeltaMovement();
            entity.setDeltaMovement(delta.x, upward, delta.z);
            entity.hasImpulse = true;
            event.setDistance(0.0F);
        });
    }

    /**
     * Fires on any incoming damage that will actually be applied (post-invulnerability filters).
     * Stamps {@link DTAttachments#LAST_DAMAGED_TICK} on the victim so the AbsorptionPerk regen
     * cooldown restarts from the hit tick rather than waiting for the next 20-tick observation.
     * Cheap: single attachment write on entities that get hit, no per-tick cost.
     */
    public static void onDamageStampTimer(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;
        if (event.getAmount() <= 0) return;
        victim.setData(DTAttachments.LAST_DAMAGED_TICK.get(), (long) victim.tickCount);
    }

    /**
     * Fires on death for anything with equipped perks. Explosive Death arms an explosion first,
     * then we wipe the attachment. Order matters: clearing before the ExplosiveDeath dispatch
     * would strip the perk that we just tried to fire.
     */
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) return;

        if (entity.level() instanceof ServerLevel sl) {
            findPerk(entity, ExplosiveDeathPerk.class).ifPresent(entry -> {
                Perk perk = Perks.get(entry.perkId());
                if (perk == null || !perk.isCurrentlyActive(entity, entry)) return;
                float radius = 1.0F + ExplosiveDeathPerk.MAX_EXPLOSION_RADIUS * entry.quality();
                // NONE keeps the blast radius/damage curve but leaves terrain intact. Players
                // asked for this so a defective genome doesn't turn every death into a crater.
                sl.explode(entity, null, null,
                        entity.getX(), entity.getY(), entity.getZ(),
                        radius, false, Level.ExplosionInteraction.NONE);
            });
        }

        // Runs onUnequip on every entry and resets the attachment. For a respawning player the
        // new entity would start empty anyway (attachment is non-copyOnDeath), but this also
        // covers mobs revived via totem or by other mods cancelling the death event downstream.
        PerkLifecycle.clearAllPerks(entity);
    }

    /**
     * Locate the first equipped {@link PerkEntry} whose perk instance matches {@code type}, or
     * empty if not present.
     */
    private static <T extends Perk> java.util.Optional<PerkEntry> findPerk(LivingEntity entity, Class<T> type) {
        EquippedPerks eq = entity.getData(DTAttachments.EQUIPPED_PERKS.get());
        for (PerkEntry entry : eq.perks()) {
            Perk perk = Perks.get(entry.perkId());
            if (type.isInstance(perk)) return java.util.Optional.of(entry);
        }
        return java.util.Optional.empty();
    }

    /** True when the damage source is a direct melee attacker (not an arrow, thorns bounce, etc.). */
    private static boolean isCloseRangeMelee(DamageSource source) {
        Entity direct = source.getDirectEntity();
        if (direct != null && direct != source.getEntity()) return false; // indirect: projectile
        return source.is(DamageTypes.MOB_ATTACK) || source.is(DamageTypes.PLAYER_ATTACK);
    }

    /**
     * Enderman-style random teleport. Picks up to {@link EnderBlinkPerk#TELEPORT_ATTEMPTS} random
     * spots inside a cube of edge {@code 2 * TELEPORT_RADIUS} centered on the host and takes the
     * first one that lands validly. Vanilla {@code randomTeleport} handles the block-safety and
     * collision checks and returns false if the spot is not viable.
     */
    private static void tryEnderTeleport(LivingEntity entity, RandomSource rng) {
        double baseX = entity.getX();
        double baseY = entity.getY();
        double baseZ = entity.getZ();
        for (int attempt = 0; attempt < EnderBlinkPerk.TELEPORT_ATTEMPTS; attempt++) {
            double dx = baseX + (rng.nextDouble() - 0.5) * 2.0 * EnderBlinkPerk.TELEPORT_RADIUS;
            double dy = baseY + (rng.nextDouble() - 0.5) * 2.0 * EnderBlinkPerk.TELEPORT_RADIUS;
            double dz = baseZ + (rng.nextDouble() - 0.5) * 2.0 * EnderBlinkPerk.TELEPORT_RADIUS;
            if (entity.randomTeleport(dx, dy, dz, true)) {
                // Sound from both origin and destination so nearby players hear the disappear
                // and reappear, matching vanilla enderman.
                entity.level().playSound(null, baseX, baseY, baseZ,
                        SoundEvents.ENDERMAN_TELEPORT, entity.getSoundSource(), 1.0F, 1.0F);
                entity.playSound(SoundEvents.ENDERMAN_TELEPORT, 1.0F, 1.0F);
                return;
            }
        }
    }
}
