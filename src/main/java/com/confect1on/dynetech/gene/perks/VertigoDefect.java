package com.confect1on.dynetech.gene.perks;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkCondition;
import com.confect1on.dynetech.gene.PerkEntry;

import java.util.Set;

/**
 * Triggers Nausea when the host is within a 5×5 XZ radius of a drop taller than 3 blocks. The
 * check runs every {@value #TICK_INTERVAL} ticks to keep the block query cost bounded.
 */
public final class VertigoDefect implements Perk {

    private static final int TICK_INTERVAL = 20;
    private static final int RADIUS = 2; // 5×5 grid = radius 2 around the entity
    private static final int DROP_THRESHOLD = 3;
    private static final int MAX_PROBE = 10;

    private final ResourceLocation id;

    public VertigoDefect(ResourceLocation id) { this.id = id; }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() {
        return Component.translatable("dynetech.perk.vertigo_defect").withStyle(ChatFormatting.LIGHT_PURPLE);
    }
    @Override public boolean isDefect() { return true; }
    @Override public int color() { return 0xAA55AA; }
    @Override public Set<PerkCondition> allowedConditions() { return Set.of(PerkCondition.ALWAYS); }

    @Override public void onEquip(LivingEntity entity, PerkEntry entry) {}
    @Override public void onUnequip(LivingEntity entity, PerkEntry entry) {
        entity.removeEffect(MobEffects.CONFUSION);
    }

    @Override
    public void tick(LivingEntity entity, PerkEntry entry) {
        if (entry.isDenatured()) return;
        if (entity.tickCount % TICK_INTERVAL != 0) return;
        if (!nearLedge(entity)) return;
        // Refresh Nausea a bit longer than the tick interval so it doesn't gap while still near
        // the ledge. Shorter than the mob-effect perks' 300 because Nausea's disorienting effect
        // is intentionally punishing.
        entity.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 80, 0, true, true, true));
    }

    /**
     * Scans the 5×5 XZ column at the entity's foot level. Returns true if any of them drops
     * more than {@link #DROP_THRESHOLD} blocks before hitting a solid surface.
     */
    private static boolean nearLedge(LivingEntity entity) {
        Level level = entity.level();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        int baseY = entity.blockPosition().getY();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                probe.set(entity.blockPosition().getX() + dx, baseY - 1, entity.blockPosition().getZ() + dz);
                int drop = 0;
                // Use blocksMotion() so grass/flowers/water don't count as "ground". This is
                // what vanilla uses for its own fall-height/step logic.
                while (drop < MAX_PROBE) {
                    if (level.getBlockState(probe).blocksMotion()) break;
                    probe.setY(probe.getY() - 1);
                    drop++;
                }
                if (drop > DROP_THRESHOLD) return true;
            }
        }
        return false;
    }
}
