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
 * Marker perk. {@code PerkEvents.onIncomingDamage} reflects a fraction of received melee damage
 * back at the attacker when the victim has this equipped.
 */
public final class CactusSkinPerk implements Perk {

    public static final float REFLECT_FRACTION_AT_FULL_QUALITY = 0.4F;

    private final ResourceLocation id;

    public CactusSkinPerk(ResourceLocation id) { this.id = id; }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() {
        return Component.translatable("dynetech.perk.cactus_skin").withStyle(ChatFormatting.GREEN);
    }
    @Override public int color() { return 0x228833; }
    @Override public Set<PerkCondition> allowedConditions() {
        return Set.of(PerkCondition.ALWAYS, PerkCondition.SNEAKING, PerkCondition.NOT_MOVING, PerkCondition.LOW_HEALTH);
    }

    @Override public void onEquip(LivingEntity entity, PerkEntry entry) {}
    @Override public void onUnequip(LivingEntity entity, PerkEntry entry) {}
}
