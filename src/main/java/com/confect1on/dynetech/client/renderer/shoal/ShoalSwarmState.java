package com.confect1on.dynetech.client.renderer.shoal;

import com.confect1on.dynetech.entity.ShoalEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.nio.ByteBuffer;

/**
 * CPU half of the GPU Shoal. The mote physics live in shoal/sim.comp; this class keeps only
 * what genuinely needs the CPU: the anchor trail with its block-avoidance scan (Level access),
 * edge detection on server-driven signals (burst flag, state changes, interest picks), and the
 * per-swarm motion personality rolled from the seed. Each frame it packs everything into the
 * ShoalParams std140 uniform block that both the compute and draw shaders read.
 *
 * <p>All positions in the block are relative to the current anchor so the SSBO holds small
 * floats regardless of where in the world the swarm is. The absolute anchor is passed once,
 * only for sampling the noise field, where millimeter precision does not matter.
 */
public final class ShoalSwarmState {

    /** Trail-buffer length. Older positions further back get lower priority as attractors. */
    public static final int TRAIL_LEN = 32;
    private static final int TRAIL_STRIDE = 1;
    // Entering Hold pops the swarm outward into a spread watch: lobes parked apart, taking
    // in several nearby things at once. The pose holds for 10 to 30 seconds, matching the
    // server's long burst hold, and simply ends early if the server leaves Hold first (a
    // startle's freeze is short because its hold is).
    private static final float STILL_TICKS = 400F;

    // std140: 6 header vec4/ivec4 slots plus two vec4[TRAIL_LEN] arrays.
    public static final int UBO_SIZE = (6 + TRAIL_LEN * 2) * 16;

    public static final int FLAG_BURST = 1;
    public static final int FLAG_STILL = 2;
    public static final int FLAG_FIRST = 4;
    public static final int FLAG_PRIMARY = 8;

    private long rngState;
    private final float churnRate;
    private final float braidRate;
    private final float braidPhase;

    private final double[] trailX = new double[TRAIL_LEN];
    private final double[] trailY = new double[TRAIL_LEN];
    private final double[] trailZ = new double[TRAIL_LEN];
    private final float[] trailAvoidX = new float[TRAIL_LEN];
    private final float[] trailAvoidY = new float[TRAIL_LEN];
    private final float[] trailAvoidZ = new float[TRAIL_LEN];
    private final float[] trailAvoidMag = new float[TRAIL_LEN];
    private final float[] trailClear = new float[TRAIL_LEN];
    private int trailHead;
    private int trailWritten;
    private long lastTrailTick = Long.MIN_VALUE;

    private double lastAnchorX, lastAnchorY, lastAnchorZ;
    private boolean anchorSeeded;
    private float lastUpdateTickTime = Float.NaN;

    // Synced mood pace, eased so the swarm winds up or down instead of switching gears. The
    // sim consumes a tempo-scaled dt and animation clock, so every shape plays unchanged,
    // just slower when tired and faster when spooked.
    private float tempo = 1F;
    private float animClock;

    private byte lastState = -1;
    private boolean sawBurst;
    private float stillUntil = Float.NEGATIVE_INFINITY;
    private int lastInterestId;
    private float exciteUntil = Float.NEGATIVE_INFINITY;
    private int frame;

    // Per-frame outputs, consumed by write().
    private final int seed;
    private double anchorX, anchorY, anchorZ;
    private float deltaX, deltaY, deltaZ;
    private float dt;
    private float now;
    private float interestX, interestY, interestZ;
    private boolean interestActive;
    private float excitement;
    private int flags;
    private byte state;

    public ShoalSwarmState(int seed) {
        this.seed = seed;
        this.rngState = seed * 0x9E3779B97F4A7C15L ^ 0x2545F4914F6CDD1DL;
        this.churnRate = 0.8F + (float) nextUnit() * 0.4F;
        this.braidRate = 0.75F + (float) nextUnit() * 0.5F;
        this.braidPhase = (float) (nextUnit() * Math.PI * 2.0);
        java.util.Arrays.fill(this.trailClear, 4F);
    }

    /** Advances the CPU-side state one frame and stages the uniform block contents. */
    public void update(ShoalEntity entity, Vec3 anchor, float partialTicks) {
        long tick = entity.tickCount;
        float nowTime = tick + partialTicks;
        if (Float.isNaN(lastUpdateTickTime) || nowTime < lastUpdateTickTime) {
            dt = 0F;
        } else {
            dt = Math.min(2F, nowTime - lastUpdateTickTime);
        }
        lastUpdateTickTime = nowTime;
        now = nowTime;
        frame++;
        // The vacuum ignores tiredness; a collapse always runs at full pace.
        float tempoTarget = entity.getShoalState() == ShoalEntity.STATE_COLLAPSE
                ? Math.max(1F, entity.getTempo()) : entity.getTempo();
        tempo += (tempoTarget - tempo) * Math.min(1F, dt * 0.04F);
        animClock += dt * tempo;

        double ax = anchor.x, ay = anchor.y, az = anchor.z;
        flags = 0;
        if (!anchorSeeded) {
            for (int i = 0; i < TRAIL_LEN; i++) {
                trailX[i] = ax; trailY[i] = ay; trailZ[i] = az;
            }
            lastAnchorX = ax; lastAnchorY = ay; lastAnchorZ = az;
            anchorSeeded = true;
            flags |= FLAG_FIRST;
        }
        if (tick - lastTrailTick >= TRAIL_STRIDE) {
            trailHead = (trailHead + 1) % TRAIL_LEN;
            trailX[trailHead] = ax;
            trailY[trailHead] = ay;
            trailZ[trailHead] = az;
            sampleAvoidance(entity, trailHead, ax, ay, az);
            if (trailWritten < TRAIL_LEN) trailWritten++;
            lastTrailTick = tick;
        }

        state = entity.getShoalState();
        boolean collapsing = state == ShoalEntity.STATE_COLLAPSE;

        // During a collapse the interest slot carries the vacuum drain instead: the phase
        // disk still in flight, so the motes chase it rather than the anchor.
        int interestId = entity.getInterestId();
        int focusId = collapsing ? entity.getCollapseFocusId() : interestId;
        interestActive = false;
        if (focusId != 0) {
            Entity focus = entity.level().getEntity(focusId);
            if (focus != null && focus.isAlive()) {
                Vec3 pos = focus.getPosition(partialTicks).add(0.0, focus.getBbHeight() * 0.6, 0.0);
                interestX = (float) (pos.x - ax);
                interestY = (float) (pos.y - ay);
                interestZ = (float) (pos.z - az);
                interestActive = true;
            }
        }
        if (interestId != lastInterestId) {
            if (interestId != 0) {
                // A fresh find gets a brief shimmer: brighter, faster churn, then it settles.
                exciteUntil = now + 25F + (float) nextUnit() * 20F;
            }
            lastInterestId = interestId;
        }

        // The outward pop is a rare startle. The server rolls it and flags both partners, so a
        // bonded pair pops and freezes as one; the client just watches the flag's rising edge.
        boolean burstFlag = entity.isBurstFlagged();
        if (state == ShoalEntity.STATE_HOLD && burstFlag && !sawBurst && lastState != -1) {
            flags |= FLAG_BURST;
            stillUntil = now + STILL_TICKS * (0.5F + (float) nextUnit());
        }
        sawBurst = burstFlag;
        if (state != lastState) {
            if (collapsing && lastState != -1) {
                // The vacuum opens with the same pop before everything gets dragged back in.
                flags |= FLAG_BURST;
            }
            lastState = state;
        }
        if (state == ShoalEntity.STATE_HOLD && now < stillUntil) flags |= FLAG_STILL;
        if (entity.isPrimaryLobe()) flags |= FLAG_PRIMARY;

        float remain = exciteUntil - now;
        excitement = remain <= 0F ? 0F : Math.min(1F, remain / 20F);

        deltaX = (float) (ax - lastAnchorX);
        deltaY = (float) (ay - lastAnchorY);
        deltaZ = (float) (az - lastAnchorZ);
        lastAnchorX = ax; lastAnchorY = ay; lastAnchorZ = az;
        anchorX = ax; anchorY = ay; anchorZ = az;
    }

    /**
     * Serializes the staged frame into the ShoalParams std140 layout. Field order must match
     * the uniform block declared in assets/dynetech/pinwheel/shaders/include/shoal.glsl.
     */
    public void write(ByteBuffer buf) {
        // The sim runs on tempo-scaled time: dt and the animation clock both slow to a crawl
        // when the swarm is tired and race when it is spooked, raw wall time never reaches it.
        buf.putFloat(deltaX).putFloat(deltaY).putFloat(deltaZ).putFloat(dt * tempo);
        buf.putFloat((float) anchorX).putFloat((float) anchorY).putFloat((float) anchorZ).putFloat(animClock);
        buf.putFloat(interestX).putFloat(interestY).putFloat(interestZ)
                .putFloat(interestActive ? 1F : 0F);
        // How far the strands sit from the center line right now. Cycles slowly, so the stream
        // visibly splits apart and braids back together.
        float branchOpen = 0.35F + 0.65F * (0.5F + 0.5F * Mth.sin(animClock * 0.02F * braidRate + braidPhase));
        buf.putFloat(excitement).putFloat(branchOpen).putFloat(animClock * 0.04F * churnRate).putFloat(0F);
        buf.putInt(state).putInt(flags).putInt(seed).putInt(frame);
        buf.putInt(trailHead).putInt(trailWritten).putInt(0).putInt(0);
        for (int i = 0; i < TRAIL_LEN; i++) {
            buf.putFloat((float) (trailX[i] - anchorX));
            buf.putFloat((float) (trailY[i] - anchorY));
            buf.putFloat((float) (trailZ[i] - anchorZ));
            buf.putFloat(trailClear[i]);
        }
        for (int i = 0; i < TRAIL_LEN; i++) {
            buf.putFloat(trailAvoidX[i]).putFloat(trailAvoidZ[i]).putFloat(trailAvoidMag[i])
                    .putFloat(trailAvoidY[i]);
        }
    }

    /** 0..1 shimmer from a fresh interest pick; drives the core tint on the CPU side. */
    public float excitement() {
        return excitement;
    }

    public double anchorX() { return anchorX; }
    public double anchorY() { return anchorY; }
    public double anchorZ() { return anchorZ; }

    /**
     * Checks the blocks around a fresh trail sample and records a shove away from nearby
     * solids, a widen factor, and the clearance to the closest one. A 5x5x3 scan per world
     * tick, so the cost is negligible, but it is what lets the flow visibly thread around
     * tree trunks, fence poles, ceilings, and floors, and it caps the braid amplitude so
     * strands stop swinging through them.
     */
    private void sampleAvoidance(ShoalEntity entity, int idx, double ax, double ay, double az) {
        Level level = entity.level();
        BlockPos base = BlockPos.containing(ax, ay, az);
        float avx = 0F, avy = 0F, avz = 0F;
        int solid = 0;
        float clear = 4F;
        for (int sy = -1; sy <= 1; sy++) {
            for (int sx = -2; sx <= 2; sx++) {
                for (int sz = -2; sz <= 2; sz++) {
                    if (sx == 0 && sy == 0 && sz == 0) continue;
                    BlockPos p = base.offset(sx, sy, sz);
                    if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                        float d = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
                        if (d < 1.8F) {
                            avx -= sx;
                            avy -= sy;
                            avz -= sz;
                            solid++;
                        }
                        if (d < clear) clear = d;
                    }
                }
            }
        }
        float len = (float) Math.sqrt(avx * avx + avy * avy + avz * avz);
        if (len > 1.0E-3F) {
            trailAvoidX[idx] = avx / len * 0.9F;
            trailAvoidY[idx] = avy / len * 0.6F;
            trailAvoidZ[idx] = avz / len * 0.9F;
        } else {
            trailAvoidX[idx] = 0F;
            trailAvoidY[idx] = 0F;
            trailAvoidZ[idx] = 0F;
        }
        trailAvoidMag[idx] = Math.min(1.5F, solid * 0.4F);
        trailClear[idx] = clear;
    }

    /** Splitmix64 step, mapped to a uniform double in [0, 1). */
    private double nextUnit() {
        long z = (rngState += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }
}
