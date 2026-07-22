package com.confect1on.dynetech.gene.perks;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkCondition;
import com.confect1on.dynetech.gene.PerkEntry;

import java.util.Set;

/**
 * Inert perk. Used as the "dud" trait for self-samples - the vial looks full but every
 * isolated perk from it is useless.
 */
public final class NoOpPerk implements Perk {

    private final ResourceLocation id;
    private final Component displayName;
    private final int color;

    public NoOpPerk(ResourceLocation id, Component displayName, int color) {
        this.id = id;
        this.displayName = displayName;
        this.color = color;
    }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() { return displayName; }
    @Override public int color() { return color; }
    @Override public Set<PerkCondition> allowedConditions() { return Set.of(PerkCondition.ALWAYS); }
    @Override public void onEquip(LivingEntity entity, PerkEntry entry) {}
    @Override public void onUnequip(LivingEntity entity, PerkEntry entry) {}
}
