package com.confect1on.dynetech.item;

import com.confect1on.dynetech.config.DTConfig;
import com.confect1on.dynetech.entity.ThrownPhaseDiskEntity;
import com.confect1on.dynetech.gene.DTAttachments;
import com.confect1on.dynetech.gene.EquippedPerks;
import com.confect1on.dynetech.gene.Perks;
import com.confect1on.dynetech.gene.ShoalHostState;
import com.confect1on.dynetech.particle.DTParticles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PhaseDiskItem extends Item {

    // Server-side channel bookkeeping: which carrier each channeling user is draining.
    // No entry means a self-cure channel.
    private static final Map<UUID, UUID> CHANNELS = new HashMap<>();

    public PhaseDiskItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // Self-cure: if the player holding the disk is infected, the same use action channels
        // the extraction instead of throwing. Matches the entity-interaction path so the
        // player never has to right-click themselves.
        if (isInfectedCarrier(player)) {
            player.startUsingItem(hand);
            return InteractionResultHolder.consume(stack);
        }

        player.getCooldowns().addCooldown(this, 10);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.SNOWBALL_THROW, SoundSource.NEUTRAL, 0.6F,
                0.3F / (level.getRandom().nextFloat() * 0.4F + 0.8F));

        if (!level.isClientSide) {
            ThrownPhaseDiskEntity disk = new ThrownPhaseDiskEntity(level, player, stack.copyWithCount(1));
            // Match PymParticleDiscItem's throw so all DyneTech discs feel the same in the hand.
            disk.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, 1.5F, 1.0F);
            level.addFreshEntity(disk);
        }

        player.awardStat(Stats.ITEM_USED.get(this));
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /**
     * Right-clicking an infected player starts a channeled extraction. Works during incubation
     * too, since both perks are stripped together on completion.
     */
    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player user, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof Player targetPlayer)) return InteractionResult.PASS;
        if (!isInfectedCarrier(targetPlayer)) return InteractionResult.PASS;
        if (!user.level().isClientSide) {
            CHANNELS.put(user.getUUID(), targetPlayer.getUUID());
        }
        user.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return DTConfig.CURE_CHANNEL_TICKS.get();
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingUseDuration) {
        if (level.isClientSide || !(entity instanceof Player user)) return;
        Player target = channelTarget(user);
        if (target == null) {
            CHANNELS.remove(user.getUUID());
            user.stopUsingItem();
            return;
        }
        if (level instanceof ServerLevel server && (remainingUseDuration & 1) == 0) {
            streamMotes(server, user, target);
        }
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (level.isClientSide || !(entity instanceof Player user)) return stack;
        Player target = channelTarget(user);
        CHANNELS.remove(user.getUUID());
        if (target != null) {
            extractInfection(user, target, stack);
        }
        return stack;
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (!level.isClientSide) CHANNELS.remove(entity.getUUID());
    }

    // A crashed or disconnecting channeler never fires releaseUsing, so drop the entry here to
    // keep CHANNELS from accumulating stale UUIDs across a long server session.
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        CHANNELS.remove(event.getEntity().getUUID());
    }

    /** The carrier this channel is draining, or null if the channel should abort. */
    private static Player channelTarget(Player user) {
        UUID targetId = CHANNELS.get(user.getUUID());
        Player target = targetId == null ? user : user.level().getPlayerByUUID(targetId);
        if (target == null || !target.isAlive() || !isInfectedCarrier(target)) return null;
        double range = DTConfig.CURE_CHANNEL_RANGE.get();
        return user.distanceToSqr(target) > range * range ? null : target;
    }

    private static void streamMotes(ServerLevel server, Player user, Player target) {
        Vec3 from = target.position().add(0.0D, target.getBbHeight() * 0.6D, 0.0D);
        Vec3 to = user.getEyePosition().add(user.getLookAngle().scale(0.4D));
        Vec3 line = to.subtract(from);
        Vec3 vel = line.lengthSqr() < 1.0E-4D ? Vec3.ZERO : line.normalize().scale(0.15D);
        RandomSource rng = server.random;
        for (int i = 0; i < 3; i++) {
            Vec3 p = from.add(line.scale(rng.nextDouble()));
            server.sendParticles(DTParticles.SHOAL_MOTE.get(),
                    p.x + (rng.nextDouble() - 0.5D) * 0.3D,
                    p.y + (rng.nextDouble() - 0.5D) * 0.3D,
                    p.z + (rng.nextDouble() - 0.5D) * 0.3D,
                    0, vel.x, vel.y, vel.z, 1.0D);
        }
    }

    private static boolean isInfectedCarrier(Player player) {
        EquippedPerks eq = player.getData(DTAttachments.EQUIPPED_PERKS.get());
        return eq.has(Perks.SHOAL_INCUBATION.getId()) || eq.has(Perks.SHOAL_INFECTION.getId());
    }

    private static void extractInfection(Player user, Player target, ItemStack stack) {
        Level level = target.level();
        if (level.isClientSide) return;

        EquippedPerks eq = target.getData(DTAttachments.EQUIPPED_PERKS.get());
        eq.find(Perks.SHOAL_INCUBATION.getId()).ifPresent(entry ->
                Perks.SHOAL_INCUBATION.get().onUnequip(target, entry));
        eq.find(Perks.SHOAL_INFECTION.getId()).ifPresent(entry ->
                Perks.SHOAL_INFECTION.get().onUnequip(target, entry));
        EquippedPerks cleared = eq
                .remove(Perks.SHOAL_INCUBATION.getId())
                .remove(Perks.SHOAL_INFECTION.getId());
        target.setData(DTAttachments.EQUIPPED_PERKS.get(), cleared);
        target.setData(DTAttachments.SHOAL_HOST_STATE.get(), ShoalHostState.EMPTY);

        if (level instanceof ServerLevel server) {
            server.sendParticles(DTParticles.SHOAL_MOTE.get(),
                    target.getX(), target.getY() + target.getBbHeight() * 0.6D, target.getZ(),
                    40, 0.35D, 0.6D, 0.35D, 0.02D);
            server.playSound(null, target.getX(), target.getY(), target.getZ(),
                    SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.0F, 1.4F);
        }

        if (!user.getAbilities().instabuild) stack.shrink(1);
        user.getCooldowns().addCooldown(stack.getItem(), 20);
    }
}
