package com.confect1on.dynetech.gene.perks;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkCondition;
import com.confect1on.dynetech.gene.PerkEntry;

import java.util.Set;

/**
 * Strips a specific mob effect whenever it appears on the host. Each instance guards exactly one
 * effect; register a fresh instance per effect you want covered.
 *
 * <p>Runs every tick and short-circuits on {@code hasEffect} when the effect isn't present, so
 * the steady-state cost per equipped immunity is a single hash lookup.
 */
public final class ImmunityPerk implements Perk {

    private final ResourceLocation id;
    private final Component displayName;
    private final int color;
    private final Holder<MobEffect> guarded;
    private final Set<PerkCondition> allowedConditions;

    public ImmunityPerk(ResourceLocation id, Component displayName, int color,
                        Holder<MobEffect> guarded, Set<PerkCondition> allowedConditions) {
        this.id = id;
        this.displayName = displayName;
        this.color = color;
        this.guarded = guarded;
        this.allowedConditions = allowedConditions;
    }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() { return displayName; }
    @Override public int color() { return color; }
    @Override public Set<PerkCondition> allowedConditions() { return allowedConditions; }

    public Holder<MobEffect> guarded() { return guarded; }

    @Override
    public void onEquip(LivingEntity entity, PerkEntry entry) {
        if (isCurrentlyActive(entity, entry)) entity.removeEffect(guarded);
    }

    @Override
    public void onUnequip(LivingEntity entity, PerkEntry entry) {
        // Nothing to reverse: we don't leave any state behind, we just strip incoming effects.
    }

    @Override
    public void tick(LivingEntity entity, PerkEntry entry) {
        if (!isCurrentlyActive(entity, entry)) return;
        if (entity.hasEffect(guarded)) entity.removeEffect(guarded);
    }
}
