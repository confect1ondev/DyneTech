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
 * Marker perk. {@code PerkEvents.onIncomingDamage} rolls a chance to teleport the victim to a
 * random valid spot within {@link #TELEPORT_RADIUS} blocks when they take damage.
 *
 * <p>The damage still applies. The teleport is a chase-breaker, not a save.
 */
public final class EnderBlinkPerk implements Perk {

    /** Peak teleport chance per hit, at quality 1.0. Halves with quality. */
    public static final float CHANCE_AT_FULL_QUALITY = 0.25F;
    /** Horizontal / vertical search extent around the host for a landing spot. */
    public static final double TELEPORT_RADIUS = 8.0;
    /** How many random landing positions to try before giving up on this hit. */
    public static final int TELEPORT_ATTEMPTS = 16;

    private final ResourceLocation id;

    public EnderBlinkPerk(ResourceLocation id) { this.id = id; }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() {
        return Component.translatable("dynetech.perk.ender_blink").withStyle(ChatFormatting.DARK_PURPLE);
    }
    @Override public int color() { return 0x884499; }
    @Override public Set<PerkCondition> allowedConditions() {
        return Set.of(PerkCondition.ALWAYS, PerkCondition.LOW_HEALTH, PerkCondition.NIGHT, PerkCondition.SPRINTING);
    }

    @Override public void onEquip(LivingEntity entity, PerkEntry entry) {}
    @Override public void onUnequip(LivingEntity entity, PerkEntry entry) {}
}
