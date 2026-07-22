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
 * Marker perk. {@code PerkEvents.onIncomingDamage} multiplies damage dealt to undead when the
 * attacker has this perk equipped.
 */
public final class BaneOfUndeadPerk implements Perk {

    public static final float DAMAGE_BONUS_AT_FULL_QUALITY = 0.75F;

    private final ResourceLocation id;

    public BaneOfUndeadPerk(ResourceLocation id) { this.id = id; }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() {
        return Component.translatable("dynetech.perk.bane_of_undead").withStyle(ChatFormatting.GOLD);
    }
    @Override public int color() { return 0xFFCC44; }
    @Override public Set<PerkCondition> allowedConditions() {
        return Set.of(PerkCondition.ALWAYS, PerkCondition.DAY, PerkCondition.SPRINTING, PerkCondition.HIGH_HEALTH);
    }

    @Override public void onEquip(LivingEntity entity, PerkEntry entry) {}
    @Override public void onUnequip(LivingEntity entity, PerkEntry entry) {}
}
