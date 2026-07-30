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
 * Marker perk for the Godhood gene. All behavior (regeneration on death, essence gain,
 * vulnerability window) lives in {@link com.confect1on.dynetech.gene.GodhoodEvents} and reads
 * the {@link com.confect1on.dynetech.gene.GodhoodState} attachment.
 *
 * <p>{@link PerkCondition#ALWAYS} only: gating cheat-death on a runtime condition would produce
 * silent, unfair deaths (imagine "regenerates only while sneaking"). The gene is either armed
 * or it isn't.
 */
public final class GodhoodPerk implements Perk {

    private final ResourceLocation id;

    public GodhoodPerk(ResourceLocation id) { this.id = id; }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() {
        return Component.translatable("dynetech.perk.godhood").withStyle(ChatFormatting.GOLD);
    }
    @Override public int color() { return 0xFFD760; }
    @Override public Set<PerkCondition> allowedConditions() { return Set.of(PerkCondition.ALWAYS); }

    @Override public void onEquip(LivingEntity entity, PerkEntry entry) {}
    @Override public void onUnequip(LivingEntity entity, PerkEntry entry) {}
}
