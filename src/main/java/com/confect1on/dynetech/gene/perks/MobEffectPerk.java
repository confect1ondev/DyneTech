package com.confect1on.dynetech.gene.perks;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkCondition;
import com.confect1on.dynetech.gene.PerkEntry;

import java.util.Set;

/**
 * Reapplies a {@link MobEffectInstance} on a fixed cadence. Condition-aware via {@code tick}:
 * when the condition falls false, we skip the refresh and the effect naturally expires.
 */
public final class MobEffectPerk implements Perk {

    // Duration must stay above vanilla Night Vision's ~200-tick flash cutoff; 300-tick apply
    // refreshed every 60 keeps a >=240 floor.
    private static final int REFRESH_INTERVAL_TICKS = 60;
    private static final int APPLIED_DURATION_TICKS = 300;

    private final ResourceLocation id;
    private final Component displayName;
    private final Holder<MobEffect> effect;
    private final int maxAmplifier;
    private final boolean defect;
    private final int color;
    private final Set<PerkCondition> allowedConditions;

    public MobEffectPerk(ResourceLocation id, Component displayName, Holder<MobEffect> effect,
                         int maxAmplifier, boolean defect, int color, Set<PerkCondition> allowedConditions) {
        this.id = id;
        this.displayName = displayName;
        this.effect = effect;
        this.maxAmplifier = maxAmplifier;
        this.defect = defect;
        this.color = color;
        this.allowedConditions = allowedConditions;
    }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() { return displayName; }
    @Override public int color() { return color; }
    @Override public boolean isDefect() { return defect; }
    @Override public Set<PerkCondition> allowedConditions() { return allowedConditions; }

    @Override
    public void onEquip(LivingEntity entity, PerkEntry entry) {
        if (isCurrentlyActive(entity, entry)) applyEffect(entity, entry);
    }

    @Override
    public void onUnequip(LivingEntity entity, PerkEntry entry) { entity.removeEffect(effect); }

    @Override
    public void tick(LivingEntity entity, PerkEntry entry) {
        if (entity.tickCount % REFRESH_INTERVAL_TICKS != 0) return;
        if (!isCurrentlyActive(entity, entry)) return;
        applyEffect(entity, entry);
    }

    private void applyEffect(LivingEntity entity, PerkEntry entry) {
        int amp = (int) Math.floor(entry.quality() * (maxAmplifier + 1));
        if (amp > maxAmplifier) amp = maxAmplifier;
        entity.addEffect(new MobEffectInstance(effect, APPLIED_DURATION_TICKS, amp, true, true, true));
    }
}
