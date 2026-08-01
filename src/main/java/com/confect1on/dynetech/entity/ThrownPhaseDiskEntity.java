package com.confect1on.dynetech.entity;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.confect1on.dynetech.item.DTItems;

public class ThrownPhaseDiskEntity extends ThrowableItemProjectile {

    // Recoverable-on-miss: the disk is expensive enough that losing one to a stray throw would
    // feel awful. Cap airtime anyway so a disk lobbed into loaded-chunk void gets cleaned up.
    private static final int MAX_LIFETIME_TICKS = 20 * 60;

    public ThrownPhaseDiskEntity(EntityType<? extends ThrownPhaseDiskEntity> type, Level level) {
        super(type, level);
    }

    public ThrownPhaseDiskEntity(Level level, LivingEntity thrower, ItemStack stack) {
        super(DTEntityTypes.THROWN_PHASE_DISK.get(), thrower, level);
        this.setItem(stack.copyWithCount(1));
    }

    @Override
    protected Item getDefaultItem() {
        return DTItems.PHASE_DISK.get();
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && this.tickCount > MAX_LIFETIME_TICKS) {
            dropSelfAt(this.position());
            this.discard();
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (this.level().isClientSide) return;

        Entity target = result.getEntity();
        if (handleEntityHit(target)) {
            this.discard();
            return;
        }

        // Non-Vigil entity: minor thrown-object damage, then drop the disk for pickup.
        target.hurt(this.damageSources().thrown(this, this.getOwner()), 1.0F);
        dropSelfAt(result.getLocation());
        this.discard();
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        if (this.level().isClientSide) return;
        // Never lost on a miss; always recoverable at the impact point.
        dropSelfAt(result.getLocation());
        this.discard();
    }

    // Kept intentionally dispatch-friendly. New reactive targets (Vigil variants, decoys, etc.)
    // slot in here without touching onHitEntity's control flow.
    private boolean handleEntityHit(Entity target) {
        if (target instanceof VigilEntity vigil) {
            collapseVigil(vigil);
            return true;
        }
        if (target instanceof UsherEntity usher) {
            // The Phase Disk is the only thing that kills an Usher; hurt() rejects everything
            // else. Delegate so blob capture and captive release live with the entity.
            if (usher.level() instanceof ServerLevel server) {
                usher.collapse(server, usher.position());
            }
            return true;
        }
        if (target instanceof ShoalEntity shoal) {
            // The Shoal cannot be killed any other way. The disk is consumed on this path,
            // matching the "weapon, full stop" reading in the spec.
            shoal.collapse();
            return true;
        }
        return false;
    }

    private void collapseVigil(VigilEntity vigil) {
        Level level = vigil.level();
        if (level instanceof ServerLevel server) {
            double x = vigil.getX();
            double y = vigil.getY() + vigil.getBbHeight() * 0.5D;
            double z = vigil.getZ();
            BlockParticleOption debris = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState());
            server.sendParticles(debris, x, y, z, 60, 0.4D, 0.9D, 0.4D, 0.15D);
            server.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            server.sendParticles(ParticleTypes.FLASH, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            server.playSound(null, x, y, z, SoundEvents.STONE_BREAK, SoundSource.HOSTILE, 1.2F, 0.7F);
            server.playSound(null, x, y, z, SoundEvents.DEEPSLATE_BREAK, SoundSource.HOSTILE, 1.0F, 0.8F);
        }
        vigil.discard();
    }

    private void dropSelfAt(Vec3 pos) {
        ItemStack stack = this.getItem();
        if (stack.isEmpty()) stack = new ItemStack(DTItems.PHASE_DISK.get());
        ItemEntity dropped = new ItemEntity(this.level(), pos.x, pos.y, pos.z, stack.copyWithCount(1));
        dropped.setDeltaMovement(Vec3.ZERO);
        dropped.setDefaultPickUpDelay();
        this.level().addFreshEntity(dropped);
    }
}
