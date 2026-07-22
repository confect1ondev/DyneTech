package com.confect1on.dynetech.gene.perks;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkCondition;
import com.confect1on.dynetech.gene.PerkEntry;

import java.util.List;
import java.util.Set;

/** Broadcasts the host's position to nearby hostile mobs, retargeting them at the host. */
public final class ScreamerDefect implements Perk {

    private static final int TICK_INTERVAL = 200;
    private static final double AGGRO_RADIUS = 24.0;

    private final ResourceLocation id;

    public ScreamerDefect(ResourceLocation id) { this.id = id; }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() {
        return Component.translatable("dynetech.perk.screamer_defect").withStyle(ChatFormatting.DARK_RED);
    }
    @Override public boolean isDefect() { return true; }
    @Override public int color() { return 0xAA2244; }
    @Override public Set<PerkCondition> allowedConditions() { return Set.of(PerkCondition.ALWAYS); }

    @Override public void onEquip(LivingEntity entity, PerkEntry entry) {}
    @Override public void onUnequip(LivingEntity entity, PerkEntry entry) {}

    @Override
    public void tick(LivingEntity entity, PerkEntry entry) {
        if (entry.isDenatured()) return;
        if (entity.tickCount % TICK_INTERVAL != 0) return;
        var box = entity.getBoundingBox().inflate(AGGRO_RADIUS);
        List<Mob> hostiles = entity.level().getEntitiesOfClass(Mob.class, box,
                e -> e instanceof Enemy && e != entity);
        if (hostiles.isEmpty()) return;
        entity.level().playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 0.3F, 1.4F);
        for (Mob mob : hostiles) {
            if (mob.getTarget() == null || !mob.getTarget().isAlive()) mob.setTarget(entity);
        }
    }
}
