package com.confect1on.dynetech.gene.perks;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import com.confect1on.dynetech.gene.DTAttachments;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkCondition;
import com.confect1on.dynetech.gene.PerkEntry;

import java.util.Set;

/**
 * Absorption that regenerates. Vanilla Absorption is one-shot: once the extra hearts are gone,
 * they stay gone. This perk restores the effect once the entity has been at full HP for the
 * configured cooldown - resulting in a "reserve pool" that recharges out of combat.
 *
 * <p>Cooldown timing uses a {@link DTAttachments#LAST_DAMAGED_TICK} that resets whenever the
 * entity dips below max health, and increments implicitly via the tick loop.
 */
public final class AbsorptionPerk implements Perk {

    private static final int REGEN_DELAY_TICKS = 600; // 30 seconds
    private static final int APPLIED_DURATION_TICKS = 24000; // ~20 minutes; only expires via damage
    private static final int TICK_INTERVAL = 20;

    private final ResourceLocation id;
    private final int color;
    private final int maxAmplifier;
    private final Set<PerkCondition> allowedConditions;

    public AbsorptionPerk(ResourceLocation id, int color, int maxAmplifier,
                          Set<PerkCondition> allowedConditions) {
        this.id = id;
        this.color = color;
        this.maxAmplifier = maxAmplifier;
        this.allowedConditions = allowedConditions;
    }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() {
        return Component.translatable("dynetech.perk.absorption").withStyle(ChatFormatting.GOLD);
    }
    @Override public int color() { return color; }
    @Override public Set<PerkCondition> allowedConditions() { return allowedConditions; }

    @Override
    public void onEquip(LivingEntity entity, PerkEntry entry) {
        if (isCurrentlyActive(entity, entry)) applyEffect(entity, entry);
    }

    @Override
    public void onUnequip(LivingEntity entity, PerkEntry entry) {
        entity.removeEffect(MobEffects.ABSORPTION);
    }

    @Override
    public void tick(LivingEntity entity, PerkEntry entry) {
        if (entity.tickCount % TICK_INTERVAL != 0) return;
        if (!isCurrentlyActive(entity, entry)) return;
        if (entity.hasEffect(MobEffects.ABSORPTION)) return; // already active
        if (entity.getHealth() < entity.getMaxHealth()) return;

        // LAST_DAMAGED_TICK is stamped by PerkEvents.onDamageStampTimer on every real hit. If
        // it's still at the default -1 the entity hasn't been hit yet this life - treat that as
        // "cooldown elapsed" so freshly-equipped absorption grants immediately.
        long lastDamaged = entity.getData(DTAttachments.LAST_DAMAGED_TICK.get());
        if (lastDamaged >= 0 && entity.tickCount - lastDamaged < REGEN_DELAY_TICKS) return;
        applyEffect(entity, entry);
    }

    private void applyEffect(LivingEntity entity, PerkEntry entry) {
        int amp = (int) Math.floor(entry.quality() * (maxAmplifier + 1));
        if (amp > maxAmplifier) amp = maxAmplifier;
        entity.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, APPLIED_DURATION_TICKS, amp, true, true, true));
    }
}
