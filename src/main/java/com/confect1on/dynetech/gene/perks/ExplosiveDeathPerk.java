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
 * Marker perk. On death (see {@code PerkEvents.onDeath}) the entity's tile detonates with an
 * explosion sized by the entry's quality.
 */
public final class ExplosiveDeathPerk implements Perk {

    public static final float MAX_EXPLOSION_RADIUS = 4.0F;

    private final ResourceLocation id;

    public ExplosiveDeathPerk(ResourceLocation id) { this.id = id; }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() {
        return Component.translatable("dynetech.perk.explosive_death").withStyle(ChatFormatting.RED);
    }
    @Override public int color() { return 0xEE4444; }
    @Override public Set<PerkCondition> allowedConditions() {
        // Death event fires once - condition gates whether the explosion is armed at that instant.
        return Set.of(PerkCondition.ALWAYS, PerkCondition.NIGHT, PerkCondition.LOW_HEALTH);
    }

    @Override public void onEquip(LivingEntity entity, PerkEntry entry) {}
    @Override public void onUnequip(LivingEntity entity, PerkEntry entry) {}
}
