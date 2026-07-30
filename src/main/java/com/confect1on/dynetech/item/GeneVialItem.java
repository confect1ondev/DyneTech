package com.confect1on.dynetech.item;

import net.minecraft.network.chat.Component;
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
 * The one item that carries every gene-vial state via its {@link VialContents} data component.
 *
 * <p>Deliberately one item, not four: TFW ships one Vial too, and the alternative (four separate
 * items) forces awkward recipe pivots for every transformation. State swaps happen by writing a
 * new {@link VialContents} onto the same stack.
 *
 * <p>Interactions:
 * <ul>
 *   <li>EMPTY + right-click mob → produces a RAW vial (single-stack replace or split).</li>
 *   <li>SERUM + right-click self → self-injection, vial empties.</li>
 *   <li>SERUM + right-click mob → target injection, vial empties.</li>
 *   <li>RAW / ISOLATED - no direct interaction; use the Sequencer / Splicer blocks.</li>
 * </ul>
 */
public class GeneVialItem extends Item {

    private static final int EMPTY_STACK_LIMIT = 16;

    public GeneVialItem(Properties properties) {
        super(properties);
    }

    // -- Public helpers so machines can mint stacks --

    public static ItemStack empty() {
        // No component => reads back as EMPTY via getContents.
        return new ItemStack(com.confect1on.dynetech.item.DTItems.GENE_VIAL.get());
    }

    public static ItemStack withContents(VialContents contents) {
        ItemStack stack = new ItemStack(com.confect1on.dynetech.item.DTItems.GENE_VIAL.get());
        if (contents.state() != VialState.EMPTY) {
            stack.set(DTDataComponents.VIAL_CONTENTS.get(), contents);
        }
        return stack;
    }

    public static VialContents getContents(ItemStack stack) {
        VialContents c = stack.get(DTDataComponents.VIAL_CONTENTS.get());
        return c == null ? VialContents.EMPTY : c;
    }

    public static boolean isState(ItemStack stack, VialState state) {
        return getContents(stack).state() == state;
    }

    // -- Vanilla item hooks --

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return isState(stack, VialState.EMPTY) ? EMPTY_STACK_LIMIT : 1;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (target instanceof Player) return InteractionResult.PASS; // Never sample a player.
        // Sneak-right-click always PASSes through the entity - the empty-on-shift path in
        // {@link #use} handles clearing the vial. Without this, shift-clicking a mob still ran
        // the sample/inject branch below and produced misleading feedback (green sparkles).
        if (player.isShiftKeyDown()) return InteractionResult.PASS;
        Level level = player.level();
        VialContents contents = getContents(stack);

        if (contents.state() == VialState.EMPTY) {
            if (!level.isClientSide) {
                VialContents raw = GeneOps.rollRawFromEntity(target, level.random);
                ItemStack rawStack = withContents(raw);
                giveOrSwap(player, hand, stack, rawStack);
                level.playSound(null, target.getX(), target.getY(), target.getZ(),
                        SoundEvents.BOTTLE_FILL, SoundSource.PLAYERS, 0.6F, 1.4F);
                player.awardStat(Stats.ITEM_USED.get(this));
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (PerkLifecycle.isInjectable(contents)) {
            if (!level.isClientSide) {
                PerkLifecycle.inject(target, contents, level.random);
                emptyVial(stack);
                level.playSound(null, target.getX(), target.getY(), target.getZ(),
                        SoundEvents.BOTTLE_EMPTY, SoundSource.PLAYERS, 0.7F, 1.0F);
                player.awardStat(Stats.ITEM_USED.get(this));
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        return InteractionResult.PASS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        VialContents contents = getContents(stack);

        // Sneak + right-click: purge whatever's in the vial. Exception: if the opposite hand
        // holds an unloaded injection gun, defer via PASS so the gun's own use() loads this
        // vial instead of nuking it. That way "vial in main hand + gun in off hand" behaves
        // the same as the documented "vial in off hand + gun in main hand" gesture.
        if (player.isShiftKeyDown() && contents.state() != VialState.EMPTY) {
            InteractionHand otherHand = hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            ItemStack other = player.getItemInHand(otherHand);
            if (other.getItem() instanceof InjectionGunItem && !InjectionGunItem.hasLoadedComponent(other)) {
                return InteractionResultHolder.pass(stack);
            }
            if (!level.isClientSide) {
                emptyVial(stack);
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.BOTTLE_EMPTY, SoundSource.PLAYERS, 0.6F, 0.9F);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        // Serum states inject directly; PerkLifecycle also promotes fresh player-blood RAW to
        // an injectable path. Isolated genes still need the splicer first.
        if (!PerkLifecycle.isInjectable(contents)) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide) {
            RandomSource rng = level.random;
            PerkLifecycle.inject(player, contents, rng);
            emptyVial(stack);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BOTTLE_EMPTY, SoundSource.PLAYERS, 0.7F, 1.0F);
            player.awardStat(Stats.ITEM_USED.get(this));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        VialTooltip.append(getContents(stack), tooltip, true);
    }

    // -- Private helpers --

    /**
     * "Empty" the vial by stripping the {@link DTDataComponents#VIAL_CONTENTS} component. The
     * {@link ItemStack} stays in-hand - no shrink/replace shuffle - which keeps creative-mode
     * semantics correct and guarantees the vial visually updates to Empty in the same slot
     * whether the caller was inject-consume or an explicit sneak-clear.
     */
    private static void emptyVial(ItemStack stack) {
        stack.remove(DTDataComponents.VIAL_CONTENTS.get());
    }

    /**
     * Sample succeeded - put the raw vial in the player's inventory. In survival, the source
     * empty vial in-hand is consumed and (if it was the whole stack) replaced by the raw. In
     * creative, the source stack is left alone and the raw is always given - the previous
     * {@code inventory.contains} short-circuit collapsed distinct raws to a single stack because
     * {@code Inventory.contains(ItemStack)} matches by item type, not by vial payload.
     */
    private static void giveOrSwap(Player player, InteractionHand hand, ItemStack inHand, ItemStack replacement) {
        if (player.getAbilities().instabuild) {
            if (!player.getInventory().add(replacement)) player.drop(replacement, false);
            return;
        }
        inHand.shrink(1);
        if (inHand.isEmpty()) {
            player.setItemInHand(hand, replacement);
        } else {
            if (!player.getInventory().add(replacement)) player.drop(replacement, false);
        }
    }
}
