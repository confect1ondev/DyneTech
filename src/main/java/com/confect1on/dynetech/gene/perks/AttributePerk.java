package com.confect1on.dynetech.gene.perks;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkCondition;
import com.confect1on.dynetech.gene.PerkEntry;
import com.confect1on.dynetech.gene.PerkGrade;

import java.util.Set;

/**
 * Perk that applies a vanilla {@link AttributeModifier}. Condition-aware: each tick reconciles
 * modifier presence against the entry's condition, so a "SNEAKING"-conditioned Fleetfoot only
 * applies its speed boost while the player is crouched.
 *
 * <p>Magnitude scaling goes through {@link PerkGrade#effectiveMultiplier} so the "denatured
 * does nothing, corrupted uses the floor" contract lives in exactly one place.
 */
public final class AttributePerk implements Perk {

    private final ResourceLocation id;
    private final Component displayName;
    private final Holder<Attribute> attribute;
    private final double amountAtFullQuality;
    private final AttributeModifier.Operation operation;
    private final int color;
    private final Set<PerkCondition> allowedConditions;

    public AttributePerk(ResourceLocation id, Component displayName, Holder<Attribute> attribute,
                         double amountAtFullQuality, AttributeModifier.Operation operation, int color,
                         Set<PerkCondition> allowedConditions) {
        this.id = id;
        this.displayName = displayName;
        this.attribute = attribute;
        this.amountAtFullQuality = amountAtFullQuality;
        this.operation = operation;
        this.color = color;
        this.allowedConditions = allowedConditions;
    }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() { return displayName; }
    @Override public int color() { return color; }
    @Override public Set<PerkCondition> allowedConditions() { return allowedConditions; }

    @Override
    public void onEquip(LivingEntity entity, PerkEntry entry) {
        if (isCurrentlyActive(entity, entry)) applyModifier(entity, entry);
    }

    @Override
    public void onUnequip(LivingEntity entity, PerkEntry entry) {
        AttributeInstance inst = entity.getAttribute(attribute);
        if (inst != null) inst.removeModifier(id);
    }

    @Override
    public void tick(LivingEntity entity, PerkEntry entry) {
        if (entry.effectiveCondition() == PerkCondition.ALWAYS) return;
        AttributeInstance inst = entity.getAttribute(attribute);
        if (inst == null) return;
        boolean active = isCurrentlyActive(entity, entry);
        boolean present = inst.getModifier(id) != null;
        if (active && !present) applyModifier(entity, entry);
        else if (!active && present) inst.removeModifier(id);
    }

    private void applyModifier(LivingEntity entity, PerkEntry entry) {
        AttributeInstance inst = entity.getAttribute(attribute);
        if (inst == null) return;
        inst.removeModifier(id);
        float scale = PerkGrade.effectiveMultiplier(entry.quality());
        if (scale <= 0.0F) return;
        inst.addPermanentModifier(new AttributeModifier(id, amountAtFullQuality * scale, operation));
    }
}
