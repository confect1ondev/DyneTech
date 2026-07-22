package com.confect1on.dynetech.gene.perks;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkCondition;
import com.confect1on.dynetech.gene.PerkEntry;
import com.confect1on.dynetech.pehkui.PehkuiCompat;

import java.util.Set;

/**
 * Pehkui-backed scale change. Condition-aware - when the condition falls false, target scale
 * animates back to 1.0. When it comes true again, animates to the perk's target.
 */
public final class ScalePerk implements Perk {

    private static final int SCALE_TICK_DELAY = 20;

    private final ResourceLocation id;
    private final Component displayName;
    private final float delta;
    private final int color;
    private final Set<PerkCondition> allowedConditions;

    public ScalePerk(ResourceLocation id, Component displayName, float delta, int color,
                     Set<PerkCondition> allowedConditions) {
        this.id = id;
        this.displayName = displayName;
        this.delta = delta;
        this.color = color;
        this.allowedConditions = allowedConditions;
    }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() { return displayName; }
    @Override public int color() { return color; }
    @Override public Set<PerkCondition> allowedConditions() { return allowedConditions; }

    @Override
    public void onEquip(LivingEntity entity, PerkEntry entry) {
        applyScale(entity, entry, isCurrentlyActive(entity, entry));
    }

    @Override
    public void onUnequip(LivingEntity entity, PerkEntry entry) {
        PehkuiCompat.setTargetScale(entity, 1.0F, SCALE_TICK_DELAY);
    }

    @Override
    public void tick(LivingEntity entity, PerkEntry entry) {
        if (entry.effectiveCondition() == PerkCondition.ALWAYS) return;
        if (entity.tickCount % 20 != 0) return; // Reconcile once per second to smooth animation.
        applyScale(entity, entry, isCurrentlyActive(entity, entry));
    }

    private void applyScale(LivingEntity entity, PerkEntry entry, boolean active) {
        float target = active ? Math.max(0.05F, 1.0F + entry.quality() * delta) : 1.0F;
        if (Math.abs(PehkuiCompat.getTargetScale(entity) - target) < 0.01F) return;
        PehkuiCompat.setTargetScale(entity, target, SCALE_TICK_DELAY);
    }
}
