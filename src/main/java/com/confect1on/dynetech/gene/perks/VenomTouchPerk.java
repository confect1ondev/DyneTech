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
 * Marker perk. {@code PerkEvents.onPostDamage} applies Poison to the victim of a close-range
 * melee hit when the attacker has this equipped.
 */
public final class VenomTouchPerk implements Perk {

    /** Poison duration floor in ticks, regardless of quality. */
    public static final int BASE_DURATION_TICKS = 60;
    /** Extra ticks of poison at quality 1.0, on top of {@link #BASE_DURATION_TICKS}. */
    public static final int BONUS_DURATION_TICKS_AT_FULL_QUALITY = 100;

    private final ResourceLocation id;

    public VenomTouchPerk(ResourceLocation id) { this.id = id; }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() {
        return Component.translatable("dynetech.perk.venom_touch").withStyle(ChatFormatting.DARK_GREEN);
    }
    @Override public int color() { return 0x338844; }
    @Override public Set<PerkCondition> allowedConditions() {
        return Set.of(PerkCondition.ALWAYS, PerkCondition.SNEAKING, PerkCondition.LOW_HEALTH, PerkCondition.NIGHT);
    }

    @Override public void onEquip(LivingEntity entity, PerkEntry entry) {}
    @Override public void onUnequip(LivingEntity entity, PerkEntry entry) {}
}
