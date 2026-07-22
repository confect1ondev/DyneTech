package com.confect1on.dynetech.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.gene.GeneOps;
import com.confect1on.dynetech.gene.PerkLifecycle;
import com.confect1on.dynetech.gene.VialContents;
import com.confect1on.dynetech.gene.VialState;
import com.confect1on.dynetech.gene.VialTooltip;

import java.util.List;

/**
 * Dual-purpose sidearm: holds a single vial (Empty or Serum) as loaded ammo, then
 * <em>left-click</em>s (attacks) apply that vial to a target.
 *
 * <h3>Gestures</h3>
 * <ul>
 *   <li><b>Sneak + right-click</b>: load / eject.
 *     <ul>
 *       <li>Gun empty, off-hand holds an Empty or Serum vial → the vial moves into the gun.</li>
 *       <li>Gun loaded, off-hand empty → the loaded vial pops back to the off-hand.</li>
 *       <li>Other combinations → refused with a hint message.</li>
 *     </ul>
 *   </li>
 *   <li><b>Left-click a mob</b>:
 *     <ul>
 *       <li>Loaded Empty → transforms into a Raw sample of that mob (no damage dealt).</li>
 *       <li>Loaded Serum → injects the mob (no damage dealt), gun empties.</li>
 *       <li>Anything else loaded → attack passes through as a normal punch.</li>
 *     </ul>
 *   </li>
 *   <li><b>Sneak + left-click a mob</b>: retargets the action to yourself.
 *     <ul>
 *       <li>Loaded Empty → draws a self-sample; because the player's species pool is only the
 *       inert "dud" trait, every isolation off this Raw vial is useless. Anti-dupe safeguard.</li>
 *       <li>Loaded Serum → self-injects the serum; gun empties.</li>
 *     </ul>
 *   </li>
 * </ul>
 */
public class InjectionGunItem extends Item {

    private static final int COOLDOWN_TICKS = 20;

    public InjectionGunItem(Properties properties) {
        super(properties);
    }

    public static VialContents getLoaded(ItemStack stack) {
        VialContents c = stack.get(DTDataComponents.LOADED_VIAL.get());
        return c == null ? VialContents.EMPTY : c;
    }

    public static boolean isEmpty(ItemStack stack) {
        return getLoaded(stack).state() == VialState.EMPTY;
    }

    private static void setLoaded(ItemStack stack, VialContents contents) {
        // Persist even the EMPTY state - the gun distinguishes "no ammo" (component absent) from
        // "empty vial loaded" (component present, state EMPTY). The empty vial is a physical
        // container we'll refill via a punch.
        if (contents == null) {
            stack.remove(DTDataComponents.LOADED_VIAL.get());
        } else {
            stack.set(DTDataComponents.LOADED_VIAL.get(), contents);
        }
    }

    // -- Loading / ejecting on sneak + right-click --

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack gun = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("item.dynetech.injection_gun.hint"), true);
            }
            return InteractionResultHolder.pass(gun);
        }

        InteractionHand otherHand = hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack other = player.getItemInHand(otherHand);
        VialContents loaded = getLoaded(gun);
        boolean gunHasAmmo = loaded.state() != VialState.EMPTY || stack_has_loaded_component(gun);

        // Eject path: gun has something loaded, off-hand is empty.
        if (gunHasAmmo && other.isEmpty()) {
            if (!level.isClientSide) {
                ItemStack ejected = GeneVialItem.withContents(loaded);
                player.setItemInHand(otherHand, ejected);
                setLoaded(gun, null);
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.BUNDLE_REMOVE_ONE, SoundSource.PLAYERS, 0.7F, 1.0F);
            }
            return InteractionResultHolder.sidedSuccess(gun, level.isClientSide);
        }

        // Load path: gun empty, off-hand holds an acceptable vial.
        if (!gunHasAmmo && other.getItem() instanceof GeneVialItem) {
            VialContents inOther = GeneVialItem.getContents(other);
            if (canLoad(inOther.state())) {
                if (!level.isClientSide) {
                    setLoaded(gun, inOther);
                    other.shrink(1);
                    level.playSound(null, player.getX(), player.getY(), player.getZ(),
                            SoundEvents.BUNDLE_INSERT, SoundSource.PLAYERS, 0.7F, 1.2F);
                }
                return InteractionResultHolder.sidedSuccess(gun, level.isClientSide);
            }
        }

        if (!level.isClientSide) {
            player.displayClientMessage(Component.translatable("item.dynetech.injection_gun.load_hint"), true);
        }
        return InteractionResultHolder.fail(gun);
    }

    /** Presence check independent of the state value. */
    private static boolean stack_has_loaded_component(ItemStack stack) {
        return stack.has(DTDataComponents.LOADED_VIAL.get());
    }

    /** Public wrapper for the client-side model-override predicate. */
    public static boolean hasLoadedComponent(ItemStack stack) {
        return stack_has_loaded_component(stack);
    }

    // -- Punching --

    @Override
    public boolean onLeftClickEntity(ItemStack stack, Player player, Entity target) {
        if (!(target instanceof LivingEntity mob) || target instanceof Player) {
            // Never sample another player, and non-living targets get a normal attack pass-through.
            return false;
        }
        if (!stack_has_loaded_component(stack)) return false;

        // Sneak retargets to the player themselves. Empty-hand attacks (nothing in front) hit
        // via the {@link #fireAtSelf} path invoked from the LeftClickEmpty payload - the client
        // sees the empty swing and routes it here through DTPayloads.
        LivingEntity effectiveTarget = player.isShiftKeyDown() ? player : mob;
        return applyLoadedTo(effectiveTarget, player, stack);
    }

    /**
     * Server-side entrypoint for the "shift + attack air" self-inject path. The client dispatches
     * this via a network payload when the player left-clicks with nothing in reach while sneaking
     * with a loaded gun in hand. Returns whether the fire actually did anything (used for logging).
     */
    public static boolean fireAtSelf(Player player, ItemStack gun) {
        if (!(gun.getItem() instanceof InjectionGunItem impl)) return false;
        if (!stack_has_loaded_component(gun)) return false;
        return impl.applyLoadedTo(player, player, gun);
    }

    /**
     * Common fire logic shared between the mob-punch and self-air paths. Runs the state-dispatch
     * (Empty → sample target, Isolated/Serum → inject target). Returns {@code true} when the gun
     * took action so the caller can cancel the underlying vanilla attack.
     */
    private boolean applyLoadedTo(LivingEntity target, Player player, ItemStack gun) {
        VialContents loaded = getLoaded(gun);
        Level level = player.level();

        if (loaded.state() == VialState.EMPTY) {
            if (!level.isClientSide) {
                VialContents raw = GeneOps.rollRawFromEntity(target, level.random);
                setLoaded(gun, raw);
                level.playSound(null, target.getX(), target.getY(), target.getZ(),
                        SoundEvents.BOTTLE_FILL, SoundSource.PLAYERS, 0.7F, 1.3F);
                player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
                player.awardStat(Stats.ITEM_USED.get(this));
            }
            return true;
        }

        if (loaded.state() == VialState.SERUM) {
            if (!level.isClientSide) {
                PerkLifecycle.inject(target, loaded, level.random);
                setLoaded(gun, VialContents.EMPTY);
                level.playSound(null, target.getX(), target.getY(), target.getZ(),
                        SoundEvents.CROSSBOW_SHOOT, SoundSource.PLAYERS, 0.5F, 1.4F);
                player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
                player.awardStat(Stats.ITEM_USED.get(this));
            }
            return true;
        }

        return false;
    }

    /** Client can call this to know whether the gun should intercept a shift+attack-empty. */
    public static boolean isFireable(ItemStack stack) {
        if (!stack_has_loaded_component(stack)) return false;
        VialState s = getLoaded(stack).state();
        // Empty triggers the sample-self path (into Raw); Serum triggers the actual injection.
        return s == VialState.EMPTY || s == VialState.SERUM;
    }

    private static boolean canLoad(VialState state) {
        // Only Empty (as a re-fillable container) and Serum (as ammo the gun can fire). Isolated
        // vials must be spliced back into blood via the splicer before they'll load - otherwise
        // the gun accepts them but the fire path has nothing to do, leaving dead ammo.
        return state == VialState.EMPTY || state == VialState.SERUM;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        if (!stack_has_loaded_component(stack)) {
            tooltip.add(Component.translatable("item.dynetech.injection_gun.unloaded").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            VialContents c = getLoaded(stack);
            tooltip.add(Component.translatable("item.dynetech.injection_gun.loaded_prefix")
                    .append(Component.translatable(c.state().langKey()))
                    .withStyle(ChatFormatting.GREEN));
            // Reuse the vial tooltip renderer so the gun's chamber view matches the vial in-hand.
            VialTooltip.append(c, tooltip, false);
        }
        tooltip.add(Component.translatable("item.dynetech.injection_gun.hint").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("item.dynetech.injection_gun.punch_hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
