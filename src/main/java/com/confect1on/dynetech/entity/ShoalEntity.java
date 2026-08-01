package com.confect1on.dynetech.entity;

import com.confect1on.dynetech.config.DTConfig;
import com.confect1on.dynetech.gene.DTAttachments;
import com.confect1on.dynetech.gene.EquippedPerks;
import com.confect1on.dynetech.gene.PerkEntry;
import com.confect1on.dynetech.gene.Perks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * Server-authoritative swarm entity. The Shoal itself has no model - the client renders it as a
 * cloud of luminous motes anchored to this entity's synced position. Behavior is a three-state
 * loop of Hold, Drift, and Flow. The Shoal never attacks and never damages blocks; contact with
 * players applies a hidden incubation gene which matures into shoal_infection later.
 *
 * <p>Damage from any source is ignored except admin/void so ops can still clean up stuck ones.
 * The only intended kill path is a Phase Disk, which routes through {@link ShoalEntity#collapse}
 * from the projectile hit dispatch rather than through {@link #hurt}.
 */
public class ShoalEntity extends Entity {

    public static final byte STATE_HOLD = 0;
    public static final byte STATE_DRIFT = 1;
    public static final byte STATE_FLOW = 2;
    public static final byte STATE_COLLAPSE = 3;
    public static final byte STATE_ORBIT = 4;
    public static final byte STATE_INSPECT = 5;
    public static final byte STATE_TORNADO = 6;

    /** How long the entity lingers after a Phase Disk hit so the client can play the vacuum. */
    private static final int COLLAPSE_TICKS = 30;

    private static final int MIN_ORBIT_TICKS = 100;
    private static final int MAX_ORBIT_TICKS = 260;
    private static final int MIN_INSPECT_TICKS = 140;
    private static final int MAX_INSPECT_TICKS = 320;
    private static final int MIN_TORNADO_TICKS = 90;
    private static final int MAX_TORNADO_TICKS = 160;

    private static final double ORBIT_NOTICE_RANGE = 24.0;
    private static final double ORBIT_CHASE_SPEED = 0.3;
    private static final double INSPECT_NOTICE_RANGE = 12.0;
    private static final double INSPECT_STANDOFF = 3.5;
    private static final double INSPECT_CREEP_SPEED = 0.035;

    private static final EntityDataAccessor<Byte> DATA_STATE =
            SynchedEntityData.defineId(ShoalEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> DATA_SEED =
            SynchedEntityData.defineId(ShoalEntity.class, EntityDataSerializers.INT);
    // Server-rolled so a bonded pair can burst-and-freeze together; the client only reacts.
    private static final EntityDataAccessor<Boolean> DATA_BURST =
            SynchedEntityData.defineId(ShoalEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_PRIMARY =
            SynchedEntityData.defineId(ShoalEntity.class, EntityDataSerializers.BOOLEAN);
    // Entity id of the current curiosity target (0 = none), synced so the client can string a
    // visible tendril of motes toward whatever the swarm is studying.
    private static final EntityDataAccessor<Integer> DATA_INTEREST =
            SynchedEntityData.defineId(ShoalEntity.class, EntityDataSerializers.INT);
    // How fast the cloud runs its internal motion right now. Rain tires it, a deliberate
    // rest drops it to a crawl, a startle spikes it. The client eases toward this value so
    // mood changes read as the swarm winding up or down rather than switching gears.
    private static final EntityDataAccessor<Float> DATA_TEMPO =
            SynchedEntityData.defineId(ShoalEntity.class, EntityDataSerializers.FLOAT);
    // Entity id of the vacuum drain during Collapse (0 = the anchor). A phase disk flying
    // past keeps moving, so the client pulls the motes into the disk itself.
    private static final EntityDataAccessor<Integer> DATA_COLLAPSE_FOCUS =
            SynchedEntityData.defineId(ShoalEntity.class, EntityDataSerializers.INT);

    // Long pauses on purpose. Short holds made the whole loop feel urgent; the Shoal should
    // linger, inspect, and only then move on.
    private static final int MIN_HOLD_TICKS = 60;
    private static final int MAX_HOLD_TICKS = 240;
    private static final int MIN_DRIFT_TICKS = 60;
    private static final int MAX_DRIFT_TICKS = 140;
    private static final int MIN_FLOW_TICKS = 60;
    private static final int MAX_FLOW_TICKS = 240;

    private static final double DRIFT_MIN_RANGE = 3.0;
    private static final double DRIFT_MAX_RANGE = 8.0;
    private static final double FLOW_MIN_RANGE = 10.0;
    private static final double FLOW_MAX_RANGE = 40.0;

    private static final double DRIFT_SPEED = 0.18;
    private static final double FLOW_SPEED = 0.42;

    private static final int WAYPOINT_MIN = 2;
    private static final int WAYPOINT_MAX = 4;

    private static final double CURIOSITY_RANGE = 32.0;
    private static final double ORBIT_RADIUS_MIN = 3.0;
    private static final double ORBIT_RADIUS_MAX = 6.0;

    // Bonded pairs pull back together past this separation. Tight: the two clouds should
    // stay close enough to read as lobes of one organism, not neighbors.
    private static final double PAIR_REJOIN_RANGE = 4.0;
    // Past this the secondary abandons subtlety and races back to the leader.
    private static final double PAIR_HARD_RANGE = 7.0;

    private byte state = STATE_HOLD;
    private int stateTicks;
    private int stateDuration = 40;

    private final java.util.ArrayDeque<Vec3> waypoints = new java.util.ArrayDeque<>();
    private java.util.UUID interestUuid;
    private double breathePhase;
    private double speedMul = 1.0;
    private double orbitAngle;
    private double orbitRadius = 1.8;
    private double orbitAngular = 0.08;
    private Vec3 inspectCenter;
    private Vec3 microTarget;

    // Secondaries hold one entry (the primary); the primary holds every secondary.
    private final java.util.List<java.util.UUID> partnerUuids = new java.util.ArrayList<>();
    // The primary runs the decision chain; the secondaries only echo it. Defaults to true so a
    // lone Shoal, or a survivor whose partners are gone, behaves fully on its own.
    private boolean primary = true;
    // True only for a deliberate long rest from planRest, so the partner can tell a real rest
    // apart from the ordinary between-state hold and knows when to settle down alongside.
    private boolean resting;

    // Sustained-attention gate for infection: how long the interest has been locked on the
    // same player, and who. Five seconds of being studied, then contact does the rest.
    private static final int ATTENTION_INFECT_TICKS = 100;
    private java.util.UUID attentionUuid;
    private int attentionTicks;

    // Rolled once from the synced seed. Every Shoal gets its own temperament: patience
    // stretches or shortens every pause, energy scales travel speed, curiosity tilts how often
    // nearby things win its attention. Identical constants across all Shoals read as clockwork.
    private double patience = 1.0;
    private double energy = 1.0;
    private double curiosity = 1.0;
    private boolean personalityRolled;

    // Destination of a glowing-block visit; consumed when the travel ends near it.
    private Vec3 pendingInspect;
    // Set by a startle; the next Hold decision turns it into genuine flight.
    private Vec3 fleeFrom;
    private long startleCooldownUntil;
    private long noticeCooldownUntil;
    private long ambientCooldownUntil;
    private long clusterCooldownUntil;
    private long lastDayPhase = -1L;
    // Set when a currently-watched interest dies; the swarm drifts to the spot for a slow look.
    private Vec3 pendingWake;

    private final java.util.Map<java.util.UUID, Long> boredUntil = new java.util.HashMap<>();
    private final java.util.ArrayDeque<java.util.UUID> recentObserved = new java.util.ArrayDeque<>(3);
    private java.util.UUID engagedUuid;
    private int engagement;

    public ShoalEntity(EntityType<? extends ShoalEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.blocksBuilding = false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_STATE, STATE_HOLD);
        builder.define(DATA_SEED, 0);
        builder.define(DATA_BURST, false);
        builder.define(DATA_PRIMARY, true);
        builder.define(DATA_INTEREST, 0);
        builder.define(DATA_TEMPO, 1.0F);
        builder.define(DATA_COLLAPSE_FOCUS, 0);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.state = tag.getByte("State");
        this.stateTicks = tag.getInt("StateTicks");
        this.stateDuration = tag.getInt("StateDuration");
        this.entityData.set(DATA_STATE, this.state);
        this.entityData.set(DATA_SEED, tag.getInt("Seed"));
        this.partnerUuids.clear();
        if (tag.hasUUID("Partner")) {
            // Pre-trio saves stored a single bond.
            this.partnerUuids.add(tag.getUUID("Partner"));
        }
        for (net.minecraft.nbt.Tag t : tag.getList("Partners", net.minecraft.nbt.Tag.TAG_INT_ARRAY)) {
            this.partnerUuids.add(net.minecraft.nbt.NbtUtils.loadUUID(t));
        }
        if (tag.contains("Primary")) {
            this.primary = tag.getBoolean("Primary");
        }
        this.entityData.set(DATA_PRIMARY, this.primary);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putByte("State", this.state);
        tag.putInt("StateTicks", this.stateTicks);
        tag.putInt("StateDuration", this.stateDuration);
        tag.putInt("Seed", this.entityData.get(DATA_SEED));
        if (!this.partnerUuids.isEmpty()) {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (java.util.UUID id : this.partnerUuids) {
                list.add(net.minecraft.nbt.NbtUtils.createUUID(id));
            }
            tag.put("Partners", list);
        }
        tag.putBoolean("Primary", this.primary);
    }

    public void addPartner(java.util.UUID id) {
        this.partnerUuids.add(id);
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
        this.entityData.set(DATA_PRIMARY, primary);
    }

    public boolean isBurstFlagged() {
        return this.entityData.get(DATA_BURST);
    }

    public float getTempo() {
        return this.entityData.get(DATA_TEMPO);
    }

    public int getCollapseFocusId() {
        return this.entityData.get(DATA_COLLAPSE_FOCUS);
    }

    public boolean isPrimaryLobe() {
        return this.entityData.get(DATA_PRIMARY);
    }

    /** First living partner: the leader for a secondary, some secondary for the primary. */
    private ShoalEntity resolvePartner() {
        List<ShoalEntity> all = resolvePartners();
        return all.isEmpty() ? null : all.get(0);
    }

    private List<ShoalEntity> resolvePartners() {
        if (this.partnerUuids.isEmpty() || !(this.level() instanceof ServerLevel server)) {
            return List.of();
        }
        java.util.List<ShoalEntity> out = new java.util.ArrayList<>(this.partnerUuids.size());
        for (java.util.UUID id : this.partnerUuids) {
            if (server.getEntity(id) instanceof ShoalEntity partner && partner.isAlive()) {
                out.add(partner);
            }
        }
        return out;
    }

    public byte getShoalState() {
        return this.entityData.get(DATA_STATE);
    }

    public int getSeed() {
        return this.entityData.get(DATA_SEED);
    }

    public int getInterestId() {
        return this.entityData.get(DATA_INTEREST);
    }

    /**
     * Rolled deterministically from the synced seed, so the same Shoal keeps the same
     * temperament across save/load without persisting anything extra.
     */
    private void ensurePersonality() {
        if (this.personalityRolled) return;
        int seed = this.entityData.get(DATA_SEED);
        if (seed == 0) return;
        this.patience = 0.7D + seedUnit(seed, 1) * 0.7D;
        this.energy = 0.75D + seedUnit(seed, 2) * 0.55D;
        this.curiosity = 0.7D + seedUnit(seed, 3) * 0.6D;
        this.personalityRolled = true;
    }

    /** Splitmix64 of the seed plus a stream index, mapped to [0, 1). */
    private static double seedUnit(int seed, int stream) {
        long z = seed * 0x9E3779B97F4A7C15L + stream * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    protected void onInsideBlock(net.minecraft.world.level.block.state.BlockState state) {
    }

    @Override
    public boolean fireImmune() {
        return true;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        if (source.is(DamageTypes.GENERIC_KILL) || source.is(DamageTypes.FELL_OUT_OF_WORLD)) {
            return false;
        }
        return true;
    }

    // Melee, projectiles, environment all pass through. Only admin kills succeed.
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypes.GENERIC_KILL) || source.is(DamageTypes.FELL_OUT_OF_WORLD)) {
            return super.hurt(source, amount);
        }
        return false;
    }

    @Override
    public void push(double x, double y, double z) {
    }

    @Override
    public void push(Entity other) {
    }

    @Override
    protected void doWaterSplashEffect() {
    }

    @Override
    public boolean canCollideWith(Entity other) {
        return false;
    }

    /**
     * Vacuum-and-destroy called from the Phase Disk hit. The entity freezes in place and enters
     * the Collapse state for a short window so tracking clients can suck the motes into the
     * anchor, then discards itself.
     */
    public void collapse() {
        collapseInto(null);
    }

    /** Same vacuum, but with a visible drain point: the motes get pulled into the focus. */
    public void collapseInto(Entity focus) {
        if (this.state == STATE_COLLAPSE) return;
        this.entityData.set(DATA_COLLAPSE_FOCUS, focus == null ? 0 : focus.getId());
        if (this.level() instanceof ServerLevel server) {
            server.playSound(null, this.getX(), this.getY(), this.getZ(),
                    net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                    net.minecraft.sounds.SoundSource.HOSTILE, 1.0F, 1.6F);
        }
        this.waypoints.clear();
        this.setDeltaMovement(Vec3.ZERO);
        setState(STATE_COLLAPSE, COLLAPSE_TICKS);
        // A bonded group dies as one; the re-entry guard above stops the mutual recursion.
        for (ShoalEntity partner : resolvePartners()) {
            partner.collapseInto(focus);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            this.breathePhase += 0.05D;
            return;
        }

        if (this.entityData.get(DATA_SEED) == 0) {
            this.entityData.set(DATA_SEED, this.random.nextInt(Integer.MAX_VALUE - 1) + 1);
        }
        ensurePersonality();

        this.stateTicks++;

        if (this.state == STATE_COLLAPSE) {
            this.setDeltaMovement(Vec3.ZERO);
            if (this.stateTicks >= this.stateDuration) {
                this.discard();
            }
            return;
        }

        switch (this.state) {
            case STATE_HOLD -> tickHold();
            case STATE_DRIFT -> tickDrift();
            case STATE_FLOW -> tickFlow();
            case STATE_ORBIT -> tickOrbit();
            case STATE_INSPECT -> tickInspect();
            case STATE_TORNADO -> tickTornado();
            default -> setState(STATE_HOLD, rollHoldDuration());
        }

        if (this.tickCount % 5 == 0) {
            maybeStartle();
            maybeInterestDeath();
        }
        if (this.tickCount % 10 == 0) {
            maybeNotice();
            updateTempo();
            maybeDefendPlayer();
        }
        if (this.tickCount % 20 == 0) {
            maybeAmbient();
            maybeDayPhaseFlicker();
        }
        maybeDamageHostile();

        // Continuous glue for the secondary, on top of whatever its state is doing. Decisions
        // alone let the pair drift apart mid-state; this keeps the clouds overlapping always,
        // with a hard catch-up once the leader pulls genuinely away.
        if (!this.primary) {
            ShoalEntity leader = resolvePartner();
            if (leader != null && leader.state != STATE_COLLAPSE) {
                Vec3 to = leader.position().subtract(this.position());
                double d = to.length();
                if (d > PAIR_HARD_RANGE) {
                    this.move(MoverType.SELF, steerAroundBlocks(
                            to.normalize().scale(Math.min(0.5D, d - PAIR_REJOIN_RANGE))));
                } else if (d > PAIR_REJOIN_RANGE) {
                    this.move(MoverType.SELF, steerAroundBlocks(to.scale(0.03D)));
                }
            }
        }

        applyContactInfection();
        this.setDeltaMovement(this.getDeltaMovement().scale(0.85D));
    }

    private void tickHold() {
        // Hold has no motion, only slight bobbing controlled by breathePhase on the client.
        this.setDeltaMovement(Vec3.ZERO);
        if (this.stateTicks < this.stateDuration) return;

        // Curiosity first: a dropped item nearby is irresistible and gets swirled. A creature
        // close by gets observed. A distant target draws a Flow that lands near it, which then
        // chains naturally into an inspect on the next Hold. Failing all that, mix stillness,
        // slow in-place inspection, and wandering so the loop never settles into one rhythm.
        // The secondary never decides anything. It answers the primary's current state so the
        // pair reads as one organism with two lobes. If the primary is gone it inherits the
        // lead and the full decision chain below.
        // A startle resolves into flight: the burst hold covered the freeze, now the primary
        // puts real distance between the swarm and whatever rushed it. Secondaries just drop
        // the marker and follow the leader out as usual.
        if (this.fleeFrom != null) {
            Vec3 from = this.fleeFrom;
            this.fleeFrom = null;
            if (this.primary) {
                setInterest(null);
                Vec3 flat = new Vec3(this.getX() - from.x, 0.0D, this.getZ() - from.z);
                Vec3 dir = flat.lengthSqr() < 1.0E-4D
                        ? new Vec3(1.0D, 0.0D, 0.0D)
                        : flat.normalize();
                double dist = 8.0D + this.random.nextDouble() * 8.0D;
                planFlow(this.position().add(dir.scale(dist))
                        .add(0.0D, 1.0D + this.random.nextDouble() * 2.0D, 0.0D));
                return;
            }
        }

        ShoalEntity partner = resolvePartner();
        if (!this.primary && partner != null) {
            followPrimary(partner);
            return;
        }

        Entity interest = pickInterest();
        setInterest(interest);

        // Nothing catches its eye and the neighborhood is all old news: leave. A long Flow to
        // somewhere fresh reads much more alive than milling around bored things.
        if (interest == null && hasActiveBoredom() && this.random.nextFloat() < 0.6F) {
            planFlow(rollDestination(FLOW_MIN_RANGE, FLOW_MAX_RANGE));
            return;
        }

        if ((interest instanceof ItemEntity || interest instanceof ExperienceOrb)
                && this.distanceToSqr(interest) < ORBIT_NOTICE_RANGE * ORBIT_NOTICE_RANGE) {
            // Prefer the braided flow lap and the settle-and-watch inspect. The tight ring is
            // dramatic but reads as machinery when it's the default answer, so it stays rare.
            // Lures still bias the roll toward engaging at all.
            float r = isLure(interest) ? this.random.nextFloat() * 0.75F : this.random.nextFloat();
            r /= (float) this.curiosity;
            if (r < 0.08F) {
                planOrbit(interest);
                return;
            }
            if (r < 0.55F) {
                planFlowLoop(interest);
                return;
            }
            if (r < 0.85F) {
                planInspect(interest);
                return;
            }
        }
        if (interest != null && !(interest instanceof ItemEntity) && !(interest instanceof ExperienceOrb)) {
            double distSq = this.distanceToSqr(interest);
            if (distSq < INSPECT_NOTICE_RANGE * INSPECT_NOTICE_RANGE) {
                float r = this.random.nextFloat() / (float) this.curiosity;
                // Tornado stays a rare showpiece so the funnel keeps its punch.
                if (r < 0.02F) {
                    planTornado(interest);
                    return;
                }
                if (r < 0.40F) {
                    planInspect(interest);
                    return;
                }
                if (r < 0.90F) {
                    planFlowLoop(interest);
                    return;
                }
            }
            if (distSq > FLOW_MIN_RANGE * FLOW_MIN_RANGE && this.random.nextFloat() < 0.75F) {
                planFlow(orbitPoint(interest));
                return;
            }
        }

        // A watched creature just died: drift to the last known spot for a slow inspect.
        if (this.pendingWake != null) {
            Vec3 spot = this.pendingWake;
            this.pendingWake = null;
            this.pendingInspect = spot.add(0.0D, 1.1D, 0.0D);
            planFlow(spot.add(0.0D, 1.5D, 0.0D));
            return;
        }

        // Nothing living or dropped around: sometimes a glowing block nearby is worth a trip.
        // The swarm flows over and settles above it, nosing at the light.
        if (interest == null && this.random.nextFloat() < 0.25F * (float) this.curiosity) {
            Vec3 glow = findGlowSpot();
            if (glow != null) {
                this.pendingInspect = glow.add(0.0D, 1.1D, 0.0D);
                planFlow(glow.add(0.0D, 1.5D, 0.0D));
                return;
            }
        }

        // Loose flocking: two solo Shoals within range visibly find each other over a minute
        // or so of play, without pairing up as bonded partners.
        if (interest == null && this.random.nextFloat() < 0.35F) {
            ShoalEntity peer = findClusterPeer();
            if (peer != null) {
                planFlow(peer.position().add(
                        (this.random.nextDouble() - 0.5D) * 4.0D,
                        1.0D + this.random.nextDouble() * 2.0D,
                        (this.random.nextDouble() - 0.5D) * 4.0D));
                return;
            }
        }

        // Idle and the partner is close: occasionally sweep a braided arc around it, so the two
        // swarms visibly wind through each other now and then.
        if (partner != null && this.distanceToSqr(partner) < 64.0D && this.random.nextFloat() < 0.15F) {
            planFlowLoop(partner);
            return;
        }

        float roll = this.random.nextFloat();
        if (roll < 0.10F) {
            setState(STATE_HOLD, rollHoldDuration());
        } else if (roll < 0.18F) {
            planRest();
        } else if (roll < 0.30F) {
            planInspect(null);
        } else if (roll < 0.60F) {
            planDrift(interest);
        } else {
            planFlow(rollDestination(FLOW_MIN_RANGE, FLOW_MAX_RANGE));
        }
    }

    /**
     * The secondary's whole behavior: complement whatever the primary is doing. A spiral or
     * funnel gets a braided sweep of the same target, an inspection gets joined on the same
     * subject (coin flip between settling dead-still beside it and creeping the same spot),
     * travel gets followed, and any gap past the tether closes first.
     */
    private void followPrimary(ShoalEntity leader) {
        if (this.distanceToSqr(leader) > PAIR_REJOIN_RANGE * PAIR_REJOIN_RANGE) {
            // Right up against the leader, not an orbitPoint 3-6 blocks out, or the tether
            // never actually closes and the pair reads as two neighbors.
            planFlow(pointBeside(leader));
            return;
        }
        // Secondaries care about the primary at least as much as about whatever it found.
        // A third of the time they wind around the leader itself instead of its subject.
        boolean drawnToLeader = this.random.nextFloat() < 0.35F;
        switch (leader.state) {
            case STATE_ORBIT, STATE_TORNADO -> {
                Entity focus = leader.resolveInterest();
                if (focus != null) {
                    planFlowLoop(drawnToLeader ? leader : focus);
                    return;
                }
            }
            case STATE_INSPECT -> {
                int remaining = Math.max(80, leader.stateDuration - leader.stateTicks);
                if (drawnToLeader) {
                    planInspect(leader);
                    this.stateDuration = remaining;
                } else if (this.random.nextBoolean()) {
                    setState(STATE_HOLD, remaining);
                } else {
                    Entity focus = leader.resolveInterest();
                    planInspect(focus);
                    if (focus == null && leader.inspectCenter != null) {
                        this.inspectCenter = leader.inspectCenter.add(
                                (this.random.nextDouble() - 0.5D) * 3.0D,
                                0.0D,
                                (this.random.nextDouble() - 0.5D) * 3.0D);
                    }
                    this.stateDuration = remaining;
                }
                return;
            }
            case STATE_FLOW, STATE_DRIFT -> {
                if (!leader.waypoints.isEmpty()) {
                    planFlow(leader.waypoints.peekLast().add(
                            (this.random.nextDouble() - 0.5D) * 4.0D,
                            0.0D,
                            (this.random.nextDouble() - 0.5D) * 4.0D));
                    return;
                }
            }
            default -> { }
        }
        // Leader is holding or resting: mostly sit with it, sometimes sweep a braided arc
        // around it instead. Long rests get matched via the leader's remaining duration.
        if (!leader.resting && drawnToLeader) {
            planFlowLoop(leader);
            return;
        }
        int wait = leader.resting
                ? Math.max(80, leader.stateDuration - leader.stateTicks)
                : 20 + this.random.nextInt(40);
        setState(STATE_HOLD, wait);
    }

    /**
     * A proper rest: 15 to 30 seconds either frozen in place or creeping so slowly it barely
     * counts as moving. Half of each keeps the downtime itself from becoming predictable.
     */
    private void planRest() {
        setInterest(null);
        int duration = (int) ((300 + this.random.nextInt(300)) * this.patience * weatherMood());
        if (this.random.nextBoolean()) {
            setState(STATE_HOLD, duration);
        } else {
            planInspect(null);
            this.stateDuration = duration;
        }
        this.resting = true;
    }

    private void tickDrift() {
        if (this.waypoints.isEmpty() || this.stateTicks >= this.stateDuration) {
            finishTravel();
            return;
        }
        moveTowardCurrentWaypoint(DRIFT_SPEED);
    }

    private void tickFlow() {
        if (this.waypoints.isEmpty() || this.stateTicks >= this.stateDuration) {
            finishTravel();
            return;
        }
        moveTowardCurrentWaypoint(FLOW_SPEED);
    }

    /**
     * Travel is over. If the trip was toward a glowing block and the swarm actually got there,
     * settle straight into a slow inspection on top of it; otherwise the usual pause.
     */
    private void finishTravel() {
        Vec3 spot = this.pendingInspect;
        this.pendingInspect = null;
        if (spot != null && this.position().distanceToSqr(spot) < 36.0D) {
            planInspect(null);
            this.inspectCenter = spot;
            return;
        }
        setState(STATE_HOLD, rollHoldDuration());
    }

    private void moveTowardCurrentWaypoint(double speed) {
        Vec3 target = this.waypoints.peekFirst();
        if (target == null) return;
        Vec3 toward = target.subtract(this.position());
        double dist = toward.length();
        if (dist < 0.6D) {
            this.waypoints.pollFirst();
            return;
        }
        // Per-plan multiplier plus a slow surge cycle so travel reads as pulses of effort
        // rather than a constant glide, with an ease-out on final approach.
        double surge = 0.8D + 0.35D * Math.sin(this.tickCount * 0.06D);
        double eff = speed * this.speedMul * surge;
        if (this.waypoints.size() == 1) {
            eff *= Mth.clamp(dist / 4.0D, 0.3D, 1.0D);
        }
        Vec3 step = toward.normalize().scale(Math.min(eff, dist));
        this.move(MoverType.SELF, steerAroundBlocks(step));
    }

    /**
     * The entity has noPhysics, so move() slides straight through terrain. Probe a couple of
     * blocks ahead and, if something solid is in the way, slide the step along the blocked face
     * with a slight upward bias so the swarm pours around trunks and over ledges.
     */
    private Vec3 steerAroundBlocks(Vec3 step) {
        double len = step.length();
        if (len < 1.0E-4D) return step;
        Vec3 dir = step.scale(1.0D / len);
        Vec3 from = this.position();
        Vec3 probe = from.add(dir.scale(Math.max(len, 2.0D)));
        BlockHitResult hit = this.level().clip(new ClipContext(from, probe,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        if (hit.getType() == HitResult.Type.MISS) return step;
        Vec3 n = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
        Vec3 slide = dir.subtract(n.scale(dir.dot(n))).add(0.0D, 0.25D, 0.0D);
        if (slide.lengthSqr() < 1.0E-4D) {
            slide = new Vec3(-dir.z, 0.6D, dir.x);
        }
        return slide.normalize().scale(len);
    }

    /**
     * A braided lap: sweep part of the way around the target in a wide arc, then peel off to
     * somewhere nearby. Reads as a curious fly-by rather than committing to a ring.
     */
    private void planFlowLoop(Entity target) {
        setInterest(target);
        this.waypoints.clear();
        double radius = 2.2D + this.random.nextDouble() * 1.6D;
        Vec3 toSelf = this.position().subtract(target.position());
        double angle = Math.atan2(toSelf.z, toSelf.x);
        double sweep = (this.random.nextBoolean() ? 1.0D : -1.0D)
                * Math.PI * (1.2D + this.random.nextDouble());
        int count = 4 + this.random.nextInt(3);
        for (int i = 1; i <= count; i++) {
            double a = angle + sweep * i / count;
            this.waypoints.addLast(new Vec3(
                    target.getX() + Math.cos(a) * radius,
                    target.getY() + 0.8D + this.random.nextDouble() * 1.4D,
                    target.getZ() + Math.sin(a) * radius));
        }
        this.waypoints.addLast(rollDestination(4.0D, 9.0D));
        this.speedMul = 0.55D + this.random.nextDouble() * 0.5D;
        setState(STATE_FLOW, MIN_FLOW_TICKS + this.random.nextInt(MAX_FLOW_TICKS - MIN_FLOW_TICKS + 1));
    }

    private void planOrbit(Entity target) {
        setInterest(target);
        this.orbitRadius = 1.5D + this.random.nextDouble() * 1.2D;
        this.orbitAngular = (0.03D + this.random.nextDouble() * 0.05D)
                * (this.random.nextBoolean() ? 1.0D : -1.0D);
        Vec3 toSelf = this.position().subtract(target.position());
        this.orbitAngle = Math.atan2(toSelf.z, toSelf.x);
        setState(STATE_ORBIT, rollDuration(MIN_ORBIT_TICKS, MAX_ORBIT_TICKS));
    }

    private void tickOrbit() {
        Entity target = resolveInterest();
        if (target == null || !target.isAlive() || this.stateTicks >= this.stateDuration) {
            if (target != null) noteEngagement(target);
            setState(STATE_HOLD, rollHoldDuration());
            return;
        }
        this.orbitAngle += this.orbitAngular;
        double y = target.getY() + 0.9D + Math.sin(this.stateTicks * 0.09D) * 0.35D;
        Vec3 desired = new Vec3(
                target.getX() + Math.cos(this.orbitAngle) * this.orbitRadius,
                y,
                target.getZ() + Math.sin(this.orbitAngle) * this.orbitRadius);
        Vec3 toward = desired.subtract(this.position());
        double dist = toward.length();
        if (dist > 1.0E-3D) {
            this.move(MoverType.SELF, steerAroundBlocks(toward.normalize().scale(Math.min(ORBIT_CHASE_SPEED, dist))));
        }
    }

    private void planTornado(Entity target) {
        setInterest(target);
        setState(STATE_TORNADO, MIN_TORNADO_TICKS + this.random.nextInt(MAX_TORNADO_TICKS - MIN_TORNADO_TICKS + 1));
    }

    /** Sit right on the target while the client spins the funnel around it. */
    private void tickTornado() {
        Entity target = resolveInterest();
        if (target == null || !target.isAlive() || this.stateTicks >= this.stateDuration) {
            if (target != null) noteEngagement(target);
            setState(STATE_HOLD, rollHoldDuration());
            return;
        }
        Vec3 toward = target.position().add(0.0D, 0.4D, 0.0D).subtract(this.position());
        double dist = toward.length();
        if (dist > 0.1D) {
            this.move(MoverType.SELF, steerAroundBlocks(toward.normalize().scale(Math.min(0.3D, dist))));
        }
    }

    private void planInspect(Entity focus) {
        if (focus != null) {
            setInterest(focus);
            this.inspectCenter = standoffPoint(focus);
        } else {
            setInterest(null);
            this.inspectCenter = this.position();
        }
        this.microTarget = rollMicroTarget();
        setState(STATE_INSPECT, rollDuration(MIN_INSPECT_TICKS, MAX_INSPECT_TICKS));
    }

    /**
     * Very slow creep around a fixed center: the cloud hangs where it stopped and noses at
     * micro-waypoints inside a small pocket, as if examining something. With a focus entity the
     * pocket eases after it, so a wandering mob gets followed at a polite distance.
     */
    private void tickInspect() {
        if (this.inspectCenter == null) this.inspectCenter = this.position();
        Entity focus = resolveInterest();
        if (focus != null && focus.isAlive()) {
            Vec3 goal = standoffPoint(focus);
            this.inspectCenter = this.inspectCenter.add(goal.subtract(this.inspectCenter).scale(0.04D));
        }
        if (this.microTarget == null || this.stateTicks % 45 == 0) {
            this.microTarget = rollMicroTarget();
        }
        Vec3 toward = this.microTarget.subtract(this.position());
        double dist = toward.length();
        if (dist > 0.05D) {
            this.move(MoverType.SELF, steerAroundBlocks(toward.normalize().scale(Math.min(INSPECT_CREEP_SPEED, dist))));
        }
        if (this.stateTicks >= this.stateDuration) {
            if (focus != null) noteEngagement(focus);
            setState(STATE_HOLD, rollHoldDuration());
        }
    }

    /**
     * Fascination wears thin. Consecutive sessions with the same target make walking away more
     * likely, and once boredom lands the target is ignored for a minute or three, so the Shoal
     * finishes its look, drifts off, and only comes back once the novelty has recovered.
     */
    private void noteEngagement(Entity target) {
        if (isLure(target)) return;
        java.util.UUID uuid = target.getUUID();
        this.recentObserved.remove(uuid);
        this.recentObserved.addFirst(uuid);
        while (this.recentObserved.size() > 3) {
            this.recentObserved.removeLast();
        }
        if (uuid.equals(this.engagedUuid)) {
            this.engagement++;
        } else {
            this.engagedUuid = uuid;
            this.engagement = 1;
        }
        if (this.random.nextFloat() < 0.3F * this.engagement / (float) this.curiosity) {
            long until = this.level().getGameTime() + 1200L + this.random.nextInt(2400);
            this.boredUntil.values().removeIf(t -> t <= this.level().getGameTime());
            this.boredUntil.put(uuid, until);
            this.engagement = 0;
        }
    }

    private int recencyIndex(java.util.UUID uuid) {
        int i = 0;
        for (java.util.UUID seen : this.recentObserved) {
            if (seen.equals(uuid)) return i;
            i++;
        }
        return -1;
    }

    private boolean hasActiveBoredom() {
        long time = this.level().getGameTime();
        for (long until : this.boredUntil.values()) {
            if (until > time) return true;
        }
        return false;
    }

    private static boolean isLure(Entity e) {
        return e instanceof ItemEntity item
                && item.getItem().is(com.confect1on.dynetech.item.DTItems.SHOAL_LURE.get());
    }

    private boolean isBoredOf(Entity e) {
        Long until = this.boredUntil.get(e.getUUID());
        if (until == null) return false;
        if (this.level().getGameTime() >= until) {
            this.boredUntil.remove(e.getUUID());
            return false;
        }
        return true;
    }

    private Vec3 standoffPoint(Entity focus) {
        Vec3 flat = new Vec3(this.getX() - focus.getX(), 0.0D, this.getZ() - focus.getZ());
        Vec3 off = flat.lengthSqr() < 1.0E-4D
                ? new Vec3(INSPECT_STANDOFF, 0.0D, 0.0D)
                : flat.normalize().scale(INSPECT_STANDOFF);
        return focus.position().add(off).add(0.0D, 1.6D, 0.0D);
    }

    private Vec3 rollMicroTarget() {
        double a = this.random.nextDouble() * Math.PI * 2.0D;
        double r = 0.4D + this.random.nextDouble();
        double dy = (this.random.nextDouble() - 0.5D) * 0.8D;
        return this.inspectCenter.add(Math.cos(a) * r, dy, Math.sin(a) * r);
    }

    private void setInterest(Entity e) {
        this.interestUuid = e == null ? null : e.getUUID();
        this.entityData.set(DATA_INTEREST, e == null ? 0 : e.getId());
    }

    private Entity resolveInterest() {
        if (this.interestUuid == null || !(this.level() instanceof ServerLevel server)) return null;
        return server.getEntity(this.interestUuid);
    }

    private void planDrift(Entity interest) {
        Vec3 dest;
        if (interest != null) {
            dest = orbitPoint(interest);
        } else {
            dest = rollDestination(DRIFT_MIN_RANGE, DRIFT_MAX_RANGE);
        }
        this.waypoints.clear();
        this.waypoints.addLast(dest);
        this.speedMul = rollSpeedMul();
        setState(STATE_DRIFT, MIN_DRIFT_TICKS + this.random.nextInt(MAX_DRIFT_TICKS - MIN_DRIFT_TICKS + 1));
    }

    private void planFlow(Vec3 finalDest) {
        this.waypoints.clear();
        int count = WAYPOINT_MIN + this.random.nextInt(WAYPOINT_MAX - WAYPOINT_MIN + 1);
        Vec3 start = this.position();
        Vec3 straight = finalDest.subtract(start);
        double straightLen = straight.length();
        if (straightLen < 1.0E-4D) {
            this.waypoints.addLast(finalDest);
        } else {
            Vec3 dir = straight.normalize();
            Vec3 side = new Vec3(-dir.z, 0.0D, dir.x).normalize();
            for (int i = 1; i <= count; i++) {
                double t = (double) i / (double) (count + 1);
                Vec3 straightPoint = start.add(straight.scale(t));
                double phase = t * Math.PI * (1.0D + this.random.nextDouble());
                double lateral = Math.sin(phase) * Mth.clamp(straightLen * 0.15D, 1.0D, 4.5D);
                double vertical = Math.cos(phase * 0.7D) * 1.5D;
                Vec3 candidate = straightPoint.add(side.scale(lateral)).add(0.0D, vertical, 0.0D);
                this.waypoints.addLast(nudgeAroundObstacle(this.position(), candidate));
            }
            this.waypoints.addLast(finalDest);
        }
        this.speedMul = rollSpeedMul();
        setState(STATE_FLOW, MIN_FLOW_TICKS + this.random.nextInt(MAX_FLOW_TICKS - MIN_FLOW_TICKS + 1));
    }

    /** Each trip gets its own pace, anywhere from a lazy meander to an eager rush. */
    private double rollSpeedMul() {
        return (0.55D + this.random.nextDouble() * 0.9D) * this.energy;
    }

    /**
     * Cheap obstacle-avoidance: if a straight line from `from` to `to` hits a solid block, offset
     * the waypoint sideways and up by a fixed amount. Not a real pathfinder, but combined with the
     * curved base path this gives the "flows around and over things" reading called for in spec.
     */
    private Vec3 nudgeAroundObstacle(Vec3 from, Vec3 to) {
        ClipContext ctx = new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, this);
        BlockHitResult hit = this.level().clip(ctx);
        if (hit.getType() == HitResult.Type.MISS) return to;
        Vec3 dir = to.subtract(from).normalize();
        Vec3 side = new Vec3(-dir.z, 0.0D, dir.x).normalize();
        double sign = this.random.nextBoolean() ? 1.0D : -1.0D;
        return to.add(side.scale(sign * 2.5D)).add(0.0D, 1.75D, 0.0D);
    }

    /** A spot close enough to the target that the two clouds visibly overlap. */
    private Vec3 pointBeside(Entity e) {
        double angle = this.random.nextDouble() * Math.PI * 2.0D;
        double radius = 1.2D + this.random.nextDouble();
        return new Vec3(e.getX() + Math.cos(angle) * radius,
                e.getY() + 0.3D + this.random.nextDouble() * 0.8D,
                e.getZ() + Math.sin(angle) * radius);
    }

    private Vec3 orbitPoint(Entity interest) {
        double radius = ORBIT_RADIUS_MIN + this.random.nextDouble() * (ORBIT_RADIUS_MAX - ORBIT_RADIUS_MIN);
        double angle = this.random.nextDouble() * Math.PI * 2.0D;
        double y = interest.getY() + 0.6D + this.random.nextDouble() * 1.2D;
        return new Vec3(interest.getX() + Math.cos(angle) * radius,
                y,
                interest.getZ() + Math.sin(angle) * radius);
    }

    private Vec3 rollDestination(double minR, double maxR) {
        double angle = this.random.nextDouble() * Math.PI * 2.0D;
        double dist = minR + this.random.nextDouble() * (maxR - minR);
        double dx = Math.cos(angle) * dist;
        double dz = Math.sin(angle) * dist;
        double dy = (this.random.nextDouble() - 0.35D) * (maxR * 0.5D);
        return this.position().add(dx, dy, dz);
    }

    /**
     * Pick the most tempting curiosity target nearby. Distance is weighted by type so a dropped
     * item wins over a slightly closer creature: items get swirled, everything alive just gets
     * watched.
     */
    private Entity pickInterest() {
        AABB scan = this.getBoundingBox().inflate(CURIOSITY_RANGE);
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity e : this.level().getEntities(this, scan, this::isInteresting)) {
            double weight;
            if (isLure(e)) {
                weight = 0.05D;
            } else if (e instanceof ItemEntity item) {
                // Anything enchanted glitters, and glitter is hard to pass up.
                weight = item.getItem().hasFoil() ? 0.3D : 0.5D;
            } else if (e instanceof ExperienceOrb) {
                weight = 0.4D;
            } else if (e instanceof Player p) {
                // Fast movement catches its eye the way it would a cat's.
                weight = p.isSprinting() ? 0.55D : 0.8D;
            } else {
                weight = 1.0D;
            }
            // The last three things it looked at are progressively less tempting, most recent
            // least of all, so attention naturally rotates through whatever is around. The lure
            // is exempt: it always fascinates.
            int seen = isLure(e) ? -1 : recencyIndex(e.getUUID());
            if (seen >= 0) weight *= 2.5D - seen * 0.6D;
            double score = this.distanceToSqr(e) * weight;
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        if (best == null && this.interestUuid != null && this.level() instanceof ServerLevel server) {
            Entity prior = server.getEntity(this.interestUuid);
            if (prior != null && prior.isAlive() && !isBoredOf(prior)
                    && prior.distanceToSqr(this) < CURIOSITY_RANGE * CURIOSITY_RANGE) {
                return prior;
            }
        }
        return best;
    }

    private boolean isInteresting(Entity e) {
        if (e == this) return false;
        if (e instanceof ShoalEntity) return false;
        if (!isLure(e) && isBoredOf(e)) return false;
        if (e instanceof Player p) return !p.isSpectator() && p.isAlive();
        if (e instanceof ItemEntity || e instanceof ExperienceOrb) return true;
        if (e instanceof net.minecraft.world.entity.LivingEntity l) return l.isAlive();
        return false;
    }

    /**
     * Infection is no longer a walk-by hazard. The swarm has to be actively studying the
     * player, interest locked on them for a sustained beat, and the player has to be inside
     * the cloud when the study completes. The visible tendril settling on you and staying
     * there is the warning; step away or break its attention before it finishes.
     */
    private void applyContactInfection() {
        if (!(this.level() instanceof ServerLevel server)) return;
        if (!(resolveInterest() instanceof Player watched)
                || watched.isSpectator() || watched.isCreative() || !watched.isAlive()) {
            this.attentionUuid = null;
            this.attentionTicks = 0;
            return;
        }
        if (watched.getUUID().equals(this.attentionUuid)) {
            this.attentionTicks++;
        } else {
            this.attentionUuid = watched.getUUID();
            this.attentionTicks = 1;
        }
        double infectRadius = DTConfig.SHOAL_INFECT_RADIUS.get();
        if (this.attentionTicks >= ATTENTION_INFECT_TICKS
                && watched.distanceToSqr(this) <= infectRadius * infectRadius) {
            infect(server, watched);
        }
    }

    private void infect(ServerLevel server, Player player) {
        EquippedPerks eq = player.getData(DTAttachments.EQUIPPED_PERKS.get());
        if (eq.has(Perks.SHOAL_INCUBATION.getId()) || eq.has(Perks.SHOAL_INFECTION.getId())) return;
        PerkEntry entry = new PerkEntry(Perks.SHOAL_INCUBATION.getId(), 1.0F,
                Optional.empty(), Optional.empty());
        EquippedPerks updated = eq.add(entry);
        player.setData(DTAttachments.EQUIPPED_PERKS.get(), updated);
        Perks.SHOAL_INCUBATION.get().onEquip(player, entry);
        // The moment itself is unmistakable: a flurry of motes washes over the player with a
        // low chime, so the infection never reads as a silent stat change.
        double px = player.getX();
        double py = player.getY() + player.getBbHeight() * 0.6D;
        double pz = player.getZ();
        server.sendParticles(com.confect1on.dynetech.particle.DTParticles.SHOAL_MOTE.get(),
                px, py, pz, 80, 0.5D, 0.7D, 0.5D, 0.08D);
        server.playSound(null, px, py, pz,
                net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_RESONATE,
                net.minecraft.sounds.SoundSource.HOSTILE, 1.2F, 0.6F);
    }

    private void setState(byte next, int duration) {
        // The rare burst-and-freeze is rolled here on the server, on transitions into Hold,
        // so a startled Shoal can pull its partner into the same freeze and the pair pops
        // outward together instead of each client rolling its own dice.
        if (next == STATE_HOLD && this.state != STATE_HOLD
                && this.random.nextFloat() < 0.35F) {
            // The burst opens into a spread watch: the cloud parks its lobes apart and takes
            // in several nearby things at once, so this hold runs far longer than a normal
            // rest. Startles bypass this path and keep their quick freeze-then-flee timing.
            duration = 200 + this.random.nextInt(401);
            this.entityData.set(DATA_BURST, true);
            for (ShoalEntity partner : resolvePartners()) {
                if (partner.state != STATE_COLLAPSE && this.random.nextFloat() < 0.85F) {
                    partner.startleWith(duration);
                }
            }
        } else {
            this.entityData.set(DATA_BURST, false);
        }
        this.state = next;
        this.stateTicks = 0;
        this.stateDuration = duration;
        this.resting = false;
        this.entityData.set(DATA_STATE, next);
    }

    /** Direct startle from the partner's burst: freeze now, no re-roll, no echo back. */
    private void startleWith(int duration) {
        this.waypoints.clear();
        this.setDeltaMovement(Vec3.ZERO);
        this.state = STATE_HOLD;
        this.stateTicks = 0;
        this.stateDuration = duration;
        this.resting = false;
        this.entityData.set(DATA_STATE, STATE_HOLD);
        this.entityData.set(DATA_BURST, true);
    }

    private int rollHoldDuration() {
        return rollDuration(MIN_HOLD_TICKS, MAX_HOLD_TICKS);
    }

    /**
     * The mood dial behind the synced tempo. Same shapes, different pace: the client scales
     * its whole mote sim by this, so a tired swarm mills in slow motion and a spooked one
     * whirls. Rain wears it down; a deliberate rest in the rain sinks to a bare crawl.
     */
    private void updateTempo() {
        double tempo = this.energy;
        if (this.fleeFrom != null) tempo *= 1.9D;
        if (this.level().isRaining()) tempo *= 0.55D;
        if (this.resting) tempo *= this.level().isRaining() ? 0.35D : 0.5D;
        // Water reads as pressure: the motes still stir but the whole cloud drags. Nether
        // heat does the opposite, a warm quickening.
        if (this.isInWater()) tempo *= 0.35D;
        if (this.level().dimensionType().ultraWarm()) tempo *= 1.3D;
        float next = (float) Math.max(0.12D, Math.min(2.2D, tempo));
        if (Math.abs(next - this.entityData.get(DATA_TEMPO)) > 0.01F) {
            this.entityData.set(DATA_TEMPO, next);
        }
    }

    /**
     * Mood-scaled duration roll with a fat tail: usually a uniform pick scaled by this Shoal's
     * patience and the weather, occasionally nearly double, so the same animal sometimes stares
     * at a thing for a strangely long time.
     */
    private int rollDuration(int min, int max) {
        int base = min + this.random.nextInt(max - min + 1);
        if (this.random.nextFloat() < 0.08F) {
            base += base;
        }
        return Math.max(20, (int) (base * this.patience * weatherMood()));
    }

    /**
     * Rain makes it sluggish, night makes it restless. Small factors on purpose; the point is
     * that someone who watches Shoals long enough notices the rhythm, not a hard mode switch.
     */
    private double weatherMood() {
        double mood = 1.0D;
        if (this.level().isRaining()) mood *= 1.3D;
        if (this.level().isNight()) mood *= 0.8D;
        return mood;
    }

    /**
     * A handful of random samples looking for a light-emitting block nearby. Torches, lanterns,
     * glowstone, amethyst clusters: anything that shines is worth a visit.
     */
    private Vec3 findGlowSpot() {
        for (int i = 0; i < 10; i++) {
            BlockPos pos = BlockPos.containing(
                    this.getX() + (this.random.nextDouble() - 0.5D) * 24.0D,
                    this.getY() + (this.random.nextDouble() - 0.5D) * 10.0D,
                    this.getZ() + (this.random.nextDouble() - 0.5D) * 24.0D);
            if (this.level().getBlockState(pos).getLightEmission() >= 7) {
                return Vec3.atCenterOf(pos);
            }
        }
        return null;
    }

    /**
     * Sudden things spook it. A player sprinting straight into the cloud, a nearby lightning
     * strike, primed TNT about to blow, or a fast projectile skimming past. The startle
     * reuses the burst-and-freeze, spreads to the whole bonded group, and the next Hold
     * decision turns into genuine flight. The cooldown keeps a circling sprinter from
     * chain-spooking the swarm forever.
     */
    private void maybeStartle() {
        if (this.state == STATE_COLLAPSE) return;
        long time = this.level().getGameTime();
        if (time < this.startleCooldownUntil) return;
        Player rusher = null;
        for (Player p : this.level().getEntitiesOfClass(Player.class, this.getBoundingBox().inflate(5.0D))) {
            if (!p.isSpectator() && p.isSprinting()) {
                rusher = p;
                break;
            }
        }
        Vec3 threat = rusher != null ? rusher.position() : null;
        if (threat == null) {
            // Primed TNT reads as a countdown: the swarm bolts before the boom, not after.
            List<PrimedTnt> primed = this.level().getEntitiesOfClass(
                    PrimedTnt.class, this.getBoundingBox().inflate(20.0D));
            if (!primed.isEmpty()) threat = primed.get(0).position();
        }
        if (threat == null) {
            // An actual bolt entity, not the ambient storm, so the swarm visibly reacts to the
            // strike itself and flees away from where it landed.
            List<LightningBolt> bolts = this.level().getEntitiesOfClass(
                    LightningBolt.class, this.getBoundingBox().inflate(48.0D));
            if (!bolts.isEmpty()) threat = bolts.get(0).position();
        }
        if (threat == null) {
            // Fast projectile passing close - arrow, snowball, potion, egg. Slow lobs fall
            // below the threshold so a gentle underhand toss doesn't spook the swarm.
            for (Projectile proj : this.level().getEntitiesOfClass(
                    Projectile.class, this.getBoundingBox().inflate(5.0D))) {
                if (proj.getDeltaMovement().lengthSqr() > 0.25D) {
                    threat = proj.position();
                    break;
                }
            }
        }
        if (threat == null) return;
        this.startleCooldownUntil = time + 300L + this.random.nextInt(300);
        this.fleeFrom = threat;
        startleWith(15 + this.random.nextInt(20));
        for (ShoalEntity partner : resolvePartners()) {
            if (partner.state != STATE_COLLAPSE) {
                partner.fleeFrom = this.fleeFrom;
                partner.startleCooldownUntil = this.startleCooldownUntil;
                partner.startleWith(15 + this.random.nextInt(20));
            }
        }
    }

    /**
     * Reactive attention. The Hold loop only reconsiders the world when a pause runs out,
     * which read as long seconds of indifference to a drop landing right beside the cloud.
     * This is the fast path: something genuinely new and salient nearby, a fresh drop, a
     * fresh orb, a sprinting player, cuts the current activity short within about half a
     * second. The tendril points at it immediately; the engagement itself follows from the
     * short look-hold set here.
     */
    private void maybeNotice() {
        if (!this.primary || this.fleeFrom != null) return;
        if (this.state == STATE_COLLAPSE || this.state == STATE_TORNADO) return;
        long time = this.level().getGameTime();
        if (time < this.noticeCooldownUntil) return;
        Entity fresh = pickSalient();
        if (fresh == null) return;
        this.noticeCooldownUntil = time + 40L + this.random.nextInt(40);
        setInterest(fresh);
        interruptToLook(8 + this.random.nextInt(12));
    }

    /** Nearest newly-appeared temptation that is not already the current interest. */
    private Entity pickSalient() {
        AABB scan = this.getBoundingBox().inflate(ORBIT_NOTICE_RANGE);
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : this.level().getEntities(this, scan, this::isInteresting)) {
            if (e.getUUID().equals(this.interestUuid)) continue;
            boolean salient = isLure(e)
                    || (e instanceof ItemEntity item && item.getAge() < 60)
                    || (e instanceof ExperienceOrb && e.tickCount < 60)
                    || (e instanceof Player p && (p.isSprinting() || p.isCrouching()));
            if (!salient) continue;
            double d = this.distanceToSqr(e);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    /**
     * Ambient chime. A soft resonate at long random intervals turns a parked cloud from a
     * silent particle system into a thing that occasionally sings to itself. Skipped in
     * high-intensity states so it never fights the vacuum or the freeze for attention.
     */
    private void maybeAmbient() {
        if (!(this.level() instanceof ServerLevel server)) return;
        long time = this.level().getGameTime();
        if (time < this.ambientCooldownUntil) return;
        this.ambientCooldownUntil = time + 400L + this.random.nextInt(1600);
        if (this.state == STATE_COLLAPSE || this.state == STATE_TORNADO) return;
        if (this.random.nextFloat() < 0.6F) return;
        float pitch = 0.6F + this.random.nextFloat() * 0.5F;
        server.playSound(null, this.getX(), this.getY() + 0.7D, this.getZ(),
                net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                net.minecraft.sounds.SoundSource.AMBIENT, 0.35F, pitch);
    }

    /**
     * Once per dawn and dusk the cloud stirs: a longer chime and an inspect-in-place, so a
     * player watching the same swarm across days notices it greet the day and settle at
     * night. Guarded so a Shoal that appears at noon doesn't fire immediately.
     */
    private void maybeDayPhaseFlicker() {
        if (!(this.level() instanceof ServerLevel server)) return;
        long day = this.level().getDayTime() % 24000L;
        long phase = day < 12000L ? 0L : 1L;
        if (this.lastDayPhase == -1L) {
            this.lastDayPhase = phase;
            return;
        }
        if (phase == this.lastDayPhase) return;
        this.lastDayPhase = phase;
        server.playSound(null, this.getX(), this.getY() + 0.7D, this.getZ(),
                net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_RESONATE,
                net.minecraft.sounds.SoundSource.AMBIENT, 0.5F, phase == 0L ? 0.9F : 0.7F);
        if (this.primary && this.state != STATE_COLLAPSE && this.fleeFrom == null) {
            planInspect(null);
            this.stateDuration = 120 + this.random.nextInt(120);
        }
    }

    /**
     * If the currently-watched entity died in the last few ticks, the swarm keeps its focus
     * on the last known spot for a slow inspect and a low chime. Reads as the cloud mourning
     * or at least noticing the absence, rather than snapping to the next target.
     */
    private void maybeInterestDeath() {
        if (!this.primary || this.interestUuid == null) return;
        if (!(this.level() instanceof ServerLevel server)) return;
        Entity focus = server.getEntity(this.interestUuid);
        if (focus == null) {
            this.interestUuid = null;
            this.entityData.set(DATA_INTEREST, 0);
            return;
        }
        if (focus instanceof LivingEntity l && !l.isAlive() && this.pendingWake == null) {
            this.pendingWake = focus.position();
            server.playSound(null, focus.getX(), focus.getY() + 0.5D, focus.getZ(),
                    net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_HIT,
                    net.minecraft.sounds.SoundSource.AMBIENT, 0.4F, 0.55F);
        }
    }

    /**
     * The friendly-defender side of the swarm. If a mob nearby is targeting a player, the
     * Shoal picks the nearest offender as interest and drops a tornado on it. Damage is
     * applied in {@link #maybeDamageHostile()} while the tornado runs, so the visible funnel
     * carries the meaning of the attack. The infection loop still runs independently on
     * whoever the swarm happens to study, which is the point: it saves people from mobs it
     * hasn't recognised as hosts yet.
     */
    private void maybeDefendPlayer() {
        if (!this.primary || this.state == STATE_COLLAPSE || this.fleeFrom != null) return;
        if (this.state == STATE_TORNADO) return;
        AABB scan = this.getBoundingBox().inflate(20.0D);
        Mob best = null;
        double bestSq = Double.MAX_VALUE;
        for (Mob m : this.level().getEntitiesOfClass(Mob.class, scan)) {
            LivingEntity target = m.getTarget();
            if (!(target instanceof Player p) || !p.isAlive() || p.isSpectator()) continue;
            if (p.distanceToSqr(m) > 24.0D * 24.0D) continue;
            double d = this.distanceToSqr(m);
            if (d < bestSq) {
                bestSq = d;
                best = m;
            }
        }
        if (best == null) return;
        setInterest(best);
        planTornado(best);
    }

    /**
     * While the swarm is funnelling a hostile that's still targeting a player, deal a slow
     * bleed of damage. Small per-tick number so the tornado runs for its whole duration and
     * the mob visibly withers instead of vaporising, and gated on the target still hunting
     * so a mob that gives up is not chased down.
     */
    private void maybeDamageHostile() {
        if (this.state != STATE_TORNADO) return;
        if (this.tickCount % 8 != 0) return;
        if (!(resolveInterest() instanceof Mob mob) || !mob.isAlive()) return;
        if (!(mob.getTarget() instanceof Player)) return;
        mob.hurt(this.damageSources().magic(), 1.0F);
    }

    /**
     * Loose flocking between unbonded Shoals. On a Hold decision, if another primary Shoal is
     * within a comfortable range and neither is startled or collapsing, drift toward them so
     * two lone clouds visibly find each other over a minute or two of play.
     */
    private ShoalEntity findClusterPeer() {
        long time = this.level().getGameTime();
        if (time < this.clusterCooldownUntil) return null;
        AABB scan = this.getBoundingBox().inflate(32.0D);
        ShoalEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (ShoalEntity other : this.level().getEntitiesOfClass(ShoalEntity.class, scan)) {
            if (other == this) continue;
            if (other.state == STATE_COLLAPSE || other.fleeFrom != null) continue;
            if (this.partnerUuids.contains(other.getUUID())) continue;
            double d = this.distanceToSqr(other);
            if (d < bestSq) {
                bestSq = d;
                best = other;
            }
        }
        if (best != null) {
            this.clusterCooldownUntil = time + 600L + this.random.nextInt(600);
        }
        return best;
    }

    /**
     * Snap out of the current activity to face something. Deliberately bypasses setState so
     * the glance can never roll the startle burst; the client freeze that comes with it would
     * hold the motes rigid for seconds, the opposite of a quick reaction.
     */
    private void interruptToLook(int duration) {
        this.waypoints.clear();
        this.pendingInspect = null;
        this.setDeltaMovement(Vec3.ZERO);
        this.state = STATE_HOLD;
        this.stateTicks = 0;
        this.stateDuration = duration;
        this.resting = false;
        this.entityData.set(DATA_STATE, STATE_HOLD);
        this.entityData.set(DATA_BURST, false);
    }

    // Persistence: keep the Shoal loaded once it exists so its state machine doesn't get reset
    // by chunk unload cycles while a player is nearby but out of tracking range briefly.
    @Override
    public boolean shouldBeSaved() {
        return true;
    }

    // False, or the swarm becomes a solid box players bump into. Projectile hits still land
    // because those go through isPickable().
    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean displayFireAnimation() {
        return false;
    }

}
