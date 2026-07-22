package com.confect1on.dynetech.gene.perks;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkCondition;
import com.confect1on.dynetech.gene.PerkEntry;

import java.util.Set;

/**
 * Heals the host in direct sunlight if they're well-fed. For non-players (no food data) the
 * hunger requirement is skipped - heals whenever exposed to daylight.
 */
public final class PhotosynthesisPerk implements Perk {

    private static final int TICK_INTERVAL = 40; // 2 seconds
    private static final float HEAL_AMOUNT = 1.0F;
    private static final int PLAYER_FOOD_THRESHOLD = 18;

    private final ResourceLocation id;

    public PhotosynthesisPerk(ResourceLocation id) { this.id = id; }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() {
        return Component.translatable("dynetech.perk.photosynthesis").withStyle(ChatFormatting.GREEN);
    }
    @Override public int color() { return 0x55DD55; }
    @Override public Set<PerkCondition> allowedConditions() {
        // Restrictive conditions that aren't redundant with the perk's own sunlight requirement.
        return Set.of(PerkCondition.ALWAYS, PerkCondition.LOW_HEALTH, PerkCondition.NOT_MOVING, PerkCondition.ALONE);
    }

    @Override public void onEquip(LivingEntity entity, PerkEntry entry) {}
    @Override public void onUnequip(LivingEntity entity, PerkEntry entry) {}

    @Override
    public void tick(LivingEntity entity, PerkEntry entry) {
        if (entity.tickCount % TICK_INTERVAL != 0) return;
        if (!isCurrentlyActive(entity, entry)) return;
        if (entity.getHealth() >= entity.getMaxHealth()) return;
        Level level = entity.level();
        if (!level.isDay() || !level.canSeeSky(entity.blockPosition())) return;
        if (entity instanceof Player p && p.getFoodData().getFoodLevel() < PLAYER_FOOD_THRESHOLD) return;

        entity.heal(HEAL_AMOUNT * entry.quality());
    }
}
