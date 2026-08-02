package com.confect1on.dynetech.gene;

import com.confect1on.dynetech.block.ShoalBloomBlock;
import com.confect1on.dynetech.block.ShoalGrowthBlock;
import com.confect1on.dynetech.particle.DTParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Optional;

/**
 * Contact-based Shoal transmission. Two entry points:
 *
 * <ul>
 *   <li>{@link #tryInfectByContact} - a non-infected player touched a Shoal block. Called from
 *   {@code stepOn} on shoal_bloom and shoal_growth.</li>
 *   <li>{@link #tickProximity} - each server tick a symptomatic carrier runs, they check for
 *   non-infected players within a small radius and infect them. Called from
 *   {@code ShoalInfectionPerk.tick}.</li>
 * </ul>
 *
 * <p>Both routes share {@link #infect} so the same visual signature (mote flutter into the
 * victim + a low chime) plays no matter which path triggered.
 */
public final class ShoalContact {

    // Player-to-player radius, in blocks. Kept tight so the carrier has to actually crowd a
    // healthy player to spread; walking past should be safe.
    private static final double PROXIMITY_RADIUS = 3.0D;

    // Only run the proximity scan periodically so a lingering carrier doesn't burn cycles every
    // tick. Ten ticks (half a second) is fast enough that the flutter reads as immediate.
    private static final long PROXIMITY_INTERVAL = 10L;

    // Global per-player block-contact sweep. Catches side/bottom touches that stepOn misses
    // (walking into a spire, standing under an overhang, etc.). Ten-tick stride matches the
    // proximity scan so the same clock owns all contact routes.
    private static final long BLOCK_CONTACT_INTERVAL = 10L;
    private static final double BLOCK_CONTACT_INFLATE = 0.35D;

    private ShoalContact() {}

    public static void tryInfectByContact(ServerLevel server, Player player) {
        if (player.isSpectator() || player.isCreative()) return;
        infect(server, player);
    }

    /**
     * Runs every server tick per player from the global entity-tick hook. Cheap: only scans the
     * player's inflated bounding box on the 10-tick clock, and returns immediately if the player
     * is already carrying either shoal perk.
     */
    public static void tickBlockContact(ServerLevel server, Player player) {
        if ((server.getGameTime() % BLOCK_CONTACT_INTERVAL) != 0L) return;
        if (player.isSpectator() || player.isCreative()) return;
        EquippedPerks eq = player.getData(DTAttachments.EQUIPPED_PERKS.get());
        if (eq.has(Perks.SHOAL_INCUBATION.getId()) || eq.has(Perks.SHOAL_INFECTION.getId())) return;

        AABB box = player.getBoundingBox().inflate(BLOCK_CONTACT_INFLATE);
        int minX = (int) Math.floor(box.minX);
        int minY = (int) Math.floor(box.minY);
        int minZ = (int) Math.floor(box.minZ);
        int maxX = (int) Math.floor(box.maxX);
        int maxY = (int) Math.floor(box.maxY);
        int maxZ = (int) Math.floor(box.maxZ);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    Block block = server.getBlockState(cursor).getBlock();
                    if (block instanceof ShoalGrowthBlock || block instanceof ShoalBloomBlock) {
                        infect(server, player);
                        return;
                    }
                }
            }
        }
    }

    public static void tickProximity(ServerLevel server, Player carrier) {
        if ((server.getGameTime() % PROXIMITY_INTERVAL) != 0L) return;
        AABB box = carrier.getBoundingBox().inflate(PROXIMITY_RADIUS);
        List<Player> nearby = server.getEntitiesOfClass(Player.class, box,
                p -> p != carrier && !p.isSpectator() && !p.isCreative());
        for (Player p : nearby) {
            if (carrier.distanceToSqr(p) > PROXIMITY_RADIUS * PROXIMITY_RADIUS) continue;
            infect(server, p);
        }
    }

    private static void infect(ServerLevel server, Player player) {
        EquippedPerks eq = player.getData(DTAttachments.EQUIPPED_PERKS.get());
        if (eq.has(Perks.SHOAL_INCUBATION.getId()) || eq.has(Perks.SHOAL_INFECTION.getId())) {
            // Already infected: nothing to do. Extra flutter would spam near the carrier.
            return;
        }
        PerkEntry entry = new PerkEntry(Perks.SHOAL_INCUBATION.getId(), 1.0F,
                Optional.empty(), Optional.empty());
        EquippedPerks updated = eq.add(entry);
        player.setData(DTAttachments.EQUIPPED_PERKS.get(), updated);
        Perks.SHOAL_INCUBATION.get().onEquip(player, entry);
        spawnFlutter(server, player);
        double px = player.getX();
        double py = player.getY() + player.getBbHeight() * 0.5D;
        double pz = player.getZ();
        // Subtle chime rather than the full ShoalEntity resonate. Contact/proximity infection is
        // less dramatic than a full-cloud attack, so the audio matches.
        server.playSound(null, px, py, pz,
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 0.35F, 0.7F);
        if (player instanceof ServerPlayer sp) {
            // No hud message on the incubation stage on purpose: the perk is silent by design.
            // The mote flutter and the chime are the whole tell.
            server.playSound(sp, sp.blockPosition(),
                    SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 0.15F, 0.6F);
        }
    }

    /**
     * A subtle inward mote flutter. Motes spawn on a small sphere around the target and drift
     * toward its chest so the effect reads as being drawn into the victim.
     */
    private static void spawnFlutter(ServerLevel server, Player victim) {
        RandomSource rng = server.random;
        double cx = victim.getX();
        double cy = victim.getY() + victim.getBbHeight() * 0.55D;
        double cz = victim.getZ();
        int motes = 6 + rng.nextInt(4);
        for (int i = 0; i < motes; i++) {
            double ox = (rng.nextDouble() - 0.5D) * 1.6D;
            double oy = (rng.nextDouble() - 0.5D) * 1.4D;
            double oz = (rng.nextDouble() - 0.5D) * 1.6D;
            double dist = Math.sqrt(ox * ox + oy * oy + oz * oz);
            if (dist < 0.01D) continue;
            double invDist = 1.0D / dist;
            double px = cx + ox;
            double py = cy + oy;
            double pz = cz + oz;
            // Velocity points from the spawn position back toward the chest.
            double vx = -ox * invDist * 0.10D;
            double vy = -oy * invDist * 0.10D;
            double vz = -oz * invDist * 0.10D;
            server.sendParticles(DTParticles.SHOAL_MOTE.get(),
                    px, py, pz, 0, vx, vy, vz, 1.0D);
        }
    }
}
