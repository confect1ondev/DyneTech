package com.confect1on.dynetech.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.pehkui.PehkuiCompat;
import com.confect1on.dynetech.storage.ShrunkenEntityRef;

import java.util.Optional;

/**
 * World entity holding a shrunken (non-player) mob captured by the Tissue Compression Eliminator.
 * Mirrors {@link ShrunkenStructureEntity}'s physics + Pehkui-driven regrow trigger; the only real
 * difference is what "restore" means (respawn the captured entity vs paste a structure blob).
 */
public class ShrunkenEntityEntity extends Entity implements ItemSupplier {

    private static final EntityDataAccessor<ItemStack> DATA_ITEM =
            SynchedEntityData.defineId(ShrunkenEntityEntity.class, EntityDataSerializers.ITEM_STACK);

    private static final float SPAWN_SCALE = 0.1F;
    private static final float RESTORE_SCALE = 0.98F;

    public ShrunkenEntityEntity(EntityType<? extends ShrunkenEntityEntity> type, Level level) {
        super(type, level);
    }

    public ShrunkenEntityEntity(Level level, double x, double y, double z, ItemStack stack) {
        this(DTEntityTypes.SHRUNKEN_ENTITY.get(), level);
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

    @Override
    public ItemStack getItem() {
        return this.entityData.get(DATA_ITEM);
    }

    public void setItem(ItemStack stack) {
        this.entityData.set(DATA_ITEM, stack.copy());
    }

    @Override
    public void tick() {
        super.tick();

        // See ShrunkenStructureEntity#tick for the reasoning behind scaling gravity/drag by 1/scale.
        float scale = Math.max(PehkuiCompat.getScale(this), 0.05F);
        float targetScale = PehkuiCompat.getTargetScale(this);
        boolean shrinkAnimating = targetScale < 0.5F && scale > 0.5F;
        if (!shrinkAnimating) {
            Vec3 v = this.getDeltaMovement();
            boolean inWater = this.isInWater();
            if (!this.isNoGravity()) {
                double gravity = (inWater ? 0.01 : 0.04) / scale;
                this.setDeltaMovement(v.x, v.y - gravity, v.z);
            }
            this.move(MoverType.SELF, this.getDeltaMovement());

            double friction = this.onGround() ? 0.6 : 0.98;
            Vec3 nv = this.getDeltaMovement();
            this.setDeltaMovement(nv.x * friction, nv.y * (this.onGround() ? -0.5 : 0.98), nv.z * friction);

            if (inWater) {
                Vec3 wv = this.getDeltaMovement();
                this.setDeltaMovement(wv.x * 0.99, wv.y + 0.05 / scale, wv.z * 0.99);
            }
        }

        if (this.level() instanceof ServerLevel server) {
            if (scale >= RESTORE_SCALE && PehkuiCompat.getTargetScale(this) >= RESTORE_SCALE) {
                restoreAndDiscard(server);
            }
        }
    }

    private void restoreAndDiscard(ServerLevel server) {
        ItemStack stack = this.getItem();
        ShrunkenEntityRef ref = stack.get(DTDataComponents.SHRUNKEN_ENTITY.get());
        if (ref != null && !ref.lethal()) {
            // Copy so the item's original NBT stays intact if the spawn fails.
            CompoundTag tag = ref.data().copy();
            // Strip UUID: item duplication in creative or via containers would otherwise resurrect
            // the same mob multiple times with a UUID collision, and the vanilla server tolerates
            // the first but silently drops the rest.
            tag.remove("UUID");
            // Ensure the entity id is present; EntityType.create resolves from this field.
            if (!tag.contains("id")) {
                tag.putString("id", ref.entityType().toString());
            }
            Optional<Entity> opt = EntityType.create(tag, server);
            if (opt.isPresent()) {
                Entity restored = opt.get();
                restored.moveTo(this.getX(), this.getY(), this.getZ(),
                        this.getYRot(), restored.getXRot());
                server.addFreshEntity(restored);
            }
        }
        this.discard();
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (this.level().isClientSide) return InteractionResult.SUCCESS;
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
        return this.isAlive();
    }

    @Override
    public net.minecraft.world.entity.EntityDimensions getDimensions(net.minecraft.world.entity.Pose pose) {
        // Same interaction-size floor as ShrunkenStructureEntity — Pehkui scales the base hitbox
        // down to something un-clickable otherwise.
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
