package com.confect1on.dynetech.gene.perks;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkCondition;
import com.confect1on.dynetech.gene.PerkEntry;

import java.util.Set;

/**
 * Under a thunderstorm and exposed to open sky, has a high chance of drawing a real lightning
 * bolt every few seconds. Rolls are silent otherwise - no ambient sparks, no clear-weather
 * damage - so the defect only announces itself when the sky already looks dangerous.
 */
public final class StaticDefect implements Perk {

    private static final int TICK_INTERVAL = 100;              // check every 5s
    private static final float STRIKE_CHANCE = 0.55F;          // per-check chance at max quality

    private final ResourceLocation id;

    public StaticDefect(ResourceLocation id) { this.id = id; }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() {
        return Component.translatable("dynetech.perk.static_defect").withStyle(ChatFormatting.BLUE);
    }
    @Override public boolean isDefect() { return true; }
    @Override public int color() { return 0x66CCFF; }
    @Override public Set<PerkCondition> allowedConditions() { return Set.of(PerkCondition.ALWAYS); }

    @Override public void onEquip(LivingEntity entity, PerkEntry entry) {}
    @Override public void onUnequip(LivingEntity entity, PerkEntry entry) {}

    @Override
    public void tick(LivingEntity entity, PerkEntry entry) {
        if (entry.isDenatured()) return;
        if (entity.tickCount % TICK_INTERVAL != 0) return;
        if (!(entity.level() instanceof ServerLevel sl)) return;
        if (!sl.isThundering()) return;
        if (!sl.canSeeSky(entity.blockPosition())) return;
        if (sl.random.nextFloat() > STRIKE_CHANCE * entry.quality()) return;

        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(sl);
        if (bolt == null) return;
        Vec3 pos = entity.position();
        bolt.moveTo(pos.x, pos.y, pos.z);
        sl.addFreshEntity(bolt);
    }
}
