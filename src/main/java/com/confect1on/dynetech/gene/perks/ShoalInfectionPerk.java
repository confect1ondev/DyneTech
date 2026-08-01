package com.confect1on.dynetech.gene.perks;

import com.confect1on.dynetech.block.ShoalSeep;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkCondition;
import com.confect1on.dynetech.gene.PerkEntry;
import net.minecraft.ChatFormatting;
import com.confect1on.dynetech.particle.DTParticles;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Set;

/**
 * Symptomatic Shoal infection. Delegates all block-conversion behavior to {@link ShoalSeep}. The
 * perk itself is a marker plus the tick heartbeat; no direct combat effect, no debuff on the
 * host. Death cures via the shared perk-clear on death path.
 */
public final class ShoalInfectionPerk implements Perk {

    private final ResourceLocation id;

    public ShoalInfectionPerk(ResourceLocation id) {
        this.id = id;
    }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() {
        return Component.translatable("dynetech.perk.shoal_infection").withStyle(ChatFormatting.BLUE);
    }
    @Override public int color() { return 0x3388FF; }
    @Override public Set<PerkCondition> allowedConditions() { return Set.of(PerkCondition.ALWAYS); }

    @Override public void onEquip(LivingEntity entity, PerkEntry entry) {}
    @Override public void onUnequip(LivingEntity entity, PerkEntry entry) {}

    @Override
    public void tick(LivingEntity entity, PerkEntry entry) {
        if (!(entity instanceof Player player)) return;
        if (!(player.level() instanceof ServerLevel server)) return;
        trailMotes(server, player);
        ShoalSeep.tick(server, player);
    }

    // The visible symptom: a faint trail of blue motes shed while the carrier moves. Attachments
    // never sync to clients, so the server sends the particles itself.
    private static void trailMotes(ServerLevel server, Player player) {
        if ((server.getGameTime() & 3L) != 0L) return;
        double dx = player.getX() - player.xo;
        double dz = player.getZ() - player.zo;
        if (dx * dx + dz * dz < 0.0025D) return;
        RandomSource rng = server.random;
        server.sendParticles(DTParticles.SHOAL_MOTE.get(),
                player.getX() + (rng.nextDouble() - 0.5D) * 0.6D,
                player.getY() + 0.2D + rng.nextDouble() * 0.8D,
                player.getZ() + (rng.nextDouble() - 0.5D) * 0.6D,
                1, 0.05D, 0.05D, 0.05D, 0.0D);
    }
}
