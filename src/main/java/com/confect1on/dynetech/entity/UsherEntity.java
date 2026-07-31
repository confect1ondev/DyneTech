package com.confect1on.dynetech.entity;

import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.dimension.UsherInterior;
import com.confect1on.dynetech.dimension.UsherSectionAllocator;
import com.confect1on.dynetech.item.DTItems;
import com.confect1on.dynetech.network.DTPayloads;
import com.confect1on.dynetech.sound.DTSounds;
import com.confect1on.dynetech.storage.ShrunkenStructureRef;
import com.confect1on.dynetech.storage.ShrunkenStructureStorage;
import com.confect1on.dynetech.storage.StructureBlob;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Neutral humanoid mob. Wanders slowly, and every 2-5 minutes (only counting down while a
 * valid player is within 24 blocks) fires a Departure: a 6s charge with disco shimmer, a
 * black-and-purple blast ring, and a cross-dim teleport of every non-creative living entity
 * within 10 blocks into the Usher's private pocket-dimension section.
 *
 * <p>Damage-immune to everything except admin kills. Phase Disks are the only player path to
 * kill one, and they route through {@link #collapse}, which dumps the interior as a Shrunken
 * Structure and releases every captive back into the world.
 *
 * <p>Persists once it owns a section: losing the entity would orphan the section.
 */
public class UsherEntity extends PathfinderMob {

    // Charge audio (~15s) intentionally outlasts this window; the tail is cool-off ambience.
    public static final int CHARGE_TICKS = 120;
    public static final int COOLDOWN_TICKS = 10;
    // Delay between blast and teleport so the purple pulse renders on caught entities before
    // they vanish from the outside world. Single pulse cycle is ~18 client ticks.
    public static final int CATCH_DELAY_TICKS = 10;
    public static final int MIN_DEPARTURE_TICKS = 20 * 60 * 2;
    public static final int MAX_DEPARTURE_TICKS = 20 * 60 * 5;
    public static final double PLAYER_GATE_RANGE = 24.0;
    public static final double CATCH_RADIUS = 10.0;
    public static final double RELEASE_RADIUS = 10.0;
    public static final float BLAST_INNER_RADIUS = 5.0F;
    public static final float BLAST_OUTER_RADIUS = 10.0F;
    // Pulses expire after ~18 client ticks; re-trigger every 10 to keep them continuous.
    private static final int DISCO_RETRIGGER_TICKS = 10;
    private static final float EASTEREGG_CHANCE = 0.30F;
    private static final int CATCH_FLASH_RGB = 0x7B2FBE;

    private static final EntityDataAccessor<Byte> DATA_STATE =
            SynchedEntityData.defineId(UsherEntity.class, EntityDataSerializers.BYTE);

    public static final byte STATE_WANDERING = 0;
    public static final byte STATE_CHARGING = 1;
    public static final byte STATE_CATCHING = 2;
    public static final byte STATE_COOLDOWN = 3;

    private int departureTimer;
    private int chargeTicks;
    private int catchingTicks;
    private int cooldownTicks;
    private int sectionIndex = -1;
    private boolean useEasterEgg;
    // In-memory only. A restart mid-catch just aborts the pending teleport; harmless.
    private final List<UUID> pendingCatchUuids = new ArrayList<>();

    public UsherEntity(EntityType<? extends UsherEntity> type, Level level) {
        super(type, level);
        this.departureTimer = rollDepartureTimer();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 60.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        // No target selectors: the Usher never picks a target and never attacks.
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_STATE, STATE_WANDERING);
    }

    public byte getUsherState() {
        return this.entityData.get(DATA_STATE);
    }

    private void setUsherState(byte state) {
        this.entityData.set(DATA_STATE, state);
    }

    public int getSectionIndex() {
        return this.sectionIndex;
    }

    private int rollDepartureTimer() {
        return MIN_DEPARTURE_TICKS + this.random.nextInt(MAX_DEPARTURE_TICKS - MIN_DEPARTURE_TICKS + 1);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte("UsherState", this.getUsherState());
        tag.putInt("DepartureTimer", this.departureTimer);
        tag.putInt("ChargeTicks", this.chargeTicks);
        tag.putInt("CatchingTicks", this.catchingTicks);
        tag.putInt("CooldownTicks", this.cooldownTicks);
        tag.putInt("SectionIndex", this.sectionIndex);
        tag.putBoolean("UseEasterEgg", this.useEasterEgg);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("UsherState")) this.setUsherState(tag.getByte("UsherState"));
        if (tag.contains("DepartureTimer")) this.departureTimer = tag.getInt("DepartureTimer");
        if (tag.contains("ChargeTicks")) this.chargeTicks = tag.getInt("ChargeTicks");
        if (tag.contains("CatchingTicks")) this.catchingTicks = tag.getInt("CatchingTicks");
        if (tag.contains("CooldownTicks")) this.cooldownTicks = tag.getInt("CooldownTicks");
        if (tag.contains("SectionIndex")) this.sectionIndex = tag.getInt("SectionIndex");
        if (tag.contains("UseEasterEgg")) this.useEasterEgg = tag.getBoolean("UseEasterEgg");
    }

    // The Usher pins itself for the whole charge+catch sequence, so ignore knockback there.
    @Override
    public void knockback(double strength, double x, double z) {
        byte s = this.getUsherState();
        if (s == STATE_CHARGING || s == STATE_CATCHING) return;
        super.knockback(strength, x, z);
    }

    /**
     * Damage-immune except for admin/void kills. Phase Disks call {@link #collapse} directly
     * rather than going through hurt, so this is the only chokepoint and the answer is no,
     * nothing else damages an Usher. A rejected hit still trips the charge on the way out.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypes.GENERIC_KILL) || source.is(DamageTypes.FELL_OUT_OF_WORLD)) {
            boolean accepted = super.hurt(source, amount);
            if (accepted && !this.level().isClientSide && !this.isAlive()
                    && this.getUsherState() == STATE_CHARGING) {
                this.setUsherState(STATE_WANDERING);
                this.chargeTicks = 0;
            }
            return accepted;
        }
        if (!this.level().isClientSide && this.isAlive()
                && this.getUsherState() == STATE_WANDERING
                && this.level() instanceof ServerLevel serverLevel) {
            startCharge(serverLevel);
        }
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) return;
        if (!(this.level() instanceof ServerLevel serverLevel)) return;

        byte state = this.getUsherState();
        switch (state) {
            case STATE_WANDERING -> tickWandering(serverLevel);
            case STATE_CHARGING -> tickCharging(serverLevel);
            case STATE_CATCHING -> tickCatching(serverLevel);
            case STATE_COOLDOWN -> tickCooldown();
        }
    }

    private void tickWandering(ServerLevel serverLevel) {
        // Don't burn a Departure in an empty forest: gate the timer on nearby players.
        if (!hasValidPlayerNearby(PLAYER_GATE_RANGE)) return;
        if (--this.departureTimer > 0) return;
        startCharge(serverLevel);
    }

    /** Locks the Usher in place and kicks off the charge audio + disco shimmer. */
    private void startCharge(ServerLevel serverLevel) {
        setUsherState(STATE_CHARGING);
        this.chargeTicks = 0;
        this.useEasterEgg = this.random.nextFloat() < EASTEREGG_CHANCE;
        this.getNavigation().stop();
        this.setDeltaMovement(0, this.getDeltaMovement().y, 0);
        this.setNoAi(true);
        serverLevel.playSound(null, this.getX(), this.getY(), this.getZ(),
                DTSounds.USHER_CHARGE.get(), SoundSource.HOSTILE, 1.5F, 1.0F);
        triggerDisco();
    }

    private void tickCharging(ServerLevel serverLevel) {
        this.chargeTicks++;
        this.setDeltaMovement(0, this.getDeltaMovement().y, 0);
        if (this.chargeTicks % DISCO_RETRIGGER_TICKS == 0) {
            triggerDisco();
        }
        if (this.chargeTicks < CHARGE_TICKS) return;

        // Fire the ring visual and stage catches. Sounds and the teleport itself are
        // deferred by CATCH_DELAY_TICKS so the purple flash renders in the outside world
        // before entities vanish, and both sounds fire together after the teleport.
        broadcastBlastVisual();
        stageCatches(serverLevel);
        setUsherState(STATE_CATCHING);
        this.catchingTicks = 0;
    }

    private void tickCatching(ServerLevel serverLevel) {
        this.setDeltaMovement(0, this.getDeltaMovement().y, 0);
        if (++this.catchingTicks < CATCH_DELAY_TICKS) return;

        SoundEvent blastSound = this.useEasterEgg ? DTSounds.USHER_EASTEREGG.get() : DTSounds.USHER_BLAST.get();
        deliverPending(serverLevel);
        this.pendingCatchUuids.clear();

        // Play at both the outside spot and the pocket-dim arrival spot in the same tick,
        // so witnesses and arriving captives hear the payoff simultaneously.
        serverLevel.playSound(null, this.getX(), this.getY(), this.getZ(),
                blastSound, SoundSource.HOSTILE, 1.5F, 1.0F);
        if (this.sectionIndex >= 0) {
            ServerLevel interior = serverLevel.getServer().getLevel(UsherInterior.DIMENSION);
            if (interior != null) {
                BlockPos center = UsherSectionAllocator.spawnPos(this.sectionIndex);
                interior.playSound(null, center.getX() + 0.5D, center.getY(), center.getZ() + 0.5D,
                        blastSound, SoundSource.HOSTILE, 1.5F, 1.0F);
            }
        }

        setUsherState(STATE_COOLDOWN);
        this.cooldownTicks = 0;
        this.setNoAi(false);
    }

    private void tickCooldown() {
        if (++this.cooldownTicks < COOLDOWN_TICKS) return;
        setUsherState(STATE_WANDERING);
        this.departureTimer = rollDepartureTimer();
        this.cooldownTicks = 0;
    }

    private boolean hasValidPlayerNearby(double range) {
        AABB scan = this.getBoundingBox().inflate(range);
        for (Player p : this.level().getEntitiesOfClass(Player.class, scan)) {
            if (p.isSpectator() || p.isCreative()) continue;
            if (p.distanceToSqr(this) <= range * range) return true;
        }
        return false;
    }

    private void triggerDisco() {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(this, new DTPayloads.SpawnPulses(this.getId()));
    }

    private void broadcastBlastVisual() {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(this,
                new DTPayloads.UsherBlast(this.getId(), BLAST_INNER_RADIUS, BLAST_OUTER_RADIUS));
    }

    /**
     * Selects every eligible living entity inside {@link #CATCH_RADIUS} (sphere, no LOS
     * check), flashes each purple, and queues them for teleport in {@link #tickCatching}.
     * Excludes self, other Ushers, dead entities, creative/spectator players, and anything
     * already in the pocket dimension. Lazily allocates the section on first catch.
     */
    private void stageCatches(ServerLevel serverLevel) {
        AABB catchBox = this.getBoundingBox().inflate(CATCH_RADIUS);
        double radiusSq = CATCH_RADIUS * CATCH_RADIUS;
        MinecraftServer server = serverLevel.getServer();
        this.pendingCatchUuids.clear();
        for (LivingEntity target : this.level().getEntitiesOfClass(LivingEntity.class, catchBox)) {
            if (target == this) continue;
            if (target instanceof UsherEntity) continue;
            if (!target.isAlive()) continue;
            if (target instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
            if (target.level().dimension() == UsherInterior.DIMENSION) continue;
            if (target.distanceToSqr(this) > radiusSq) continue;

            ensureSection(server);
            if (this.sectionIndex < 0) return;
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(target,
                    new DTPayloads.SpawnPulsesColored(target.getId(), CATCH_FLASH_RGB));
            this.pendingCatchUuids.add(target.getUUID());
        }
    }

    private void ensureSection(MinecraftServer server) {
        if (this.sectionIndex >= 0) return;
        UsherSectionAllocator alloc = UsherSectionAllocator.get(server);
        this.sectionIndex = alloc.allocateAndBuild(this.getUUID(), server);
        // Losing the Usher from here on would orphan the section.
        this.setPersistenceRequired();
    }

    private void deliverPending(ServerLevel serverLevel) {
        MinecraftServer server = serverLevel.getServer();
        ServerLevel interior = server.getLevel(UsherInterior.DIMENSION);
        if (interior == null) return;
        BlockPos spawn = UsherSectionAllocator.spawnPos(this.sectionIndex);
        double x = spawn.getX() + 0.5D;
        double y = spawn.getY();
        double z = spawn.getZ() + 0.5D;

        for (UUID uuid : this.pendingCatchUuids) {
            Entity src = serverLevel.getEntity(uuid);
            if (!(src instanceof LivingEntity le) || !le.isAlive()) continue;
            if (le.level().dimension() == UsherInterior.DIMENSION) continue;

            if (!le.teleportTo(interior, x, y, z, Set.of(), le.getYRot(), le.getXRot())) continue;

            // ServerPlayers keep the same entity id cross-dim; non-players are recreated with
            // a new id under the same UUID. Look up the destination entity for the re-flash.
            Entity dst = interior.getEntity(uuid);
            if (dst != null) {
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(dst,
                        new DTPayloads.SpawnPulsesColored(dst.getId(), CATCH_FLASH_RGB));
            }
            if (dst instanceof ServerPlayer sp) {
                sp.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0, false, false, true));
            }
        }
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        if (this.sectionIndex >= 0) return false;
        return super.removeWhenFarAway(distanceToClosestPlayer);
    }

    /**
     * Called by a Phase Disk hit. Releases every captive from the pocket-dim section,
     * drops the interior as a Shrunken Structure, wipes the section, deallocates it, and
     * discards the Usher. If no section was ever allocated the Usher just vanishes.
     */
    public void collapse(ServerLevel deathLevel, Vec3 deathPos) {
        MinecraftServer server = deathLevel.getServer();

        if (this.sectionIndex >= 0) {
            ServerLevel interior = server.getLevel(UsherInterior.DIMENSION);
            if (interior != null) {
                int cx = UsherSectionAllocator.sectionCenterX(this.sectionIndex);
                BlockPos min = new BlockPos(cx - 15,
                        UsherSectionAllocator.floorY() + 1,
                        UsherSectionAllocator.CENTER_Z - 15);
                BlockPos max = new BlockPos(cx + 14,
                        UsherSectionAllocator.ceilingY() - 1,
                        UsherSectionAllocator.CENTER_Z + 14);

                releaseCaptives(interior, min, max, deathLevel, deathPos);
                dropInteriorStructure(server, interior, min, max, deathLevel, deathPos);
                UsherSectionAllocator.demolish(interior, this.sectionIndex);
                UsherSectionAllocator.setSectionForced(interior, this.sectionIndex, false);
                UsherSectionAllocator.get(server).deallocate(this.getUUID());
            }
        }

        deathLevel.sendParticles(ParticleTypes.PORTAL,
                deathPos.x, deathPos.y + 1.0D, deathPos.z, 40, 0.5D, 1.0D, 0.5D, 0.3D);
        deathLevel.sendParticles(ParticleTypes.POOF,
                deathPos.x, deathPos.y + 0.9D, deathPos.z, 20, 0.4D, 0.6D, 0.4D, 0.05D);
        deathLevel.playSound(null, deathPos.x, deathPos.y, deathPos.z,
                SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.HOSTILE, 1.0F, 0.6F);

        this.discard();
    }

    private void releaseCaptives(ServerLevel interior, BlockPos min, BlockPos max,
                                 ServerLevel deathLevel, Vec3 deathPos) {
        AABB region = new AABB(min.getX(), min.getY(), min.getZ(),
                max.getX() + 1, max.getY() + 1, max.getZ() + 1);
        // Snapshot before teleporting: cross-dim teleport mutates the source entity list.
        List<LivingEntity> captives = new ArrayList<>(
                interior.getEntitiesOfClass(LivingEntity.class, region));
        for (LivingEntity captive : captives) {
            if (captive == this || !captive.isAlive()) continue;
            Vec3 dest = pickReleaseSpot(deathLevel, deathPos);
            captive.teleportTo(deathLevel, dest.x, dest.y, dest.z, Set.of(),
                    captive.getYRot(), captive.getXRot());
        }
    }

    private Vec3 pickReleaseSpot(ServerLevel deathLevel, Vec3 origin) {
        double angle = this.random.nextDouble() * Math.PI * 2.0D;
        double dist = this.random.nextDouble() * RELEASE_RADIUS;
        double rx = origin.x + Math.cos(angle) * dist;
        double rz = origin.z + Math.sin(angle) * dist;
        int y = deathLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(rx), Mth.floor(rz));
        // Fall back to the death Y on weird chunk edges rather than dropping mobs at Y=320.
        double ry = y > deathLevel.getMaxBuildHeight() - 2 ? origin.y : y;
        return new Vec3(rx, ry, rz);
    }

    private void dropInteriorStructure(MinecraftServer server, ServerLevel interior,
                                       BlockPos min, BlockPos max,
                                       ServerLevel deathLevel, Vec3 deathPos) {
        StructureBlob blob = StructureBlob.capture(interior, min, max);
        if (blob.isEmpty()) return;
        // Skip air on paste so a regrow doesn't blast a mob-sized hole around the target.
        blob = blob.withSkipAirOnPaste();
        UUID id = ShrunkenStructureStorage.get(server).store(blob);
        ItemStack stack = new ItemStack(DTItems.SHRUNKEN_STRUCTURE.get());
        stack.set(DTDataComponents.SHRUNKEN_STRUCTURE.get(), new ShrunkenStructureRef(id, blob.size()));
        ItemEntity drop = new ItemEntity(deathLevel, deathPos.x, deathPos.y + 0.5D, deathPos.z, stack);
        drop.setDeltaMovement(0, 0.2D, 0);
        drop.setDefaultPickUpDelay();
        deathLevel.addFreshEntity(drop);
    }

    @Override
    @Nullable
    protected SoundEvent getAmbientSound() {
        return null;
    }
}
