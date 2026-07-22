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
import com.confect1on.dynetech.blockentity.GeneSplicerBlockEntity;
import com.confect1on.dynetech.gene.VialState;
import com.confect1on.dynetech.item.GeneVialItem;
import org.jetbrains.annotations.Nullable;

public class GeneSplicerMenu extends AbstractContainerMenu {

    public static final int SLOT_A_X = 44, SLOT_B_X = 116, SLOTS_Y = 34;
    public static final int ARROW_X = 66, ARROW_Y = 36, ARROW_W = 46, ARROW_H = 12;

    private final ContainerData data;
    private final ContainerLevelAccess access;

    public GeneSplicerMenu(int id, Inventory playerInv, GeneSplicerBlockEntity be) {
        this(id, playerInv, be.inventory(), be.data(),
                ContainerLevelAccess.create(be.getLevel(), be.getBlockPos()));
    }

    public GeneSplicerMenu(int id, Inventory playerInv) {
        this(id, playerInv, new SimpleContainer(GeneSplicerBlockEntity.SIZE) {
                    @Override public int getMaxStackSize() { return 1; }
                },
                new SimpleContainerData(2), ContainerLevelAccess.NULL);
    }

    private GeneSplicerMenu(int id, Inventory playerInv, Container beInv, ContainerData data, ContainerLevelAccess access) {
        super(DTMenus.GENE_SPLICER.get(), id);
        this.data = data;
        this.access = access;

        this.addSlot(new SpliceInputSlot(beInv, GeneSplicerBlockEntity.SLOT_A, SLOT_A_X, SLOTS_Y));
        this.addSlot(new SpliceInputSlot(beInv, GeneSplicerBlockEntity.SLOT_B, SLOT_B_X, SLOTS_Y));

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
                level.getBlockState(pos).is(DTBlocks.GENE_SPLICER.get())
                        && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0,
                true);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        int beSize = GeneSplicerBlockEntity.SIZE;
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
     * Splicer only splices <em>filled</em> vials. Empty vials aren't a valid input - otherwise
     * the machine would auto-serum-ify an Isolated on the next tick after a merge.
     */
    private static class SpliceInputSlot extends Slot {
        SpliceInputSlot(Container c, int index, int x, int y) {
            super(c, index, x, y);
        }

        @Override public int getMaxStackSize() { return 1; }

        @Override
        public boolean mayPlace(@Nullable ItemStack stack) {
            if (stack == null || !(stack.getItem() instanceof GeneVialItem)) return false;
            VialState s = GeneVialItem.getContents(stack).state();
            // Isolated (gene) or Raw (blood carrier). Empty and Serum aren't valid inputs.
            return s == VialState.ISOLATED || s == VialState.RAW;
        }
    }
}
