package com.confect1on.dynetech.gene.perks;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import com.confect1on.dynetech.effect.DTEffects;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkCondition;
import com.confect1on.dynetech.gene.PerkEntry;

import java.util.Set;

/**
 * Chameleon: applies both vanilla {@link MobEffects#INVISIBILITY} and the {@code dynetech:camouflage}
 * marker effect. The mixin {@code EntityInvisibleToMixin} short-circuits {@code isInvisibleTo}
 * to false for any camouflaged entity, which sends the vanilla {@code LivingEntityRenderer}
 * down its translucent render path - the same one used by "show invisible teammates." The
 * result is that the host renders semi-transparent to every viewer instead of vanishing.
 */
public final class CamouflagePerk implements Perk {

    private static final int REFRESH_INTERVAL_TICKS = 60;
    private static final int APPLIED_DURATION_TICKS = 300;

    private final ResourceLocation id;
    private final int color;
    private final Set<PerkCondition> allowedConditions;

    public CamouflagePerk(ResourceLocation id, int color, Set<PerkCondition> allowedConditions) {
        this.id = id;
        this.color = color;
        this.allowedConditions = allowedConditions;
    }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() {
        return Component.translatable("dynetech.perk.invisibility").withStyle(ChatFormatting.DARK_PURPLE);
    }
    @Override public int color() { return color; }
    @Override public Set<PerkCondition> allowedConditions() { return allowedConditions; }

    @Override
    public void onEquip(LivingEntity entity, PerkEntry entry) {
        if (isCurrentlyActive(entity, entry)) applyEffects(entity);
    }

    @Override
    public void onUnequip(LivingEntity entity, PerkEntry entry) {
        entity.removeEffect(MobEffects.INVISIBILITY);
        entity.removeEffect(DTEffects.CAMOUFLAGE);
    }

    @Override
    public void tick(LivingEntity entity, PerkEntry entry) {
        if (entity.tickCount % REFRESH_INTERVAL_TICKS != 0) return;
        if (!isCurrentlyActive(entity, entry)) return;
        applyEffects(entity);
    }

    private static void applyEffects(LivingEntity entity) {
        // ambient=true, visible=true (particles), showIcon=true. The invisibility does the actual
        // hide; the marker effect is what our mixin looks for to send the renderer down the
        // translucent branch instead of hiding the model entirely.
        entity.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, APPLIED_DURATION_TICKS, 0, true, true, true));
        entity.addEffect(new MobEffectInstance(DTEffects.CAMOUFLAGE, APPLIED_DURATION_TICKS, 0, true, false, false));
    }
}
