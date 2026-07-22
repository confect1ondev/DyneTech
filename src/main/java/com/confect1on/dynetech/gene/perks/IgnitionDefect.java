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
 * Sets its host on fire briefly at pseudo-random intervals. Higher quality -> more frequent.
 * Defects always express regardless of condition - the whole point is that they're punishment.
 */
public final class IgnitionDefect implements Perk {

    private static final int BASE_INTERVAL_TICKS = 400;

    private final ResourceLocation id;

    public IgnitionDefect(ResourceLocation id) { this.id = id; }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() {
        return Component.translatable("dynetech.perk.ignition_defect").withStyle(ChatFormatting.RED);
    }
    @Override public boolean isDefect() { return true; }
    @Override public int color() { return 0xFF6633; }
    @Override public Set<PerkCondition> allowedConditions() { return Set.of(PerkCondition.ALWAYS); }

    @Override public void onEquip(LivingEntity entity, PerkEntry entry) {}
    @Override public void onUnequip(LivingEntity entity, PerkEntry entry) { entity.clearFire(); }

    @Override
    public void tick(LivingEntity entity, PerkEntry entry) {
        if (entry.isDenatured()) return;
        int interval = Math.max(60, (int) (BASE_INTERVAL_TICKS * (1.0F - entry.quality() * 0.75F)));
        if (entity.tickCount == 0 || entity.tickCount % interval != 0) return;
        entity.igniteForSeconds(3);
    }
}
