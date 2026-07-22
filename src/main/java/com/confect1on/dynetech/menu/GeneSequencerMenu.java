package com.confect1on.dynetech.menu;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import com.confect1on.dynetech.block.DTBlocks;
import com.confect1on.dynetech.blockentity.GeneSequencerBlockEntity;
import com.confect1on.dynetech.gene.VialState;
import com.confect1on.dynetech.item.GeneVialItem;
import org.jetbrains.annotations.Nullable;

/**
 * Slot layout is public so {@link com.confect1on.dynetech.client.screen.GeneSequencerScreen}
 * can draw the recesses in exactly the same spots - any drift here means items render offset
 * from their backgrounds.
 */
public class GeneSequencerMenu extends AbstractContainerMenu {

    public static final int INPUT_SLOT_X = 30, INPUT_SLOT_Y = 34;
    public static final int OUTPUT_SLOT_X = 98, OUTPUT_SLOT_Y = 34, OUTPUT_SLOT_STEP = 18;
    public static final int ARROW_X = 50, ARROW_Y = 36, ARROW_W = 46, ARROW_H = 12;

    private final ContainerData data;
    private final ContainerLevelAccess access;

    public GeneSequencerMenu(int id, Inventory playerInv, GeneSequencerBlockEntity be) {
        this(id, playerInv, be.inventory(), be.data(),
                ContainerLevelAccess.create(be.getLevel(), be.getBlockPos()));
    }

    /** Client fallback when the BE hasn't arrived yet - scratch container that never syncs. */
    public GeneSequencerMenu(int id, Inventory playerInv) {
        this(id, playerInv, new SimpleContainer(GeneSequencerBlockEntity.SIZE) {
                    @Override public int getMaxStackSize() { return 1; }
                },
                new SimpleContainerData(2), ContainerLevelAccess.NULL);
    }

    private GeneSequencerMenu(int id, Inventory playerInv, Container beInv, ContainerData data, ContainerLevelAccess access) {
        super(DTMenus.GENE_SEQUENCER.get(), id);
        this.data = data;
        this.access = access;

        this.addSlot(new StatefulVialSlot(beInv, GeneSequencerBlockEntity.INPUT_SLOT,
                INPUT_SLOT_X, INPUT_SLOT_Y, VialState.RAW));
        for (int i = 0; i < GeneSequencerBlockEntity.OUTPUT_SLOT_COUNT; i++) {
            this.addSlot(new StatefulVialSlot(beInv, GeneSequencerBlockEntity.OUTPUT_SLOT_START + i,
                    OUTPUT_SLOT_X + i * OUTPUT_SLOT_STEP, OUTPUT_SLOT_Y, VialState.EMPTY));
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, 8 + col * 18, 142));
        }

        addDataSlots(data);
    }

    public int getProgress() { return data.get(0); }
    public int getProgressThreshold() { return Math.max(1, data.get(1)); }

    @Override
    public boolean stillValid(Player player) {
        return access.evaluate((level, pos) ->
                level.getBlockState(pos).is(DTBlocks.GENE_SEQUENCER.get())
                        && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0,
                true);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        int beSize = GeneSequencerBlockEntity.SIZE;
        int playerStart = beSize;
        int playerEnd = playerStart + 36;

        if (index < beSize) {
            if (!moveItemStackTo(stack, playerStart, playerEnd, true)) return ItemStack.EMPTY;
        } else {
            if (!moveItemStackTo(stack, 0, beSize, false)) return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    /**
     * Enforces both the vial-state filter and the "one vial per slot" rule (max 1 via override).
     */
    private static class StatefulVialSlot extends Slot {
        private final VialState required;

        StatefulVialSlot(Container c, int index, int x, int y, VialState required) {
            super(c, index, x, y);
            this.required = required;
        }

        @Override
        public int getMaxStackSize() { return 1; }

        @Override
        public boolean mayPlace(@Nullable ItemStack stack) {
            if (stack == null || !(stack.getItem() instanceof GeneVialItem)) return false;
            com.confect1on.dynetech.gene.VialContents c = GeneVialItem.getContents(stack);
            if (c.state() != required) return false;
            // Player-blood raws are locked snapshots - the microscope is the only place they go.
            return !c.isPlayerBlood();
        }
    }
}
