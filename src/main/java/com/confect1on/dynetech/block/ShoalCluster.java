package com.confect1on.dynetech.block;

import com.confect1on.dynetech.particle.DTParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * Ambient behavior shared by {@link ShoalGrowthBlock} and {@link ShoalBloomBlock}. Detects local
 * clusters of Shoal blocks and drops a subtle haze around them; density scales with count.
 *
 * <p>The haze is aware of people: drifting particles lean toward the nearest player, a close
 * player draws extra haze and the occasional clear chime, and big clusters carry a sparse low
 * resonant hum so a colony is audible before it is visible.
 *
 * <p>Sampled with a stride so a single {@code animateTick} call stays cheap even at max radius.
 * Runs client-side only.
 */
public final class ShoalCluster {

    // Exact count in a radius-4 cube, ~730 lookups per call, which is safe for the small subset
    // of Shoal blocks Minecraft picks for animateTick each frame. Exact matters here: a strided
    // sample sees ~1/8th of the blocks, so a typical spike never crossed the threshold and the
    // whole cluster ambience silently no-opped.
    private static final int SAMPLE_RADIUS = 4;
    private static final int CLUSTER_THRESHOLD = 3;

    // How far the haze notices a player, and the tighter band where the cluster visibly reacts.
    private static final double LEAN_RANGE = 6.0D;
    private static final double PULSE_RANGE = 4.0D;

    // Each shoal block owns a sphere of cloud this big. Neighboring spheres overlap, so the
    // union blankets the whole infected area as one connected cloud that grows with it.
    private static final double CLOUD_RADIUS = 5.0D;

    private ShoalCluster() {}

    /**
     * Called from {@code Block.animateTick} on each Shoal block. Emits haze particles when the
     * block is part of a cluster of at least {@link #CLUSTER_THRESHOLD} Shoal blocks within a
     * roughly 16-block volume.
     */
    public static void animateHaze(Level level, BlockPos pos, RandomSource random) {
        int neighbors = countNearby(level, pos);
        if (neighbors < CLUSTER_THRESHOLD) return;

        double bx = pos.getX() + 0.5D;
        double by = pos.getY() + 0.5D;
        double bz = pos.getZ() + 0.5D;
        Player watched = level.getNearestPlayer(bx, by, bz, LEAN_RANGE, false);
        boolean pulsing = watched != null
                && watched.distanceToSqr(bx, by, bz) < PULSE_RANGE * PULSE_RANGE;

        ambientSounds(level, random, bx, by, bz, neighbors, pulsing);

        // Fill this block's cloud sphere with a few particles per call; the overlap between
        // neighboring spheres does the rest. Two flavors: frozen (velocity zero) and drifting
        // (very slow), roughly a 30/70 split, so the cloud hangs mostly still with lazy motion.
        // A close player agitates it: more particles, far fewer frozen ones, and every drifting
        // one leans their way.
        int cap = Math.min(5, 1 + neighbors / 4);
        int spawnAttempts = 1 + random.nextInt(cap) + (pulsing ? 2 : 0);
        float frozenChance = pulsing ? 0.15F : 0.3F;
        BlockPos.MutableBlockPos sample = new BlockPos.MutableBlockPos();
        for (int i = 0; i < spawnAttempts; i++) {
            double ox = (random.nextDouble() - 0.5D) * 2.0D * CLOUD_RADIUS;
            double oy = (random.nextDouble() - 0.35D) * 1.2D * CLOUD_RADIUS;
            double oz = (random.nextDouble() - 0.5D) * 2.0D * CLOUD_RADIUS;
            if (ox * ox + oy * oy + oz * oz > CLOUD_RADIUS * CLOUD_RADIUS) continue;
            double px = bx + ox;
            double py = by + oy;
            double pz = bz + oz;
            // Open air only, so the cloud wraps the mass instead of clipping into terrain.
            sample.set((int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz));
            if (!level.getBlockState(sample).isAir()) continue;
            double vx;
            double vy;
            double vz;
            if (random.nextFloat() < frozenChance) {
                vx = 0.0D;
                vy = 0.0D;
                vz = 0.0D;
            } else {
                vx = (random.nextDouble() - 0.5D) * 0.008D;
                vy = (random.nextDouble() - 0.2D) * 0.006D;
                vz = (random.nextDouble() - 0.5D) * 0.008D;
                if (watched != null) {
                    double tx = watched.getX() - px;
                    double ty = watched.getY() + watched.getBbHeight() * 0.5D - py;
                    double tz = watched.getZ() - pz;
                    double dist = Math.sqrt(tx * tx + ty * ty + tz * tz);
                    if (dist > 0.01D) {
                        double strength = pulsing ? 0.025D : 0.012D;
                        vx += tx / dist * strength;
                        vy += ty / dist * strength * 0.7D;
                        vz += tz / dist * strength;
                    }
                }
            }
            level.addParticle(DTParticles.SHOAL_HAZE.get(), px, py, pz, vx, vy, vz);
        }
    }

    // Local sounds only, no packets. Probabilities are per animateTick pick, which vanilla runs
    // for a small random subset of blocks near the camera, so even a huge colony stays sparse.
    private static void ambientSounds(Level level, RandomSource random,
                                      double bx, double by, double bz,
                                      int neighbors, boolean pulsing) {
        // The swarm's idle voice: a soft relaxed buzz drifting around the cloud, denser where
        // the biomass is denser.
        float buzzChance = Math.min(0.09F, 0.03F + neighbors * 0.002F);
        if (random.nextFloat() < buzzChance) {
            level.playLocalSound(
                    bx + (random.nextDouble() - 0.5D) * CLOUD_RADIUS * 1.6D,
                    by + random.nextDouble() * 1.5D,
                    bz + (random.nextDouble() - 0.5D) * CLOUD_RADIUS * 1.6D,
                    SoundEvents.BEE_LOOP, SoundSource.BLOCKS,
                    0.08F + random.nextFloat() * 0.06F,
                    0.45F + random.nextFloat() * 0.25F, false);
        }
        float humChance = Math.min(0.02F, 0.004F + neighbors * 0.0007F);
        if (random.nextFloat() < humChance) {
            level.playLocalSound(bx, by, bz,
                    SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS,
                    0.15F + random.nextFloat() * 0.12F,
                    0.35F + random.nextFloat() * 0.25F, false);
        }
        if (pulsing && random.nextFloat() < 0.10F) {
            level.playLocalSound(bx, by, bz,
                    SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS,
                    0.18F + random.nextFloat() * 0.12F,
                    0.55F + random.nextFloat() * 0.3F, false);
        }
    }

    private static int countNearby(Level level, BlockPos center) {
        int count = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -SAMPLE_RADIUS; dx <= SAMPLE_RADIUS; dx++) {
            for (int dy = -SAMPLE_RADIUS; dy <= SAMPLE_RADIUS; dy++) {
                for (int dz = -SAMPLE_RADIUS; dz <= SAMPLE_RADIUS; dz++) {
                    cursor.setWithOffset(center, dx, dy, dz);
                    Block block = level.getBlockState(cursor).getBlock();
                    if (block instanceof ShoalGrowthBlock || block instanceof ShoalBloomBlock) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
