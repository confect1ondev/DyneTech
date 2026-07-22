package com.confect1on.dynetech.menu;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import com.confect1on.dynetech.block.DTBlocks;
import com.confect1on.dynetech.blockentity.GeneMicroscopeBlockEntity;
import com.confect1on.dynetech.item.GeneVialItem;
import org.jetbrains.annotations.Nullable;

/**
 * One-slot inspection menu. Layout constants are public so the screen draws recesses at exactly
 * the same coordinates the slots resolve to - that's how we guarantee the "items sit inside the
 * wells" invariant.
 */
public class GeneMicroscopeMenu extends AbstractContainerMenu {

    public static final int SLOT_X = 24;
    public static final int SLOT_Y = 30;

    /** Player-inventory grid origin. Screen's imageHeight is 210; row0 at 128, hotbar at 186. */
    public static final int INV_ROW0_Y = 128;
    public static final int INV_HOTBAR_Y = 186;
    public static final int INV_X0 = 8;

    private final Container beInv;
    private final ContainerLevelAccess access;

    public GeneMicroscopeMenu(int id, Inventory playerInv, GeneMicroscopeBlockEntity be) {
        this(id, playerInv, be.inventory(),
                ContainerLevelAccess.create(be.getLevel(), be.getBlockPos()));
    }

    public GeneMicroscopeMenu(int id, Inventory playerInv) {
        this(id, playerInv, new SimpleContainer(GeneMicroscopeBlockEntity.SIZE) {
            @Override public int getMaxStackSize() { return 1; }
        }, ContainerLevelAccess.NULL);
    }

    private GeneMicroscopeMenu(int id, Inventory playerInv, Container beInv, ContainerLevelAccess access) {
        super(DTMenus.GENE_MICROSCOPE.get(), id);
        this.beInv = beInv;
        this.access = access;

        this.addSlot(new VialInspectSlot(beInv, 0, SLOT_X, SLOT_Y));

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9, INV_X0 + col * 18, INV_ROW0_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, INV_X0 + col * 18, INV_HOTBAR_Y));
        }
    }

    public ItemStack loadedVial() { return beInv.getItem(0); }

    @Override
    public boolean stillValid(Player player) {
        return access.evaluate((level, pos) ->
                level.getBlockState(pos).is(DTBlocks.GENE_MICROSCOPE.get())
                        && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0,
                true);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        int beSize = GeneMicroscopeBlockEntity.SIZE;
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

    private static class VialInspectSlot extends Slot {
        VialInspectSlot(Container c, int index, int x, int y) { super(c, index, x, y); }

        @Override public int getMaxStackSize() { return 1; }

        @Override
        public boolean mayPlace(@Nullable ItemStack stack) {
            return stack != null && stack.getItem() instanceof GeneVialItem;
        }
    }
}
