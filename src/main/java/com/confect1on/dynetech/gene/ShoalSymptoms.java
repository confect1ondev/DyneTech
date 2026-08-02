package com.confect1on.dynetech.gene;

import com.confect1on.dynetech.block.ShoalBloomBlock;
import com.confect1on.dynetech.block.ShoalGrowthBlock;
import com.confect1on.dynetech.config.DTConfig;
import com.confect1on.dynetech.particle.DTParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;

/**
 * Carrier-side symptom escalation for the symptomatic Shoal stage. Severity ramps from 0 to 1
 * over a few in-game days after incubation matures, and every visible tell scales with it:
 *
 * <ul>
 *   <li>Trail motes shed while moving get denser and wider.</li>
 *   <li>A lingering carrier starts shedding motes standing still.</li>
 *   <li>Faint chimes play off the carrier at random, audible to anyone close.</li>
 *   <li>A mote stream drifts off the carrier's chest toward the nearest biomass, so the
 *   infection reads as pulling its host home.</li>
 * </ul>
 *
 * <p>All of it is cosmetic; the host takes no debuff. The point is that other players can read
 * how far gone a carrier is at a glance.
 */
public final class ShoalSymptoms {

    // Severity climbs from 0 to 1 over this many day-time ticks after incubation matures.
    // Three in-game days: long enough that a fresh carrier is easy to miss, short enough that
    // an established one is unmistakable.
    private static final long SEVERITY_RAMP_TICKS = 72_000L;

    // Carrier-pull scan. Stride-3 walk over a 31-block cube is ~1.3k lookups every two seconds,
    // cheap for something that only runs on symptomatic carriers. Stride can miss lone blocks;
    // that's fine, the pull is meant to point at real biomass, not a stray bloom.
    private static final long PULL_INTERVAL = 40L;
    private static final int PULL_RADIUS = 15;
    private static final int PULL_STRIDE = 3;
    private static final double PULL_MIN_DIST = 5.0D;

    private ShoalSymptoms() {}

    public static void tick(ServerLevel server, Player player) {
        double severity = severity(server, player);
        trailMotes(server, player, severity);
        idleShed(server, player, severity);
        whisper(server, player, severity);
        pull(server, player);
    }

    /**
     * 0 at the moment incubation matures, 1 after {@link #SEVERITY_RAMP_TICKS}. Runs on the
     * day-time clock like incubation itself, so /time add and sleeping advance it too.
     */
    private static double severity(ServerLevel server, Player player) {
        ShoalHostState state = player.getData(DTAttachments.SHOAL_HOST_STATE.get());
        long now = server.getDayTime();
        long incubation = DTConfig.SHOAL_INCUBATION_TICKS.get();
        if (state.incubationStart() < 0L) {
            // Serum-injected infection skipped the incubation stage entirely. Backdate the
            // start so severity ramps from zero instead of sitting unset forever.
            player.setData(DTAttachments.SHOAL_HOST_STATE.get(),
                    state.withIncubationStart(now - incubation));
            return 0.0D;
        }
        long age = now - state.incubationStart() - incubation;
        if (age <= 0L) return 0.0D;
        return Math.min(1.0D, (double) age / SEVERITY_RAMP_TICKS);
    }

    // The moving-carrier trail. Attachments never sync to clients, so the server sends the
    // particles itself. Rate doubles past half severity and a second mote joins near full.
    private static void trailMotes(ServerLevel server, Player player, double severity) {
        long mask = severity >= 0.5D ? 1L : 3L;
        if ((server.getGameTime() & mask) != 0L) return;
        double dx = player.getX() - player.xo;
        double dz = player.getZ() - player.zo;
        if (dx * dx + dz * dz < 0.0025D) return;
        RandomSource rng = server.random;
        int count = severity >= 0.8D ? 2 : 1;
        double spread = 0.6D + severity * 0.4D;
        for (int i = 0; i < count; i++) {
            server.sendParticles(DTParticles.SHOAL_MOTE.get(),
                    player.getX() + (rng.nextDouble() - 0.5D) * spread,
                    player.getY() + 0.2D + rng.nextDouble() * 0.8D,
                    player.getZ() + (rng.nextDouble() - 0.5D) * spread,
                    1, 0.05D, 0.05D, 0.05D, 0.0D);
        }
    }

    // Past a quarter severity, standing still no longer hides the infection: motes rise slowly
    // off the body. Probability scales with severity so the tell fades in rather than snapping.
    private static void idleShed(ServerLevel server, Player player, double severity) {
        if (severity < 0.25D) return;
        if ((server.getGameTime() % 16L) != 0L) return;
        double dx = player.getX() - player.xo;
        double dz = player.getZ() - player.zo;
        if (dx * dx + dz * dz >= 0.0025D) return;
        RandomSource rng = server.random;
        if (rng.nextDouble() > severity) return;
        int count = 1 + rng.nextInt(2);
        for (int i = 0; i < count; i++) {
            server.sendParticles(DTParticles.SHOAL_MOTE.get(),
                    player.getX() + (rng.nextDouble() - 0.5D) * 0.7D,
                    player.getY() + rng.nextDouble() * player.getBbHeight(),
                    player.getZ() + (rng.nextDouble() - 0.5D) * 0.7D,
                    0, 0.0D, 0.03D, 0.0D, 1.0D);
        }
    }

    // Random faint chimes off the carrier. Broadcast quietly rather than sent to the carrier
    // alone: the whole point is that a healthy player standing close can hear something wrong.
    // At full severity this averages one chime every ~12 seconds.
    private static void whisper(ServerLevel server, Player player, double severity) {
        if (severity <= 0.0D) return;
        RandomSource rng = server.random;
        if (rng.nextDouble() >= severity * 0.004D) return;
        server.playSound(null, player.getX(), player.getY() + 1.0D, player.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE,
                0.12F, 0.5F + rng.nextFloat() * 0.3F);
    }

    /**
     * Compass stream: motes leave the carrier's chest and drift toward the nearest biomass, as
     * if the infection is tugging its host home. Suppressed when already standing in it.
     */
    private static void pull(ServerLevel server, Player player) {
        if ((server.getGameTime() % PULL_INTERVAL) != 0L) return;
        BlockPos nearest = findNearestShoal(server, player.blockPosition());
        if (nearest == null) return;
        double srcX = player.getX();
        double srcY = player.getY() + player.getBbHeight() * 0.6D;
        double srcZ = player.getZ();
        double dx = nearest.getX() + 0.5D - srcX;
        double dy = nearest.getY() + 0.5D - srcY;
        double dz = nearest.getZ() + 0.5D - srcZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < PULL_MIN_DIST) return;
        double nx = dx / dist;
        double ny = dy / dist;
        double nz = dz / dist;
        RandomSource rng = server.random;
        int motes = 2 + rng.nextInt(2);
        for (int i = 0; i < motes; i++) {
            double px = srcX + nx * 0.6D + (rng.nextDouble() - 0.5D) * 0.3D;
            double py = srcY + ny * 0.6D + (rng.nextDouble() - 0.5D) * 0.3D;
            double pz = srcZ + nz * 0.6D + (rng.nextDouble() - 0.5D) * 0.3D;
            // count=0 sends the offsets as velocity, so each mote streams toward the biomass.
            server.sendParticles(DTParticles.SHOAL_MOTE.get(),
                    px, py, pz, 0, nx * 0.12D, ny * 0.12D, nz * 0.12D, 1.0D);
        }
    }

    private static BlockPos findNearestShoal(ServerLevel server, BlockPos center) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -PULL_RADIUS; dx <= PULL_RADIUS; dx += PULL_STRIDE) {
            for (int dy = -PULL_RADIUS; dy <= PULL_RADIUS; dy += PULL_STRIDE) {
                for (int dz = -PULL_RADIUS; dz <= PULL_RADIUS; dz += PULL_STRIDE) {
                    cursor.setWithOffset(center, dx, dy, dz);
                    Block block = server.getBlockState(cursor).getBlock();
                    if (!(block instanceof ShoalGrowthBlock) && !(block instanceof ShoalBloomBlock)) {
                        continue;
                    }
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best;
    }
}
