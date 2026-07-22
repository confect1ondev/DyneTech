package com.confect1on.dynetech.gene.perks;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkCondition;
import com.confect1on.dynetech.gene.PerkEntry;

import java.util.Set;

/**
 * Marker perk. Actual logic lives in {@code PerkEvents.onIncomingDamage} which heals the
 * attacker for a fraction of dealt damage when they have this perk equipped.
 */
public final class BloodthirstPerk implements Perk {

    /** Fraction of dealt damage that heals the attacker at quality 1.0. */
    public static final float HEAL_FRACTION_AT_FULL_QUALITY = 0.35F;

    private final ResourceLocation id;

    public BloodthirstPerk(ResourceLocation id) { this.id = id; }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() {
        return Component.translatable("dynetech.perk.bloodthirst").withStyle(ChatFormatting.DARK_RED);
    }
    @Override public int color() { return 0xAA0022; }
    @Override public Set<PerkCondition> allowedConditions() {
        return Set.of(PerkCondition.ALWAYS, PerkCondition.LOW_HEALTH, PerkCondition.NIGHT, PerkCondition.SPRINTING);
    }

    @Override public void onEquip(LivingEntity entity, PerkEntry entry) {}
    @Override public void onUnequip(LivingEntity entity, PerkEntry entry) {}
}
