package com.confect1on.dynetech.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.pehkui.PehkuiCompat;
import com.confect1on.dynetech.storage.ShrunkenStructureRef;
import com.confect1on.dynetech.storage.ShrunkenStructureStorage;
import com.confect1on.dynetech.storage.StructureBlob;

/**
 * A physical world entity holding a shrunken structure. Behaves item-like (gravity, water float).
 * Pehkui scales it (starts at 0.1×). If a grow disc hits it and its target reaches ~1.0×, the entity
 * removes itself and pastes the stored blocks at its position.
 */
public class ShrunkenStructureEntity extends Entity {

    private static final EntityDataAccessor<ItemStack> DATA_ITEM =
            SynchedEntityData.defineId(ShrunkenStructureEntity.class, EntityDataSerializers.ITEM_STACK);

    private static final float SPAWN_SCALE = 0.1F;
    private static final float RESTORE_SCALE = 0.98F; // trigger threshold — Pehkui interpolation may overshoot slightly

    public ShrunkenStructureEntity(EntityType<? extends ShrunkenStructureEntity> type, Level level) {
        super(type, level);
    }

    public ShrunkenStructureEntity(Level level, double x, double y, double z, ItemStack stack) {
        this(DTEntityTypes.SHRUNKEN_STRUCTURE.get(), level);
        this.setPos(x, y, z);
        this.setYRot(level.getRandom().nextFloat() * 360.0F);
        this.setItem(stack);
        if (!level.isClientSide) {
            PehkuiCompat.setScaleImmediate(this, SPAWN_SCALE);
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ITEM, ItemStack.EMPTY);
    }

    public ItemStack getItem() {
        return this.entityData.get(DATA_ITEM);
    }

    public void setItem(ItemStack stack) {
        this.entityData.set(DATA_ITEM, stack.copy());
    }

    @Override
    public void tick() {
        super.tick();

        // Item-like physics: gravity + drag, water buoyancy.
        // Pehkui's BASE scale multiplies applied motion by the entity's scale, so at 0.1× the world-space
        // fall would be 10× slower. Divide our velocity inputs by scale to cancel that out and keep normal
        // world-space fall speed.
        float scale = Math.max(PehkuiCompat.getScale(this), 0.05F);
        float targetScale = PehkuiCompat.getTargetScale(this);
        // Freeze physics while a shrink animation is running (target is small, current is still big).
        boolean shrinkAnimating = targetScale < 0.5F && scale > 0.5F;
        if (!shrinkAnimating) {
            Vec3 v = this.getDeltaMovement();
            boolean inWater = this.isInWater();
            if (!this.isNoGravity()) {
                double gravity = (inWater ? 0.01 : 0.04) / scale;
                this.setDeltaMovement(v.x, v.y - gravity, v.z);
            }
            this.move(net.minecraft.world.entity.MoverType.SELF, this.getDeltaMovement());

            double friction = this.onGround() ? 0.6 : 0.98;
            Vec3 nv = this.getDeltaMovement();
            this.setDeltaMovement(nv.x * friction, nv.y * (this.onGround() ? -0.5 : 0.98), nv.z * friction);

            if (inWater) {
                Vec3 wv = this.getDeltaMovement();
                this.setDeltaMovement(wv.x * 0.99, wv.y + 0.05 / scale, wv.z * 0.99); // slight buoyancy
            }
        }

        // Restore only when Pehkui is bringing us UP to normal size (target ≥ threshold).
        // Without checking the target, a shrinker-spawn animation from 1.0× → 0.1× would
        // trigger restore on tick 1 while scale is still ≈ 1.0.
        if (this.level() instanceof ServerLevel server) {
            if (scale >= RESTORE_SCALE && PehkuiCompat.getTargetScale(this) >= RESTORE_SCALE) {
                restoreAndDiscard(server);
            }
        }
    }

    private void restoreAndDiscard(ServerLevel server) {
        ItemStack stack = this.getItem();
        ShrunkenStructureRef ref = stack.get(DTDataComponents.SHRUNKEN_STRUCTURE.get());
        if (ref != null) {
            ShrunkenStructureStorage storage = ShrunkenStructureStorage.get(server.getServer());
            StructureBlob blob = storage.get(ref.id());
            if (blob != null) {
                // Origin such that the structure is centered horizontally on the entity, base at feet.
                BlockPos origin = new BlockPos(
                        (int) Math.floor(this.getX() - blob.size().getX() / 2.0),
                        (int) Math.floor(this.getY()),
                        (int) Math.floor(this.getZ() - blob.size().getZ() / 2.0));
                blob.paste(server, origin);
                storage.remove(ref.id());
            }
        }
        this.discard();
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (this.level().isClientSide) return InteractionResult.SUCCESS;
        // Only pick up while it's still tiny.
        float scale = PehkuiCompat.getScale(this);
        if (scale > 0.5F) return InteractionResult.PASS;

        ItemStack stack = this.getItem();
        if (!stack.isEmpty()) {
            if (player.getInventory().add(stack)) {
                this.discard();
                return InteractionResult.CONSUME;
            }
        }
        return InteractionResult.PASS;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Item")) {
            ItemStack.parse(this.registryAccess(), tag.getCompound("Item")).ifPresent(this::setItem);
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        ItemStack stack = this.getItem();
        if (!stack.isEmpty()) {
            tag.put("Item", stack.save(this.registryAccess()));
        }
    }

    @Override
    public boolean isPickable() {
        // ray-trace still hits us (so right-click interact works)
        return this.isAlive();
    }

    @Override
    public net.minecraft.world.entity.EntityDimensions getDimensions(net.minecraft.world.entity.Pose pose) {
        // Pehkui BASE scales the entity type's 1×1 hitbox by the current scale, so at 0.1× the
        // physical hitbox is 0.1×0.1 — nearly impossible to right-click or hit with a disc.
        // Enforce a minimum interaction size regardless of visual scale.
        net.minecraft.world.entity.EntityDimensions base = super.getDimensions(pose);
        float min = 0.5F;
        return net.minecraft.world.entity.EntityDimensions.scalable(
                Math.max(base.width(), min), Math.max(base.height(), min));
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        // Item-like: players walk through; no auto-crouch when tossing.
        return false;
    }

    @Override
    public boolean fireImmune() {
        return true;
    }

    @Override
    protected boolean canRide(Entity entity) {
        return false;
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        return false;
    }
}
