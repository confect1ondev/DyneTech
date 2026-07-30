package com.confect1on.dynetech.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.config.DTConfig;
import com.confect1on.dynetech.gene.VialContents;
import com.confect1on.dynetech.gene.VialState;
import com.confect1on.dynetech.item.GeneVialItem;
import com.confect1on.dynetech.menu.CryoPreservatorMenu;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Central blood slot plus a 2x2 ice input grid. Ice lives directly in the four ice slots (each
 * capped at vanilla's 64) so the total capacity is four stacks without violating the vanilla
 * ItemStack serialization codec's 1..99 count limit. Burn consumes from the first non-empty
 * ice slot at each ice boundary.
 *
 * <p>The tick loop derives its time step from {@link Level#getGameTime()} instead of assuming
 * one tick per call. If the containing chunk unloads and later reloads, the next tick catches
 * up in a single O(1) call, consuming exactly as much ice as the offline gap covers and pushing
 * the vial expiry forward by the same amount.
 */
public class CryoPreservatorBlockEntity extends BlockEntity implements MenuProvider {

    public static final int SLOT_BLOOD = 0;
    public static final int SLOT_ICE_A = 1;
    public static final int SLOT_ICE_B = 2;
    public static final int SLOT_ICE_C = 3;
    public static final int SLOT_ICE_D = 4;
    public static final int SIZE = 5;
    public static final int[] ICE_SLOTS = {SLOT_ICE_A, SLOT_ICE_B, SLOT_ICE_C, SLOT_ICE_D};

    /** 4 stacks of 64 = the display cap for the fill gauge. */
    public static final int MAX_ICE_STORAGE = 4 * 64;

    private static final int DATA_ICE_STORED = 0;
    private static final int DATA_ICE_PROGRESS = 1;
    private static final int DATA_INTERVAL = 2;
    private static final int DATA_PRESERVING = 3;
    private static final int DATA_SIZE = 4;

    private final SimpleContainer inv = new SimpleContainer(SIZE) {
        @Override public void setChanged() {
            super.setChanged();
            CryoPreservatorBlockEntity.this.setChanged();
        }
    };

    private int iceProgress = 0;
    /** {@link Long#MIN_VALUE} = "no prior tick recorded". Sentinel avoids colliding with a
     *  legitimate saved game time of 0 in freshly-created worlds. */
    private long lastTickGameTime = Long.MIN_VALUE;
    private boolean preservingLastTick = false;

    private final ContainerData data = new ContainerData() {
        @Override public int get(int index) {
            return switch (index) {
                case DATA_ICE_STORED -> iceCount();
                case DATA_ICE_PROGRESS -> iceProgress;
                case DATA_INTERVAL -> DTConfig.CRYO_ICE_INTERVAL_TICKS.get();
                case DATA_PRESERVING -> preservingLastTick ? 1 : 0;
                default -> 0;
            };
        }
        @Override public void set(int index, int value) {
            if (index == DATA_ICE_PROGRESS) iceProgress = value;
            else if (index == DATA_PRESERVING) preservingLastTick = value != 0;
        }
        @Override public int getCount() { return DATA_SIZE; }
    };

    public CryoPreservatorBlockEntity(BlockPos pos, BlockState state) {
        super(DTBlockEntities.CRYO_PRESERVATOR.get(), pos, state);
    }

    public Container inventory() { return inv; }
    public ContainerData data() { return data; }
    public int iceProgress() { return iceProgress; }

    /** Sum of ice sitting across every ice slot. Does not include the in-progress ice. */
    public int iceCount() {
        int total = 0;
        for (int slot : ICE_SLOTS) {
            ItemStack s = inv.getItem(slot);
            if (isCoolant(s)) total += s.getCount();
        }
        return total;
    }

    public static boolean isCoolant(ItemStack stack) {
        return stack.is(Items.ICE) || stack.is(Items.PACKED_ICE) || stack.is(Items.BLUE_ICE);
    }

    /**
     * A template vial must be a player-blood RAW that has not yet passed its expiry. Expired
     * samples are refused so the machine can't be used to zombify a dead vial back into service.
     */
    public static boolean isValidTemplate(ItemStack stack, long now) {
        if (!(stack.getItem() instanceof GeneVialItem)) return false;
        VialContents c = GeneVialItem.getContents(stack);
        if (c.state() != VialState.RAW || !c.isPlayerBlood() || c.donor().isEmpty()) return false;
        return !c.isExpired(now);
    }

    public void serverTick(Level level) {
        long now = level.getGameTime();
        // First tick after placement uses delta = 1. A reloaded BE keeps its saved
        // lastTickGameTime, so `now - lastTickGameTime` reflects however long the chunk was
        // unloaded. MIN_VALUE distinguishes "never ticked" from "genuinely zero".
        long delta = 1L;
        if (lastTickGameTime != Long.MIN_VALUE) {
            long raw = now - lastTickGameTime;
            if (raw > 0L) delta = raw;
        }
        lastTickGameTime = now;

        ItemStack blood = inv.getItem(SLOT_BLOOD);
        boolean stateChanged = false;

        long preservedTicks = 0L;
        if (isValidTemplate(blood, now) && (iceProgress > 0 || iceCount() > 0)) {
            preservedTicks = applyPreservation(delta);
            if (preservedTicks > 0L) {
                advanceExpiry(blood, preservedTicks);
                stateChanged = true;
            }
        }

        boolean preservingNow = preservedTicks > 0L;
        if (preservingNow != preservingLastTick) {
            preservingLastTick = preservingNow;
            stateChanged = true;
        }
        if (stateChanged) markUpdated();
    }

    /**
     * Consume up to {@code delta} ticks worth of fuel. Ice items are shrunk out of the ice
     * slots one-by-one as each starts burning, so the slot counts remain the truth about how
     * much fuel is stored. Returns how many ticks of preservation were actually applied.
     */
    private long applyPreservation(long delta) {
        long interval = Math.max(1L, DTConfig.CRYO_ICE_INTERVAL_TICKS.get());
        long applied = 0L;
        long remaining = delta;

        while (remaining > 0L) {
            if (iceProgress == 0) {
                // Need to light up a new ice unit. Pull from the first slot that has any.
                if (!consumeOneIce()) break;
            }
            long step = Math.min(remaining, interval - iceProgress);
            iceProgress += (int) step;
            remaining -= step;
            applied += step;
            if (iceProgress >= interval) iceProgress = 0;
        }
        return applied;
    }

    /** Shrink one ice item from the first non-empty ice slot. Returns false if all slots are empty. */
    private boolean consumeOneIce() {
        for (int slot : ICE_SLOTS) {
            ItemStack s = inv.getItem(slot);
            if (isCoolant(s) && !s.isEmpty()) {
                s.shrink(1);
                return true;
            }
        }
        return false;
    }

    /** Total preservation ticks available before we'd need more ice, given current state. */
    public long fuelTicksRemaining() {
        long interval = Math.max(1L, DTConfig.CRYO_ICE_INTERVAL_TICKS.get());
        long fromCurrent = iceProgress > 0 ? interval - iceProgress : 0L;
        return fromCurrent + (long) iceCount() * interval;
    }

    /**
     * Push the vial's expiry timestamp forward by however many ticks we preserved. If it had
     * somehow fallen behind world time (e.g. loaded from disk mid-run), clamp forward to at
     * least one tick ahead so it stays viable while ice is available.
     */
    private void advanceExpiry(ItemStack blood, long ticks) {
        VialContents c = GeneVialItem.getContents(blood);
        Optional<Long> exp = c.expiresAtGameTime();
        if (exp.isEmpty()) return;
        long next = Math.max(exp.get() + ticks, lastTickGameTime + 1L);
        blood.set(DTDataComponents.VIAL_CONTENTS.get(), c.withExpiry(Optional.of(next)));
    }

    public void dropContents(Level level) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) {
                level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(
                        level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, s));
            }
        }
        inv.clearContent();
    }

    private void markUpdated() {
        setChanged();
        if (level != null) level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // Only touch the inventory when the tag actually carries one. Otherwise a state-only
        // client-side BE update packet would flash all slots empty for a tick.
        if (tag.contains("Inv")) {
            inv.clearContent();
            inv.fromTag(tag.getList("Inv", 10), registries);
        }
        iceProgress = Math.max(0, tag.getInt("IceProgress"));
        // Missing key = fresh BE that has never ticked. Explicit check so the sentinel doesn't
        // get overwritten by NBT's default-of-zero.
        lastTickGameTime = tag.contains("LastTick", 4 /* LONG */)
                ? tag.getLong("LastTick") : Long.MIN_VALUE;
        preservingLastTick = tag.getBoolean("Preserving");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inv", inv.createTag(registries));
        tag.putInt("IceProgress", iceProgress);
        tag.putLong("LastTick", lastTickGameTime);
        tag.putBoolean("Preserving", preservingLastTick);
    }

    /**
     * Update packets deliberately omit the inventory. Slot contents are synced to any watching
     * menu via {@link AbstractContainerMenu#broadcastChanges}, and rendering outside the GUI
     * doesn't reference the inventory. Including it here would round-trip the whole 5-slot
     * container every time preservation state flips, which caused a visible slot flash when
     * players took the vial in or out.
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putInt("IceProgress", iceProgress);
        tag.putLong("LastTick", lastTickGameTime);
        tag.putBoolean("Preserving", preservingLastTick);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.dynetech.cryo_preservator");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInv, Player player) {
        return new CryoPreservatorMenu(id, playerInv, this);
    }
}
