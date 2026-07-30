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
 * Marker perk. {@code PerkEvents.onLivingFall} cancels fall damage and launches the host upward
 * when they land while this is active.
 */
public final class SlimeBouncePerk implements Perk {

    /** Baseline upward velocity added to every bounce, before the per-block-fallen component. */
    public static final float BASE_LAUNCH = 0.4F;
    /** Extra upward velocity per block fallen, scaled by quality. */
    public static final float LAUNCH_PER_BLOCK = 0.10F;
    /** Ceiling so a fall from the sky does not fling the host into the stratosphere. */
    public static final float MAX_LAUNCH = 1.5F;
    /** Fall distances below this ignore the perk entirely and take normal (zero) damage. */
    public static final float MIN_FALL_DISTANCE = 1.5F;

    private final ResourceLocation id;

    public SlimeBouncePerk(ResourceLocation id) { this.id = id; }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() {
        return Component.translatable("dynetech.perk.slime_bounce").withStyle(ChatFormatting.GREEN);
    }
    @Override public int color() { return 0x66CC55; }
    @Override public Set<PerkCondition> allowedConditions() {
        return Set.of(PerkCondition.ALWAYS, PerkCondition.FALLING, PerkCondition.SNEAKING, PerkCondition.LOW_HEALTH);
    }

    @Override public void onEquip(LivingEntity entity, PerkEntry entry) {}
    @Override public void onUnequip(LivingEntity entity, PerkEntry entry) {}
}
