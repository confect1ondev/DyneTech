package com.confect1on.dynetech.client.renderer;

import com.confect1on.dynetech.entity.ShoalEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side mote simulation for one Shoal. Motes are stored in flat arrays so a full swarm
 * update walks contiguous memory. The swarm is state-aware: HOLD packs motes tightly around
 * the anchor, DRIFT loosens it, FLOW spreads motes along a trail buffer to give the ribbon
 * reading the spec calls for.
 *
 * <p>Coordinates are stored in world space; the renderer converts to entity-local space before
 * writing quads so the buffer transform in {@code EntityRenderer} keeps working normally.
 */
public final class ShoalSwarm {

    /** Trail-buffer length. Older positions further back get lower priority as attractors. */
    private static final int TRAIL_LEN = 32;
    /** How many world ticks between trail-buffer writes. */
    private static final int TRAIL_STRIDE = 1;
    /** Neighbor-spawn radius by state. Motes born far from the anchor grow the cloud footprint. */
    private static final float SPAWN_R_HOLD = 6.0F;
    private static final float SPAWN_R_DRIFT = 6.5F;
    private static final float SPAWN_R_FLOW = 3.1F;
    private static final float SPAWN_R_ORBIT = 3.2F;
    private static final float SPAWN_R_INSPECT = 8.0F;
    // Lifetime in tick units. At 20 tps that's ~3 seconds per mote before respawn.
    private static final float MOTE_LIFETIME = 60F;
    // Entering Hold pops the swarm outward, then it hangs nearly motionless for this long
    // before the home-shell spring picks the motes back up. Long on purpose: the frozen beat
    // is where the Shoal reads as watching, so it should hold well past a quick blink.
    private static final float STILL_TICKS = 70F;

    private final int capacity;
    private long rngState;

    private final float[] px, py, pz;
    private final float[] vx, vy, vz;
    private final float[] age;
    private final float[] life;
    private final float[] size;
    private final float[] home;
    private final float[] slot;
    private final float[] offX, offY, offZ;
    private final byte[] branch;

    private byte lastState = -1;
    private boolean sawBurst;
    private float stillUntil = Float.NEGATIVE_INFINITY;
    private int lastInterestId;
    private float exciteUntil = Float.NEGATIVE_INFINITY;
    // Per-swarm motion personality, rolled from the seed: how fast the internal churn runs and
    // how quickly the braid opens and closes. Twins with identical rhythms read as copies.
    private final float churnRate;
    private final float braidRate;
    private final float braidPhase;

    private final double[] trailX, trailY, trailZ;
    private final float[] trailAvoidX, trailAvoidZ, trailAvoidMag, trailClear;
    private int trailHead;
    private int trailWritten;
    private long lastTrailTick = Long.MIN_VALUE;

    private double lastAnchorX, lastAnchorY, lastAnchorZ;
    private boolean anchorSeeded;
    private float lastUpdateTickTime = Float.NaN;

    public ShoalSwarm(int seed, int capacity) {
        this.rngState = seed * 0x9E3779B97F4A7C15L ^ 0x2545F4914F6CDD1DL;
        this.churnRate = 0.8F + (float) nextUnit() * 0.4F;
        this.braidRate = 0.75F + (float) nextUnit() * 0.5F;
        this.braidPhase = (float) (nextUnit() * Math.PI * 2.0);
        this.capacity = capacity;
        this.px = new float[capacity];
        this.py = new float[capacity];
        this.pz = new float[capacity];
        this.vx = new float[capacity];
        this.vy = new float[capacity];
        this.vz = new float[capacity];
        this.age = new float[capacity];
        this.life = new float[capacity];
        this.size = new float[capacity];
        this.home = new float[capacity];
        this.slot = new float[capacity];
        this.offX = new float[capacity];
        this.offY = new float[capacity];
        this.offZ = new float[capacity];
        this.branch = new byte[capacity];
        this.trailX = new double[TRAIL_LEN];
        this.trailY = new double[TRAIL_LEN];
        this.trailZ = new double[TRAIL_LEN];
        this.trailAvoidX = new float[TRAIL_LEN];
        this.trailAvoidZ = new float[TRAIL_LEN];
        this.trailAvoidMag = new float[TRAIL_LEN];
        this.trailClear = new float[TRAIL_LEN];
        java.util.Arrays.fill(this.trailClear, 4F);
        // Motes are seeded on the first update() once the anchor is known so they start with
        // valid world positions AND staggered ages. Marking every slot as expired here lets the
        // first-update respawn path fill them all in one pass without a second code branch.
        for (int i = 0; i < capacity; i++) age[i] = Float.MAX_VALUE;
    }

    public int capacity() { return capacity; }

    public void update(ShoalEntity entity, Vec3 anchor, float partialTicks) {
        long tick = entity.tickCount;
        // Advance age based on wall-clock ticks (framerate-independent). Without this, motes
        // aged one full unit per rendered frame, so lifetimes stretched with framerate and the
        // whole swarm cycled ~60x faster at 60fps than intended.
        float now = tick + partialTicks;
        float dt;
        if (Float.isNaN(lastUpdateTickTime) || now < lastUpdateTickTime) {
            dt = 0F;
        } else {
            dt = Math.min(2F, now - lastUpdateTickTime);
        }
        lastUpdateTickTime = now;
        double ax = anchor.x, ay = anchor.y, az = anchor.z;
        if (!anchorSeeded) {
            for (int i = 0; i < TRAIL_LEN; i++) {
                trailX[i] = ax; trailY[i] = ay; trailZ[i] = az;
            }
            lastAnchorX = ax; lastAnchorY = ay; lastAnchorZ = az;
            anchorSeeded = true;
            // Seed every mote around the anchor with a random age offset so the swarm isn't
            // in lockstep. Without this, all motes fade in and out on the same cadence and the
            // whole cloud reads as a slow pulse rather than a steady glow.
            for (int i = 0; i < capacity; i++) {
                respawn(i, entity.getShoalState(), ax, ay, az, 0.0, 0.0, 0.0);
                age[i] = (float) nextUnit() * life[i];
            }
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

        byte state = entity.getShoalState();
        boolean collapsing = state == ShoalEntity.STATE_COLLAPSE;

        // Whatever the server says the swarm is studying right now. A quarter of the motes
        // reach toward it as a tendril, so a watcher can tell exactly what caught its eye.
        int interestId = entity.getInterestId();
        Vec3 interestPos = null;
        if (interestId != 0 && !collapsing) {
            net.minecraft.world.entity.Entity focus = entity.level().getEntity(interestId);
            if (focus != null && focus.isAlive()) {
                interestPos = focus.getPosition(partialTicks).add(0.0, focus.getBbHeight() * 0.6, 0.0);
            }
        }
        if (interestId != lastInterestId) {
            if (interestId != 0) {
                // A fresh find gets a brief shimmer: brighter, faster churn, then it settles.
                exciteUntil = now + 25F + (float) nextUnit() * 20F;
            }
            lastInterestId = interestId;
        }
        // The outward pop is a rare startle, not the standard pause. The server rolls it and
        // flags both partners, so a bonded pair pops and freezes as one; the client just
        // watches the flag's rising edge.
        boolean burstFlag = entity.isBurstFlagged();
        if (state == ShoalEntity.STATE_HOLD && burstFlag && !sawBurst && lastState != -1) {
            burstOutward(ax, ay, az);
            // Each freeze runs a different length so repeated startles never feel metronomic.
            stillUntil = now + STILL_TICKS * (0.7F + (float) nextUnit() * 0.6F);
        }
        sawBurst = burstFlag;
        if (state != lastState) {
            if (collapsing && lastState != -1) {
                // The vacuum opens with the same pop before everything gets dragged back in.
                burstOutward(ax, ay, az);
            }
            lastState = state;
        }
        boolean still = state == ShoalEntity.STATE_HOLD && now < stillUntil;
        boolean orbiting = state == ShoalEntity.STATE_ORBIT;

        float excite = excitement(now);
        float noiseTime = tick * 0.04F * churnRate;
        // How far the strands sit from the center line right now. Cycles slowly, so the stream
        // visibly splits apart and braids back together. The floor keeps the strands from ever
        // fully collapsing onto each other, which read as a thick core instead of a stream.
        float branchOpen = 0.35F + 0.65F * (0.5F + 0.5F * Mth.sin(now * 0.02F * braidRate + braidPhase));
        float attract = still ? 0F : attractStrengthForState(state) * (1F + excite * 0.4F);
        float shellR = spawnRadiusForState(state);
        // The secondary lobe rides the same trail but stays cloudier: while the primary pulls
        // into a crisp braided line, this one loosens, so together they read as one organism
        // with a dense leading edge and a diffuse wake.
        boolean primaryLobe = entity.isPrimaryLobe();
        float lineSpread = primaryLobe ? 1F : 1.8F;
        float lineRest = primaryLobe ? 0.2F : 0.5F;
        float damping = still ? 0.84F : state == ShoalEntity.STATE_HOLD ? 0.86F : 0.92F;
        float curlAmp = collapsing || still ? 0F : curlAmpForState(state) * (1F + excite * 0.8F);
        double vaX = ax - lastAnchorX;
        double vaY = ay - lastAnchorY;
        double vaZ = az - lastAnchorZ;
        lastAnchorX = ax; lastAnchorY = ay; lastAnchorZ = az;

        for (int i = 0; i < capacity; i++) {
            // Aging pauses during the frozen beat, otherwise the burst pattern dissolves within
            // a second as its motes expire and respawn back at the center.
            if (!still) age[i] += dt;
            if (age[i] >= life[i]) {
                // During the vacuum, expired motes stay dead so the swarm thins out as it
                // funnels into the anchor instead of endlessly refilling.
                if (collapsing) continue;
                respawn(i, state, ax, ay, az, vaX, vaY, vaZ);
                continue;
            }

            // Curl-noise-ish flow: three sin/cos evaluations that stay bounded and read as a
            // continuous flow field without materializing a proper noise sampler.
            float px_ = px[i], py_ = py[i], pz_ = pz[i];
            float ncx = Mth.sin((py_ + noiseTime) * 0.6F) * Mth.cos((pz_ - noiseTime) * 0.5F);
            float ncy = Mth.sin((pz_ - noiseTime) * 0.7F) * Mth.cos((px_ + noiseTime) * 0.4F);
            float ncz = Mth.sin((px_ + noiseTime) * 0.5F) * Mth.cos((py_ - noiseTime) * 0.6F);

            // Attraction target: for Flow, each mote follows its own fixed slot on the trail plus
            // a personal sideways offset, so the stream has genuine thickness - hundreds of
            // parallel followers rather than everything converging onto the exact trail line.
            // The slot spreads them along the ribbon; the offset spreads them across it. For
            // Hold/Drift, aim straight at the anchor.
            double tx, ty, tz;
            float rest;
            if (state == ShoalEntity.STATE_FLOW && trailWritten > 2) {
                int stepsBack = Math.min(trailWritten - 1, (int) slot[i]);
                int idx = (trailHead - stepsBack + TRAIL_LEN) % TRAIL_LEN;
                tx = trailX[idx] + offX[i] * lineSpread;
                ty = trailY[idx] + offY[i] * lineSpread;
                tz = trailZ[idx] + offZ[i] * lineSpread;
                // The four strands corkscrew around the center line with 90-degree phase
                // offsets, so they genuinely cross over and under one another along the ribbon
                // instead of riding as parallel rails. branchOpen cycles the helix radius, so
                // the braid periodically pulls tight into a single line and blooms open again.
                // Nearby solid blocks recorded on the trail shove the whole target sideways and
                // widen the helix, so the stream visibly parts around trunks and poles.
                int ahead = (idx + 1) % TRAIL_LEN;
                double dirX = trailX[ahead] - trailX[idx];
                double dirZ = trailZ[ahead] - trailZ[idx];
                double hl = Math.sqrt(dirX * dirX + dirZ * dirZ);
                if (hl > 1.0E-4 && branchOpen > 0.05F) {
                    double along = stepsBack / (double) TRAIL_LEN;
                    double wPhase = along * 9.42 + branch[i] * 1.571 + now * 0.045;
                    double amp = branchOpen * (0.6 + 0.4 * along)
                            * (1.0 + trailAvoidMag[idx]);
                    double lat = Math.sin(wPhase) * amp * 2.8;
                    // Cap the sideways swing at the recorded clearance so whole strands don't
                    // arc through a trunk the center line is politely steering past. The margin
                    // leaves room for the personal offsets on top.
                    double maxLat = Math.max(0.15, trailClear[idx] - 0.75);
                    lat = Mth.clamp(lat, -maxLat, maxLat);
                    tx += -dirZ / hl * lat;
                    tz += dirX / hl * lat;
                    ty += Math.cos(wPhase) * amp * 0.8;
                }
                tx += trailAvoidX[idx];
                tz += trailAvoidZ[idx];
                // Snug to the strand; spread within it comes from the personal offsets.
                rest = lineRest;
            } else if (state == ShoalEntity.STATE_TORNADO) {
                // A soft updraft rather than machinery: each mote rides a slowly rising spiral
                // that widens toward the top, then recirculates from the bottom. The loose
                // spring and extra curl keep the column from reading as rigid spinning ribs.
                float hNorm = (slot[i] / (float) TRAIL_LEN + now * 0.008F) % 1F;
                float ang = now * 0.11F + hNorm * 5.0F + branch[i] * 1.571F;
                float rad = 1.2F + hNorm * 3.5F;
                tx = ax + Mth.cos(ang) * rad;
                ty = ay - 1.8F + hNorm * 6.5F;
                tz = az + Mth.sin(ang) * rad;
                rest = 0.3F;
            } else if (interestPos != null && branch[i] == 0 && !still) {
                // Tendril motes: a quarter of the swarm strings out along the line toward the
                // interest, tapering as it goes, so the cloud visibly points at what it is
                // studying. Capped reach keeps it a gesture rather than a bridge.
                double rx = interestPos.x - ax;
                double ry = interestPos.y - ay;
                double rz = interestPos.z - az;
                double rd = Math.sqrt(rx * rx + ry * ry + rz * rz);
                if (rd > 2.0 && rd < 24.0) {
                    double f = slot[i] / (double) TRAIL_LEN;
                    double along = Math.min(rd - 1.0, 14.0) * f;
                    float taper = 1F - 0.75F * (float) f;
                    tx = ax + rx / rd * along + offX[i] * taper;
                    ty = ay + ry / rd * along + offY[i] * taper;
                    tz = az + rz / rd * along + offZ[i] * taper;
                    rest = 0.12F;
                } else {
                    tx = ax; ty = ay; tz = az;
                    rest = home[i] * shellR;
                }
            } else {
                tx = ax; ty = ay; tz = az;
                rest = home[i] * shellR;
            }

            float dx = (float) (tx - px_);
            float dy = (float) (ty - py_);
            float dz = (float) (tz - pz_);
            float distSq = dx * dx + dy * dy + dz * dz;
            if (collapsing && distSq < 0.04F) {
                // Arrived at the anchor: gone. The disk ate it.
                age[i] = life[i];
                continue;
            }
            if (distSq > 0.001F) {
                float dist = (float) Math.sqrt(distSq);
                float inv = 1.0F / dist;
                // Spring toward each mote's own rest shell instead of the target point itself.
                // Pulling straight at the target let the damping settle everything into a tight
                // knot; the per-mote rest radius keeps the cloud filled out. Stretch goes
                // slightly negative inside the shell so crowded motes ease back outward. The
                // vacuum collapses the shell to zero so everything funnels straight in.
                if (collapsing) rest = 0F;
                float stretch = Mth.clamp(dist - rest, -0.6F, 2.5F);
                float pull = stretch * attract * dt;
                vx[i] += dx * inv * pull;
                vy[i] += dy * inv * pull;
                vz[i] += dz * inv * pull;

                // Orbit adds a horizontal tangential push around the anchor, so the cloud reads
                // as swirling the thing it hovers over rather than just hanging near it.
                if (orbiting) {
                    float hLen = (float) Math.sqrt(dx * dx + dz * dz);
                    if (hLen > 0.05F) {
                        // Gentle enough that the swirl reads as interest, not a hard ring.
                        float swirl = 0.22F * dt / hLen;
                        vx[i] += -dz * swirl;
                        vz[i] += dx * swirl;
                    }
                }
            }

            vx[i] += ncx * curlAmp * dt;
            vy[i] += ncy * curlAmp * dt;
            vz[i] += ncz * curlAmp * dt;
            vx[i] *= damping;
            vy[i] *= damping;
            vz[i] *= damping;

            // Cap speed to keep runaway divergence out of the loop when the anchor teleports.
            // The vacuum gets a much looser cap so the suck reads fast.
            float cap = collapsing ? 1.2F : 0.35F;
            float vsq = vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i];
            if (vsq > cap) {
                float k = cap / vsq;
                vx[i] *= k; vy[i] *= k; vz[i] *= k;
            }

            px[i] = px_ + vx[i];
            py[i] = py_ + vy[i];
            pz[i] = pz_ + vz[i];
        }
    }

    /**
     * Checks the blocks around a fresh trail sample and records a sideways shove, a widen
     * factor, and the lateral clearance for it. A 5x5 horizontal scan per world tick, so the
     * cost is negligible, but it is what lets the flow visibly thread around tree trunks and
     * fence poles and keeps the braid amplitude from swinging strands through them.
     */
    private void sampleAvoidance(ShoalEntity entity, int idx, double ax, double ay, double az) {
        net.minecraft.world.level.Level level = entity.level();
        net.minecraft.core.BlockPos base = net.minecraft.core.BlockPos.containing(ax, ay, az);
        float avx = 0F, avz = 0F;
        int solid = 0;
        float clear = 4F;
        for (int sx = -2; sx <= 2; sx++) {
            for (int sz = -2; sz <= 2; sz++) {
                if (sx == 0 && sz == 0) continue;
                net.minecraft.core.BlockPos p = base.offset(sx, 0, sz);
                if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                    float d = (float) Math.sqrt(sx * sx + sz * sz);
                    if (d < 1.5F) {
                        avx -= sx;
                        avz -= sz;
                        solid++;
                    }
                    if (d < clear) clear = d;
                }
            }
        }
        float len = (float) Math.sqrt(avx * avx + avz * avz);
        if (len > 1.0E-3F) {
            trailAvoidX[idx] = avx / len * 0.9F;
            trailAvoidZ[idx] = avz / len * 0.9F;
        } else {
            trailAvoidX[idx] = 0F;
            trailAvoidZ[idx] = 0F;
        }
        trailAvoidMag[idx] = Math.min(1.5F, solid * 0.6F);
        trailClear[idx] = clear;
    }

    /** Kick every live mote away from the anchor. Runs once on the transition into Hold. */
    private void burstOutward(double ax, double ay, double az) {
        for (int i = 0; i < capacity; i++) {
            if (age[i] >= life[i]) continue;
            double bx = px[i] - ax;
            double by = py[i] - ay;
            double bz = pz[i] - az;
            double len = Math.sqrt(bx * bx + by * by + bz * bz);
            if (len < 1.0E-3) {
                double theta = nextUnit() * Math.PI * 2.0;
                double c = 1.0 - 2.0 * nextUnit();
                double s = Math.sqrt(Math.max(0.0, 1.0 - c * c));
                bx = Math.cos(theta) * s;
                by = c;
                bz = Math.sin(theta) * s;
                len = 1.0;
            }
            double kick = (0.25 + nextUnit() * 0.3) / len;
            vx[i] += (float) (bx * kick);
            vy[i] += (float) (by * kick);
            vz[i] += (float) (bz * kick);
        }
    }

    private void respawn(int i, byte state, double ax, double ay, double az,
                         double vaX, double vaY, double vaZ) {
        float radius = spawnRadiusForState(state);
        double u = nextUnit();
        double v = nextUnit();
        double w = nextUnit();
        double theta = u * Math.PI * 2.0;
        double phi = Math.acos(1.0 - 2.0 * v);
        // Cube root gives uniform density through the sphere's volume; the old square root
        // packed most spawns near the center, which read as a small bright core with a halo of
        // strays around it.
        double r = radius * Math.cbrt(w);
        double ox = r * Math.sin(phi) * Math.cos(theta);
        double oy = r * Math.cos(phi);
        double oz = r * Math.sin(phi) * Math.sin(theta);

        double spawnX = ax + ox;
        double spawnY = ay + oy + (state == ShoalEntity.STATE_HOLD ? 0.15 : 0.0);
        double spawnZ = az + oz;

        px[i] = (float) spawnX;
        py[i] = (float) spawnY;
        pz[i] = (float) spawnZ;
        // Inherit some anchor velocity so Flow-spawned motes streak behind naturally.
        vx[i] = (float) (vaX * 0.4);
        vy[i] = (float) (vaY * 0.4);
        vz[i] = (float) (vaZ * 0.4);
        age[i] = 0F;
        life[i] = MOTE_LIFETIME * (0.6F + (float) nextUnit() * 0.6F);
        // Shell fraction of the current state's spawn radius, resolved at use time so the cloud
        // grows or shrinks with state changes instead of waiting out a full respawn cycle.
        home[i] = 0.5F + (float) nextUnit() * 0.5F;
        slot[i] = 1F + (float) nextUnit() * (TRAIL_LEN - 2);
        double offTheta = nextUnit() * Math.PI * 2.0;
        double offC = 1.0 - 2.0 * nextUnit();
        double offS = Math.sqrt(Math.max(0.0, 1.0 - offC * offC));
        double offMag = 0.2 + nextUnit() * 0.55;
        offX[i] = (float) (Math.cos(offTheta) * offS * offMag);
        // Vertical spread is squashed so the stream reads wide rather than tall.
        offY[i] = (float) (offC * offMag * 0.55);
        offZ[i] = (float) (Math.sin(offTheta) * offS * offMag);
        branch[i] = (byte) (nextUnit() * 4.0);
        // Tiny motes. Spec called for 1-3 pixel apparent size; at close range this reads as a
        // dense field of dots rather than a handful of soft blobs.
        size[i] = 0.022F + (float) nextUnit() * 0.024F;
    }

    private static float attractStrengthForState(byte state) {
        return switch (state) {
            case ShoalEntity.STATE_HOLD -> 0.35F;
            case ShoalEntity.STATE_DRIFT -> 0.30F;
            case ShoalEntity.STATE_FLOW -> 0.45F;
            case ShoalEntity.STATE_COLLAPSE -> 2.2F;
            case ShoalEntity.STATE_ORBIT -> 0.5F;
            case ShoalEntity.STATE_INSPECT -> 0.25F;
            case ShoalEntity.STATE_TORNADO -> 0.55F;
            default -> 0.35F;
        };
    }

    private static float curlAmpForState(byte state) {
        return switch (state) {
            case ShoalEntity.STATE_FLOW -> 0.35F;
            case ShoalEntity.STATE_ORBIT -> 0.28F;
            // Inspect barely stirs: the cloud hangs and shifts almost imperceptibly.
            case ShoalEntity.STATE_INSPECT -> 0.07F;
            // Enough wobble that the funnel breathes instead of running on rails.
            case ShoalEntity.STATE_TORNADO -> 0.12F;
            default -> 0.18F;
        };
    }

    private static float spawnRadiusForState(byte state) {
        return switch (state) {
            case ShoalEntity.STATE_HOLD -> SPAWN_R_HOLD;
            case ShoalEntity.STATE_DRIFT -> SPAWN_R_DRIFT;
            case ShoalEntity.STATE_FLOW -> SPAWN_R_FLOW;
            case ShoalEntity.STATE_ORBIT -> SPAWN_R_ORBIT;
            case ShoalEntity.STATE_INSPECT -> SPAWN_R_INSPECT;
            case ShoalEntity.STATE_TORNADO -> 3.0F;
            default -> SPAWN_R_HOLD;
        };
    }

    /**
     * Splitmix64 step, mapped to a uniform double in [0, 1). The previous hash-and-frac scheme
     * multiplied nanosecond-scale longs into doubles far past 2^52, where doubles carry no
     * fractional bits at all: frac() returned 0 for every mote, so all spawn offsets collapsed
     * onto the anchor and the whole swarm rendered as a single dot.
     */
    private double nextUnit() {
        long z = (rngState += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    /** 0..1 shimmer from a fresh interest pick, fading out over its last second. */
    public float excitement() {
        return excitement(lastUpdateTickTime);
    }

    private float excitement(float now) {
        if (Float.isNaN(now)) return 0F;
        float remain = exciteUntil - now;
        if (remain <= 0F) return 0F;
        return Math.min(1F, remain / 20F);
    }

    // Accessors for the renderer.
    public int count() { return capacity; }
    public float px(int i) { return px[i]; }
    public float py(int i) { return py[i]; }
    public float pz(int i) { return pz[i]; }
    public float size(int i) { return size[i]; }
    public float alpha(int i) {
        float t = age[i] / Math.max(1F, life[i]);
        if (t <= 0F || t >= 1F) return 0F;
        // Fast fade in over the first 8% of life, fast fade out over the last 8%, hold at max
        // in between. Keeps each mote at full visibility for the great majority of its lifetime
        // instead of spending most of its life below the fragment shader's alpha discard
        // threshold, which was making the swarm read as sparse.
        float fadeIn = Math.min(1F, t / 0.08F);
        float fadeOut = Math.min(1F, (1F - t) / 0.08F);
        return Math.min(fadeIn, fadeOut);
    }
}
