package com.confect1on.dynetech.entity;

import com.confect1on.dynetech.config.DTConfig;
import com.confect1on.dynetech.gene.DTAttachments;
import com.confect1on.dynetech.gene.EquippedPerks;
import com.confect1on.dynetech.gene.PerkEntry;
import com.confect1on.dynetech.gene.Perks;
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
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
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
        if (this.state == STATE_COLLAPSE) return;
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
            partner.collapse();
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
        ShoalEntity partner = resolvePartner();
        if (!this.primary && partner != null) {
            followPrimary(partner);
            return;
        }

        Entity interest = pickInterest();
        this.interestUuid = interest == null ? null : interest.getUUID();

        // Nothing catches its eye and the neighborhood is all old news: leave. A long Flow to
        // somewhere fresh reads much more alive than milling around bored things.
        if (interest == null && hasActiveBoredom() && this.random.nextFloat() < 0.6F) {
            planFlow(rollDestination(FLOW_MIN_RANGE, FLOW_MAX_RANGE));
            return;
        }

        if (interest instanceof ItemEntity
                && this.distanceToSqr(interest) < ORBIT_NOTICE_RANGE * ORBIT_NOTICE_RANGE) {
            // Three ways to fuss over an item so the ring isn't the default answer: the tight
            // swirl, a lazy braided lap past it, or just hanging nearby watching. A lure always
            // engages but still varies which.
            float r = isLure(interest) ? this.random.nextFloat() * 0.75F : this.random.nextFloat();
            if (r < 0.25F) {
                planOrbit(interest);
                return;
            }
            if (r < 0.50F) {
                planFlowLoop(interest);
                return;
            }
            if (r < 0.75F) {
                planInspect(interest);
                return;
            }
        }
        if (interest != null && !(interest instanceof ItemEntity)) {
            double distSq = this.distanceToSqr(interest);
            if (distSq < INSPECT_NOTICE_RANGE * INSPECT_NOTICE_RANGE) {
                float r = this.random.nextFloat();
                // Rarely, the whole swarm winds itself around the creature as a funnel.
                if (r < 0.06F) {
                    planTornado(interest);
                    return;
                }
                if (r < 0.45F) {
                    planInspect(interest);
                    return;
                }
                if (r < 0.70F) {
                    planFlowLoop(interest);
                    return;
                }
            }
            if (distSq > FLOW_MIN_RANGE * FLOW_MIN_RANGE && this.random.nextFloat() < 0.6F) {
                planFlow(orbitPoint(interest));
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
        if (roll < 0.18F) {
            setState(STATE_HOLD, rollHoldDuration());
        } else if (roll < 0.32F) {
            planRest();
        } else if (roll < 0.48F) {
            planInspect(null);
        } else if (roll < 0.76F) {
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
        int duration = 300 + this.random.nextInt(300);
        if (this.random.nextBoolean()) {
            setState(STATE_HOLD, duration);
        } else {
            planInspect(null);
            this.stateDuration = duration;
        }
        this.resting = true;
    }

    private void tickDrift() {
        if (this.waypoints.isEmpty()) {
            setState(STATE_HOLD, rollHoldDuration());
            return;
        }
        moveTowardCurrentWaypoint(DRIFT_SPEED);
        if (this.stateTicks >= this.stateDuration) {
            setState(STATE_HOLD, rollHoldDuration());
        }
    }

    private void tickFlow() {
        if (this.waypoints.isEmpty()) {
            setState(STATE_HOLD, rollHoldDuration());
            return;
        }
        moveTowardCurrentWaypoint(FLOW_SPEED);
        if (this.stateTicks >= this.stateDuration) {
            setState(STATE_HOLD, rollHoldDuration());
        }
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
        this.interestUuid = target.getUUID();
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
        this.interestUuid = target.getUUID();
        this.orbitRadius = 1.5D + this.random.nextDouble() * 1.2D;
        this.orbitAngular = (0.03D + this.random.nextDouble() * 0.05D)
                * (this.random.nextBoolean() ? 1.0D : -1.0D);
        Vec3 toSelf = this.position().subtract(target.position());
        this.orbitAngle = Math.atan2(toSelf.z, toSelf.x);
        setState(STATE_ORBIT, MIN_ORBIT_TICKS + this.random.nextInt(MAX_ORBIT_TICKS - MIN_ORBIT_TICKS + 1));
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
        this.interestUuid = target.getUUID();
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
            this.interestUuid = focus.getUUID();
            this.inspectCenter = standoffPoint(focus);
        } else {
            this.interestUuid = null;
            this.inspectCenter = this.position();
        }
        this.microTarget = rollMicroTarget();
        setState(STATE_INSPECT, MIN_INSPECT_TICKS + this.random.nextInt(MAX_INSPECT_TICKS - MIN_INSPECT_TICKS + 1));
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
        if (this.random.nextFloat() < 0.3F * this.engagement) {
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
        return 0.55D + this.random.nextDouble() * 0.9D;
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
            double weight = isLure(e) ? 0.05D
                    : e instanceof ItemEntity ? 0.5D : e instanceof Player ? 0.8D : 1.0D;
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
        if (e instanceof ItemEntity) return true;
        if (e instanceof net.minecraft.world.entity.LivingEntity l) return l.isAlive();
        return false;
    }

    private void applyContactInfection() {
        if (!(this.level() instanceof ServerLevel server)) return;
        double infectRadius = DTConfig.SHOAL_INFECT_RADIUS.get();
        AABB range = new AABB(this.getX() - infectRadius, this.getY() - infectRadius, this.getZ() - infectRadius,
                this.getX() + infectRadius, this.getY() + infectRadius, this.getZ() + infectRadius);
        List<Player> nearby = this.level().getEntitiesOfClass(Player.class, range);
        for (Player player : nearby) {
            if (player.isSpectator() || player.isCreative()) continue;
            if (player.distanceToSqr(this) > infectRadius * infectRadius) continue;
            infect(server, player);
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
    }

    private void setState(byte next, int duration) {
        // The rare burst-and-freeze is rolled here on the server, on transitions into Hold,
        // so a startled Shoal can pull its partner into the same freeze and the pair pops
        // outward together instead of each client rolling its own dice.
        if (next == STATE_HOLD && this.state != STATE_HOLD
                && this.random.nextFloat() < 0.15F) {
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
        return MIN_HOLD_TICKS + this.random.nextInt(MAX_HOLD_TICKS - MIN_HOLD_TICKS + 1);
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
