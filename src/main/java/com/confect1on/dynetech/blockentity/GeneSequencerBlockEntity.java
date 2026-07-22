package com.confect1on.dynetech.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import com.confect1on.dynetech.config.DTConfig;
import com.confect1on.dynetech.gene.GeneOps;
import com.confect1on.dynetech.gene.PerkLifecycle;
import com.confect1on.dynetech.gene.VialContents;
import com.confect1on.dynetech.gene.VialState;
import com.confect1on.dynetech.item.GeneVialItem;
import com.confect1on.dynetech.menu.GeneSequencerMenu;
import org.jetbrains.annotations.Nullable;

/**
 * All-or-nothing sequencer. Requires a RAW vial in the input slot AND every output slot filled
 * with an Empty vial. When the timer completes, all outputs become Isolated vials (each rolled
 * independently from the raw's perk pool) and the input becomes Empty.
 */
public class GeneSequencerBlockEntity extends BlockEntity implements MenuProvider {

    public static final int SIZE = 4;
    public static final int INPUT_SLOT = 0;
    public static final int OUTPUT_SLOT_START = 1;
    public static final int OUTPUT_SLOT_COUNT = 3;

    private static final int DATA_PROGRESS = 0;
    private static final int DATA_THRESHOLD = 1;
    private static final int DATA_SIZE = 2;

    private final SimpleContainer inv = new SimpleContainer(SIZE) {
        @Override public void setChanged() {
            super.setChanged();
            GeneSequencerBlockEntity.this.setChanged();
        }
        @Override public int getMaxStackSize() { return 1; }
    };
    private int progressTicks = 0;

    private final ContainerData data = new ContainerData() {
        @Override public int get(int index) {
            return switch (index) {
                case DATA_PROGRESS -> progressTicks;
                case DATA_THRESHOLD -> DTConfig.SEQUENCER_TICKS.get();
                default -> 0;
            };
        }
        @Override public void set(int index, int value) {
            if (index == DATA_PROGRESS) progressTicks = value;
        }
        @Override public int getCount() { return DATA_SIZE; }
    };

    public GeneSequencerBlockEntity(BlockPos pos, BlockState state) {
        super(DTBlockEntities.GENE_SEQUENCER.get(), pos, state);
    }

    public Container inventory() { return inv; }
    public ContainerData data() { return data; }

    public void serverTick(Level level) {
        ItemStack input = inv.getItem(INPUT_SLOT);
        VialContents inputContents = GeneVialItem.getContents(input);

        boolean canRun = input.getItem() instanceof GeneVialItem
                && inputContents.state() == VialState.RAW
                && !inputContents.isPlayerBlood() // player-blood vials are inspection-only
                && !inputContents.perks().isEmpty() // guard: empty pool would consume input for no output
                && allOutputsHoldEmptyVials();

        if (!canRun) {
            if (progressTicks != 0) { progressTicks = 0; markUpdated(); }
            return;
        }

        progressTicks++;
        if (progressTicks % 4 == 0) markUpdated();
        if (progressTicks < DTConfig.SEQUENCER_TICKS.get()) return;

        // All-or-nothing: process every output slot in one shot, then drain the raw vial.
        for (int i = OUTPUT_SLOT_START; i < OUTPUT_SLOT_START + OUTPUT_SLOT_COUNT; i++) {
            VialContents isolated = GeneOps.sequenceOne(inputContents, level.random);
            inv.setItem(i, GeneVialItem.withContents(isolated));
        }
        inv.setItem(INPUT_SLOT, GeneVialItem.empty());
        progressTicks = 0;
        level.playSound(null, worldPosition, SoundEvents.SPYGLASS_USE, SoundSource.BLOCKS, 0.6F, 1.4F);
        markUpdated();
    }

    private boolean allOutputsHoldEmptyVials() {
        for (int i = OUTPUT_SLOT_START; i < OUTPUT_SLOT_START + OUTPUT_SLOT_COUNT; i++) {
            ItemStack s = inv.getItem(i);
            if (!(s.getItem() instanceof GeneVialItem)) return false;
            if (GeneVialItem.getContents(s).state() != VialState.EMPTY) return false;
        }
        return true;
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
        inv.clearContent();
        if (tag.contains("Inv")) inv.fromTag(tag.getList("Inv", 10), registries);
        progressTicks = tag.getInt("Progress");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inv", inv.createTag(registries));
        tag.putInt("Progress", progressTicks);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.dynetech.gene_sequencer");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInv, Player player) {
        return new GeneSequencerMenu(id, playerInv, this);
    }
}
