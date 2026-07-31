package com.confect1on.dynetech.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public class VigilEntity extends Monster {

    private static final EntityDataAccessor<Byte> DATA_VARIANT =
            SynchedEntityData.defineId(VigilEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Boolean> DATA_FROZEN =
            SynchedEntityData.defineId(VigilEntity.class, EntityDataSerializers.BOOLEAN);

    private static final int SKIN_VARIANTS = 3;
    private static final int OBSERVE_RANGE = 48;
    private static final double CONE_DOT_THRESHOLD = 0.35;
    // Effective brightness at the Vigil's position. Full-moon night on the surface reads ~4;
    // an unlit cave reads 0. Threshold picked so moonlit outdoors still freezes it, but any
    // real darkness lets it move: you can't observe what you can't see.
    private static final int LIGHT_OBSERVE_THRESHOLD = 3;
    private static final double LIGHT_SEARCH_RADIUS = 8.0;

    private static final double TP_MIN = 300.0;
    private static final double TP_MAX = 800.0;
    private static final int TP_ATTEMPTS = 16;
    private static final double TOUCH_INFLATE = 0.3;

    public VigilEntity(EntityType<? extends VigilEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 30.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.55D)
                .add(Attributes.FOLLOW_RANGE, 64.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
                .add(Attributes.ATTACK_DAMAGE, 0.0D);
    }

    @Override
    protected void registerGoals() {
        // No FrozenGate wrapping: the freeze/unfreeze transition drives setNoAi, which is the
        // vanilla mechanism for "mob exists but does not tick goals or move controller". This
        // avoids a class of subtle bugs where a wrapped goal's state leaks across a freeze flip.
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // Extinguish before pursuit: this goal owns the MOVE flag whenever a nearby light is
        // closer than the player. It quietly gives up (canUse=false) when there's nothing to
        // snuff or the player has closed the gap, letting MeleeAttackGoal take over seamlessly.
        this.goalSelector.addGoal(1, new LightExtinguishGoal(this, LIGHT_SEARCH_RADIUS));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0D, false));
        // randomInterval=1 -> evaluate target acquisition every tick instead of the default
        // "roll 1-in-10 each eval". Combined with the target selector's 2-tick canUse cadence,
        // the vanilla default is ~1s of delay between look-away and pursuit; we want it instant.
        // mustSee=false: pursuit is unconditional once unfrozen. Observation is the player's job.
        this.targetSelector.addGoal(1,
                new NearestAttackableTargetGoal<>(this, Player.class, 1, false, false, null));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_VARIANT, (byte) 0);
        builder.define(DATA_FROZEN, true);
    }

    public int getVariant() {
        return this.entityData.get(DATA_VARIANT) & 0xFF;
    }

    public void setVariant(int variant) {
        this.entityData.set(DATA_VARIANT, (byte) (variant % SKIN_VARIANTS));
    }

    public boolean isFrozen() {
        return this.entityData.get(DATA_FROZEN);
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType reason, @Nullable SpawnGroupData spawnData) {
        this.setVariant(this.random.nextInt(SKIN_VARIANTS));
        return super.finalizeSpawn(level, difficulty, reason, spawnData);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte("Variant", (byte) this.getVariant());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Variant")) {
            this.setVariant(tag.getByte("Variant"));
        }
    }

    // Pickaxe-only kill mechanic: swords, arrows, fire, fall, mob attacks, everything else is
    // silently no-op. Phase Disk collapse doesn't route through hurt(), so it still works.
    // Admin/system damage (/kill, out-of-world) is always accepted so ops and the void loop
    // can still clean up stuck Vigils.
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypes.GENERIC_KILL) || source.is(DamageTypes.FELL_OUT_OF_WORLD)) {
            return super.hurt(source, amount);
        }
        if (source.getEntity() instanceof Player player
                && player.getMainHandItem().getItem() instanceof PickaxeItem) {
            boolean accepted = super.hurt(source, amount);
            if (accepted && this.level() instanceof ServerLevel server) {
                spawnStoneChipParticles(server);
            }
            return accepted;
        }
        return false;
    }

    private void spawnStoneChipParticles(ServerLevel server) {
        BlockParticleOption chips = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState());
        server.sendParticles(chips,
                this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ(),
                12, 0.3D, 0.6D, 0.3D, 0.1D);
    }

    @Override
    public boolean fireImmune() {
        return true;
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    // No knockback ever, even when damaged: statues don't flinch.
    @Override
    public void knockback(double strength, double x, double z) {
    }

    // Client-side hurt animation entry point. Vanilla sets hurtTime = hurtDuration = 10 here,
    // which is what LivingEntityRenderer reads to pack the red overlay UV. Leaving this empty
    // is the only reliable way to suppress the tint. Zeroing hurtTime on the server doesn't
    // reach the client, which gets its own hurtTime from ClientboundHurtAnimationPacket.
    @Override
    public void animateHurt(float yaw) {
    }

    @Override
    public void tick() {
        // Update frozen state BEFORE super.tick() so this tick's goal evaluation sees the right
        // isNoAi() value. Otherwise the mob would run a full AI step at the old state and only
        // freeze/unfreeze on the next tick, visible as a jerky one-tick lag on every transition.
        if (!this.level().isClientSide) {
            // Observation is checked every tick. The check is cheap (dot-product first, raycast
            // only if the cone passes) and the payoff is a zero-frame unfreeze: the tick a player
            // rotates the Vigil out of their FOV is the tick it starts moving.
            boolean nowFrozen = computeObserved();
            if (nowFrozen != this.entityData.get(DATA_FROZEN)) {
                this.entityData.set(DATA_FROZEN, nowFrozen);
            }
            // setNoAi is the vanilla "do not tick AI" toggle. Keep it strictly in sync with
            // frozen state every tick, not just on transitions, since mods or /data commands
            // might flip NoAI out from under us.
            boolean frozen = this.isFrozen();
            if (this.isNoAi() != frozen) {
                this.setNoAi(frozen);
                if (frozen) {
                    this.getNavigation().stop();
                    this.setTarget(null);
                } else {
                    // Pre-seed a target on the unfreeze edge so MeleeAttackGoal can start
                    // pathing THIS tick, no waiting for the target selector's next cadence.
                    Player nearest = this.level().getNearestPlayer(this, OBSERVE_RANGE);
                    if (nearest != null && !nearest.isSpectator() && nearest.isAlive()) {
                        this.setTarget(nearest);
                    }
                }
            }
            if (frozen) {
                // Freeze rotations against the previous tick so a Vigil mid-turn snaps into pose.
                this.setYRot(this.yRotO);
                this.setXRot(this.xRotO);
                this.yBodyRot = this.yBodyRotO;
                this.yHeadRot = this.yHeadRotO;
                // Kill horizontal drift; gravity handles Y.
                Vec3 v = this.getDeltaMovement();
                this.setDeltaMovement(0.0D, Math.min(v.y, 0.0D), 0.0D);
            }
        }

        super.tick();

        // Touch check runs on the server every tick regardless of AI state, in case the player
        // charges into the Vigil during the last tick before it re-freezes.
        if (!this.level().isClientSide && !this.isFrozen() && this.isAlive()) {
            checkTouch();
        }
    }

    @Override
    public int getMaxHeadYRot() {
        return this.isFrozen() ? 0 : super.getMaxHeadYRot();
    }

    @Override
    public int getMaxHeadXRot() {
        return this.isFrozen() ? 0 : super.getMaxHeadXRot();
    }

    @Override
    public int getHeadRotSpeed() {
        return this.isFrozen() ? 0 : super.getHeadRotSpeed();
    }

    // Snap to server-authoritative positions instead of gliding. A player who whips around
    // should never catch the Vigil mid-interpolation between two ticks of movement.
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        super.lerpTo(x, y, z, yRot, xRot, 1);
    }

    @Override
    public void lerpHeadTo(float yaw, int steps) {
        super.lerpHeadTo(yaw, 1);
    }

    // --- Observation ---

    private boolean computeObserved() {
        // Light-level gate: if the Vigil stands in near-darkness, no observation counts. The
        // player literally can't see the Vigil, so it hunts freely. Check both feet and eye
        // so a Vigil straddling a shadow line still reads as visible if either end is lit.
        int feetLight = this.level().getMaxLocalRawBrightness(this.blockPosition());
        int eyeLight = this.level().getMaxLocalRawBrightness(
                BlockPos.containing(this.getX(), this.getEyeY(), this.getZ()));
        if (Math.max(feetLight, eyeLight) < LIGHT_OBSERVE_THRESHOLD) {
            return false;
        }
        AABB scan = this.getBoundingBox().inflate(OBSERVE_RANGE);
        for (Player player : this.level().getEntitiesOfClass(Player.class, scan)) {
            if (player.isSpectator() || !player.isAlive()) continue;
            if (player.distanceToSqr(this) > (double) (OBSERVE_RANGE * OBSERVE_RANGE)) continue;
            if (isObservedBy(player)) {
                return true;
            }
        }
        return false;
    }

    private boolean isObservedBy(Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        Vec3 vigilEye = new Vec3(this.getX(), this.getEyeY(), this.getZ());
        Vec3 vigilCenter = this.getBoundingBox().getCenter();

        return (conePasses(eye, look, vigilEye) && rayClear(eye, vigilEye))
                || (conePasses(eye, look, vigilCenter) && rayClear(eye, vigilCenter));
    }

    private static boolean conePasses(Vec3 eye, Vec3 look, Vec3 target) {
        Vec3 dir = target.subtract(eye);
        double lenSq = dir.lengthSqr();
        if (lenSq < 1.0e-6) return true;
        return look.dot(dir.normalize()) >= CONE_DOT_THRESHOLD;
    }

    private boolean rayClear(Vec3 from, Vec3 to) {
        ClipContext ctx = new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this);
        BlockHitResult hit = this.level().clip(ctx);
        return hit.getType() == HitResult.Type.MISS;
    }

    // --- Touch → teleport payoff ---

    private void checkTouch() {
        AABB reach = this.getBoundingBox().inflate(TOUCH_INFLATE);
        for (Player p : this.level().getEntitiesOfClass(Player.class, reach)) {
            if (p.isCreative() || p.isSpectator()) continue;
            teleportPlayer(p);
            spendSelf();
            return;
        }
    }

    private void spendSelf() {
        if (this.level() instanceof ServerLevel server) {
            for (int i = 0; i < 24; i++) {
                double ox = (this.random.nextDouble() - 0.5D) * 0.6D;
                double oy = this.random.nextDouble() * 1.8D;
                double oz = (this.random.nextDouble() - 0.5D) * 0.6D;
                server.sendParticles(ParticleTypes.CRIT,
                        this.getX() + ox, this.getY() + oy, this.getZ() + oz,
                        1, 0.0D, 0.0D, 0.0D, 0.0D);
                server.sendParticles(ParticleTypes.POOF,
                        this.getX() + ox, this.getY() + oy, this.getZ() + oz,
                        1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
        this.discard();
    }

    private void teleportPlayer(Player player) {
        if (!(this.level() instanceof ServerLevel server)) return;
        RandomSource rng = this.getRandom();
        Vec3 origin = player.position();

        Vec3 dest = null;
        Vec3 fallback = null;
        for (int attempt = 0; attempt < TP_ATTEMPTS; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2.0D;
            double dist = TP_MIN + rng.nextDouble() * (TP_MAX - TP_MIN);
            int bx = Mth.floor(origin.x + Math.cos(angle) * dist);
            int bz = Mth.floor(origin.z + Math.sin(angle) * dist);
            // Only consider chunks that are already loaded. Force-loading via getChunk() would
            // trigger synchronous worldgen on the server tick, which can stall the loop for
            // hundreds of ms per fresh chunk. Missing all attempts is acceptable: the Vigil
            // still spends itself for the scare, the player just doesn't get relocated.
            if (!server.hasChunk(bx >> 4, bz >> 4)) continue;
            int y = server.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
            Vec3 candidate = new Vec3(bx + 0.5D, y, bz + 0.5D);
            fallback = candidate;
            if (isSafeDestination(server, bx, y, bz)) {
                dest = candidate;
                break;
            }
        }
        if (dest == null) dest = fallback;
        if (dest == null) return;

        server.playSound(null, origin.x, origin.y, origin.z,
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 1.0F);
        spawnPortalParticles(server, origin);

        if (player instanceof ServerPlayer sp) {
            sp.connection.teleport(dest.x, dest.y, dest.z, player.getYRot(), player.getXRot());
        } else {
            player.teleportTo(dest.x, dest.y, dest.z);
        }

        server.playSound(null, dest.x, dest.y, dest.z,
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 1.0F);
        spawnPortalParticles(server, dest);
    }

    private static boolean isSafeDestination(ServerLevel level, int x, int y, int z) {
        if (y <= level.getMinBuildHeight() + 2) return false;
        BlockPos groundPos = new BlockPos(x, y - 1, z);
        BlockPos feetPos = new BlockPos(x, y, z);
        BlockPos headPos = new BlockPos(x, y + 1, z);
        BlockState ground = level.getBlockState(groundPos);
        BlockState feet = level.getBlockState(feetPos);
        BlockState head = level.getBlockState(headPos);
        if (ground.isAir() || !ground.blocksMotion()) return false;
        if (isDangerous(ground) || isDangerous(feet) || isDangerous(head)) return false;
        if (feet.blocksMotion() || head.blocksMotion()) return false;
        return true;
    }

    private static boolean isDangerous(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA)
                || state.is(Blocks.LAVA)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.SWEET_BERRY_BUSH);
    }

    private static void spawnPortalParticles(ServerLevel level, Vec3 at) {
        level.sendParticles(ParticleTypes.PORTAL,
                at.x, at.y + 1.0D, at.z, 40,
                0.5D, 1.0D, 0.5D, 0.15D);
    }

    // --- Silent ambient/step; deepslate FX on hurt/death ---

    @Override
    protected @Nullable net.minecraft.sounds.SoundEvent getAmbientSound() {
        return null;
    }

    @Override
    protected @Nullable net.minecraft.sounds.SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.DEEPSLATE_HIT;
    }

    @Override
    protected @Nullable net.minecraft.sounds.SoundEvent getDeathSound() {
        return SoundEvents.DEEPSLATE_BREAK;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
    }
}
