package com.confect1on.dynetech.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.entity.ShrunkenEntityEntity;
import com.confect1on.dynetech.entity.ShrunkenStructureEntity;
import com.confect1on.dynetech.network.DTPayloads;
import com.confect1on.dynetech.pehkui.PehkuiCompat;
import com.confect1on.dynetech.storage.ShrunkenEntityRef;

import java.util.List;

/**
 * Casts a 6-block beam from the player's eyes in their look direction. The first non-player,
 * non-already-shrunken entity along the ray is captured: its NBT is written to a
 * {@link ShrunkenEntityRef} on a shrunken-entity item, and a {@link ShrunkenEntityEntity} spawns
 * at the target's position holding that item. Regrowth is the standard grow-disc flow.
 */
public class TissueCompressionEliminator extends Item {

    private static final double RANGE = 6.0;
    private static final int COOLDOWN_TICKS = 20;

    public TissueCompressionEliminator(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // Shift+use toggles Lethal ↔ Non-Lethal without firing the beam.
        if (player.isShiftKeyDown()) {
            boolean nowLethal = !isLethal(stack);
            stack.set(DTDataComponents.TCE_LETHAL.get(), nowLethal);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.PLAYERS, 0.4F, nowLethal ? 0.9F : 1.4F);
            if (!level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable(nowLethal
                                ? "item.dynetech.tissue_compression_eliminator.mode.lethal"
                                : "item.dynetech.tissue_compression_eliminator.mode.nonlethal"),
                        true);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        boolean lethal = isLethal(stack);

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.5F, lethal ? 1.2F : 1.6F);

        if (!level.isClientSide) {
            Vec3 from = player.getEyePosition();
            Vec3 dir = player.getLookAngle();
            Vec3 farEnd = from.add(dir.scale(RANGE));

            // Cap the beam at the first solid block so we can't grab mobs through walls.
            BlockHitResult blockHit = level.clip(new ClipContext(from, farEnd,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            Vec3 end = blockHit.getType() == HitResult.Type.MISS ? farEnd : blockHit.getLocation();

            AABB scanBox = player.getBoundingBox().expandTowards(dir.scale(RANGE)).inflate(1.0);
            EntityHitResult entHit = ProjectileUtil.getEntityHitResult(level, player, from, end, scanBox,
                    TissueCompressionEliminator::isValidTarget);

            if (entHit != null) {
                shrinkAndDrop((ServerLevel) level, player, entHit.getEntity(), lethal);
            }

            spawnBeamParticles((ServerLevel) level, from, end, lethal);
        }

        player.awardStat(Stats.ITEM_USED.get(this));
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        boolean lethal = isLethal(stack);
        tooltip.add(Component.translatable("item.dynetech.tissue_compression_eliminator.tooltip.mode",
                Component.translatable(lethal
                        ? "item.dynetech.tissue_compression_eliminator.mode.lethal"
                        : "item.dynetech.tissue_compression_eliminator.mode.nonlethal")
                        .withStyle(lethal ? ChatFormatting.RED : ChatFormatting.GREEN))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.dynetech.tissue_compression_eliminator.tooltip.hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    public static boolean isLethal(ItemStack stack) {
        Boolean v = stack.get(DTDataComponents.TCE_LETHAL.get());
        return v != null && v;
    }

    private static boolean isValidTarget(Entity e) {
        // Restrict to LivingEntity so the inventory 3D preview and pulse animation can rely on a
        // LivingEntityRenderer being available for the captured mob's type.
        return e instanceof LivingEntity
                && e.isAlive()
                && e.isPickable()
                && !e.isSpectator()
                && !(e instanceof Player)
                && !(e instanceof ShrunkenEntityEntity)
                && !(e instanceof ShrunkenStructureEntity);
    }

    private static void shrinkAndDrop(ServerLevel level, Player caster, Entity target, boolean lethal) {
        CompoundTag tag = new CompoundTag();
        if (!target.saveAsPassenger(tag)) return;

        String idStr = tag.getString("id");
        ResourceLocation typeId = ResourceLocation.tryParse(idStr);
        if (typeId == null) return;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(typeId);
        if (type == null) return;

        double tx = target.getX();
        double ty = target.getY();
        double tz = target.getZ();

        // Remove the original before spawning the replacement so no two copies exist even briefly.
        target.discard();

        ItemStack payload = ShrunkenEntityItem.create(new ShrunkenEntityRef(typeId, tag, lethal));
        ShrunkenEntityEntity carrier = new ShrunkenEntityEntity(level, tx, ty, tz, payload);
        level.addFreshEntity(carrier);

        // Animate from full size down (constructor already set immediate 0.1×; override with a
        // brief 1.0 → 0.1 shrink for the visible pop, matching the structure shrinker's flow).
        PehkuiCompat.setScaleImmediate(carrier, 1.0F);
        PehkuiCompat.setTargetScale(carrier, 0.1F, 20);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                carrier, new DTPayloads.SpawnPulses(carrier.getId()));
    }

    private static void spawnBeamParticles(ServerLevel level, Vec3 from, Vec3 end, boolean lethal) {
        Vec3 delta = end.subtract(from);
        int steps = Math.max(1, (int) Math.ceil(delta.length() * 4));
        var particle = lethal ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.REVERSE_PORTAL;
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            Vec3 p = from.add(delta.scale(t));
            level.sendParticles(particle, p.x, p.y, p.z, 1, 0, 0, 0, 0.0);
        }
    }
}
