package com.confect1on.dynetech.entity;

import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.network.PacketDistributor;
import com.confect1on.dynetech.item.DTItems;
import com.confect1on.dynetech.item.PymParticleDiskItem;
import com.confect1on.dynetech.network.DTPayloads;
import com.confect1on.dynetech.pehkui.PehkuiCompat;

public class PymParticleDiskEntity extends ThrowableItemProjectile {

    public PymParticleDiskEntity(EntityType<? extends PymParticleDiskEntity> type, Level level) {
        super(type, level);
    }

    public PymParticleDiskEntity(Level level, LivingEntity thrower, ItemStack stack) {
        super(DTEntityTypes.PYM_PARTICLE_DISK.get(), thrower, level);
        this.setItem(stack.copyWithCount(1));
    }

    @Override
    protected Item getDefaultItem() {
        return DTItems.SHRINK_DISK.get();
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == 3) {
            ParticleOptions options = new ItemParticleOption(ParticleTypes.ITEM, this.getItem());
            for (int i = 0; i < 8; i++) {
                this.level().addParticle(options, this.getX(), this.getY(), this.getZ(), 0.0D, 0.0D, 0.0D);
            }
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (this.level().isClientSide) return;

        ItemStack stack = this.getItem();
        if (!(stack.getItem() instanceof PymParticleDiskItem disc)) return;

        var target = result.getEntity();

        // Special-case shrunken structures: a grow disc unshrinks them back to normal size.
        if (target instanceof com.confect1on.dynetech.entity.ShrunkenStructureEntity && disc.size > 1F) {
            PehkuiCompat.setTargetScale(target, 1.0F, 20);
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(target, new DTPayloads.SpawnPulses(target.getId()));
            return;
        }
        if (target instanceof com.confect1on.dynetech.entity.ShrunkenStructureEntity) {
            // shrink disc on it is a no-op — still pulse for feedback.
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(target, new DTPayloads.SpawnPulses(target.getId()));
            return;
        }

        // Same treatment for shrunken mobs captured by the Tissue Compression Eliminator, but
        // lethal captures refuse regrow — pulse for feedback either way.
        if (target instanceof com.confect1on.dynetech.entity.ShrunkenEntityEntity carrier && disc.size > 1F) {
            var ref = carrier.getItem().get(com.confect1on.dynetech.component.DTDataComponents.SHRUNKEN_ENTITY.get());
            boolean lethal = ref != null && ref.lethal();
            if (!lethal) {
                PehkuiCompat.setTargetScale(target, 1.0F, 20);
            }
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(target, new DTPayloads.SpawnPulses(target.getId()));
            return;
        }
        if (target instanceof com.confect1on.dynetech.entity.ShrunkenEntityEntity) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(target, new DTPayloads.SpawnPulses(target.getId()));
            return;
        }

        float diskSize = disc.size;
        float current = PehkuiCompat.getScale(target);

        // Cross-1F transitions snap back to 1F instead of flipping polarity.
        float newSize = ((current < 1F && diskSize > 1F) || (current > 1F && diskSize < 1F)) ? 1F : diskSize;

        PehkuiCompat.setTargetScale(target, newSize, 10);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(target, new DTPayloads.SpawnPulses(target.getId()));

        if (target instanceof LivingEntity living) {
            var slot = pickArmorSlot(living);
            if (slot != null) {
                ItemStack armor = living.getItemBySlot(slot);
                if (!armor.isEmpty()) {
                    living.setItemSlot(slot, ItemStack.EMPTY);
                    ItemEntity dropped = new ItemEntity(living.level(), living.getX(), living.getY() + 1.0, living.getZ(), armor);
                    living.level().addFreshEntity(dropped);
                }
            }
        }
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (!this.level().isClientSide) {
            this.level().broadcastEntityEvent(this, (byte) 3);
            this.discard();
        }
    }

    private static net.minecraft.world.entity.EquipmentSlot pickArmorSlot(LivingEntity living) {
        var slots = new java.util.ArrayList<net.minecraft.world.entity.EquipmentSlot>();
        for (var slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            if (slot.getType() == net.minecraft.world.entity.EquipmentSlot.Type.HUMANOID_ARMOR
                    && !living.getItemBySlot(slot).isEmpty()) {
                slots.add(slot);
            }
        }
        if (slots.isEmpty()) return null;
        return slots.get(living.getRandom().nextInt(slots.size()));
    }
}
