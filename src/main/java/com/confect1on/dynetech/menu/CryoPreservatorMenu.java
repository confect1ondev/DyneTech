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
import com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity;
import org.jetbrains.annotations.Nullable;

public class CryoPreservatorMenu extends AbstractContainerMenu {

    // 2x2 grid of ice input slots on the right, blood vial centered on the left of that group.
    public static final int BLOOD_X = 92, BLOOD_Y = 44;
    public static final int ICE_GRID_X = 132, ICE_GRID_Y = 34;
    public static final int ICE_SLOT_STEP = 20;

    private final ContainerData data;
    private final ContainerLevelAccess access;

    public CryoPreservatorMenu(int id, Inventory playerInv, CryoPreservatorBlockEntity be) {
        this(id, playerInv, be.inventory(), be.data(),
                ContainerLevelAccess.create(be.getLevel(), be.getBlockPos()), be.getLevel());
    }

    public CryoPreservatorMenu(int id, Inventory playerInv) {
        this(id, playerInv, new SimpleContainer(CryoPreservatorBlockEntity.SIZE),
                new SimpleContainerData(4), ContainerLevelAccess.NULL, null);
    }

    private CryoPreservatorMenu(int id, Inventory playerInv, Container beInv, ContainerData data,
                                ContainerLevelAccess access, net.minecraft.world.level.Level levelForSlots) {
        super(DTMenus.CRYO_PRESERVATOR.get(), id);
        this.data = data;
        this.access = access;

        this.addSlot(new BloodSlot(beInv, CryoPreservatorBlockEntity.SLOT_BLOOD, BLOOD_X, BLOOD_Y, levelForSlots));
        addIceSlot(beInv, CryoPreservatorBlockEntity.SLOT_ICE_A, 0, 0);
        addIceSlot(beInv, CryoPreservatorBlockEntity.SLOT_ICE_B, 1, 0);
        addIceSlot(beInv, CryoPreservatorBlockEntity.SLOT_ICE_C, 0, 1);
        addIceSlot(beInv, CryoPreservatorBlockEntity.SLOT_ICE_D, 1, 1);

        int invLeft = (200 - 162) / 2;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9, invLeft + col * 18, 118 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, invLeft + col * 18, 176));
        }

        addDataSlots(data);
    }

    private void addIceSlot(Container beInv, int slotIndex, int col, int row) {
        this.addSlot(new IceSlot(beInv, slotIndex,
                ICE_GRID_X + col * ICE_SLOT_STEP,
                ICE_GRID_Y + row * ICE_SLOT_STEP));
    }

    public int getIceStored() { return data.get(0); }
    public int getIceProgress() { return data.get(1); }
    public int getIntervalTicks() { return Math.max(1, data.get(2)); }
    public boolean isPreserving() { return data.get(3) != 0; }

    /** Total ticks of preservation still available given current ice stock + burn progress. */
    public long fuelTicksRemaining() {
        long interval = getIntervalTicks();
        long fromCurrent = getIceProgress() > 0 ? interval - getIceProgress() : 0L;
        return fromCurrent + (long) getIceStored() * interval;
    }

    @Override
    public boolean stillValid(Player player) {
        return access.evaluate((level, pos) ->
                level.getBlockState(pos).is(DTBlocks.CRYO_PRESERVATOR.get())
                        && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0,
                true);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        int beSize = CryoPreservatorBlockEntity.SIZE;
        int playerStart = beSize;
        int playerEnd = playerStart + 36;

        if (index < beSize) {
            if (!moveItemStackTo(stack, playerStart, playerEnd, true)) return ItemStack.EMPTY;
        } else {
            // From player inventory: try the machine slots. mayPlace filters route the item into
            // the correct slot (blood → blood slot, ice → any ice slot).
            if (!moveItemStackTo(stack, 0, beSize, false)) return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    private static class BloodSlot extends Slot {
        private final net.minecraft.world.level.Level level;

        BloodSlot(Container c, int index, int x, int y, net.minecraft.world.level.Level level) {
            super(c, index, x, y);
            this.level = level;
        }
        @Override public int getMaxStackSize() { return 1; }
        @Override public boolean mayPlace(@Nullable ItemStack stack) {
            if (stack == null) return false;
            long now = level != null ? level.getGameTime() : 0L;
            return CryoPreservatorBlockEntity.isValidTemplate(stack, now);
        }
    }

    private static class IceSlot extends Slot {
        IceSlot(Container c, int index, int x, int y) { super(c, index, x, y); }
        @Override public boolean mayPlace(@Nullable ItemStack stack) {
            return stack != null && CryoPreservatorBlockEntity.isCoolant(stack);
        }
    }
}
