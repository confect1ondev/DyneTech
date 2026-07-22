package com.confect1on.dynetech.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.gene.DTAttachments;
import com.confect1on.dynetech.gene.EquippedPerks;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkEntry;
import com.confect1on.dynetech.gene.Perks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dev-only cycler: right-click strips every equipped perk and installs the next one in
 * {@link Perks#all()} at full quality, {@code ALWAYS} condition. The cycle index lives in a
 * data component on the stack so multiple cyclers cycle independently.
 */
public final class PerkCyclerItem extends Item {

    public PerkCyclerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.sidedSuccess(stack, true);

        List<Perk> all = new ArrayList<>(Perks.all());
        if (all.isEmpty()) return InteractionResultHolder.fail(stack);

        int idx = Math.floorMod(stack.getOrDefault(DTDataComponents.CYCLE_INDEX.get(), 0), all.size());
        Perk next = all.get(idx);
        stack.set(DTDataComponents.CYCLE_INDEX.get(), idx + 1);

        // Strip every currently equipped perk cleanly so their onUnequip side-effects (attribute
        // modifiers, mob effects, pehkui scale, etc.) get rolled back before the new one lands.
        EquippedPerks current = player.getData(DTAttachments.EQUIPPED_PERKS.get());
        for (PerkEntry e : current.perks()) {
            Perk p = Perks.get(e.perkId());
            if (p != null) p.onUnequip(player, e);
        }
        player.setData(DTAttachments.EQUIPPED_PERKS.get(), EquippedPerks.EMPTY);

        PerkEntry entry = new PerkEntry(next.id(), 1.0F, Optional.empty(), Optional.empty());
        player.setData(DTAttachments.EQUIPPED_PERKS.get(), EquippedPerks.EMPTY.add(entry));
        next.onEquip(player, entry);

        player.displayClientMessage(
                Component.translatable("item.dynetech.perk_cycler.applied", next.displayName())
                        .withStyle(ChatFormatting.AQUA),
                true);
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.dynetech.perk_cycler.hint").withStyle(ChatFormatting.DARK_GRAY));
        int idx = stack.getOrDefault(DTDataComponents.CYCLE_INDEX.get(), 0);
        int size = Perks.all().size();
        if (size > 0) {
            Perk peek = new ArrayList<>(Perks.all()).get(Math.floorMod(idx, size));
            tooltip.add(Component.translatable("item.dynetech.perk_cycler.next", peek.displayName())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
