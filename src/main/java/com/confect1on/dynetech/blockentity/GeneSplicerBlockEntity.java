package com.confect1on.dynetech.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
import com.confect1on.dynetech.menu.GeneSplicerMenu;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Two-slot ticking splicer. Both inputs transform in place when the timer completes:
 * ISOLATED + ISOLATED → merged ISOLATED in slot A (Empty container in slot B),
 * ISOLATED + EMPTY → SERUM in slot A (slot B consumed as container).
 */
public class GeneSplicerBlockEntity extends BlockEntity implements MenuProvider {

    public static final int SIZE = 2;
    public static final int SLOT_A = 0;
    public static final int SLOT_B = 1;

    private static final int DATA_PROGRESS = 0;
    private static final int DATA_THRESHOLD = 1;
    private static final int DATA_SIZE = 2;

    private final SimpleContainer inv = new SimpleContainer(SIZE) {
        @Override public void setChanged() {
            super.setChanged();
            GeneSplicerBlockEntity.this.setChanged();
        }
        @Override public int getMaxStackSize() { return 1; }
    };
    private int progressTicks = 0;

    private final ContainerData data = new ContainerData() {
        @Override public int get(int index) {
            return switch (index) {
                case DATA_PROGRESS -> progressTicks;
                case DATA_THRESHOLD -> DTConfig.SPLICER_TICKS.get();
                default -> 0;
            };
        }
        @Override public void set(int index, int value) {
            if (index == DATA_PROGRESS) progressTicks = value;
        }
        @Override public int getCount() { return DATA_SIZE; }
    };

    public GeneSplicerBlockEntity(BlockPos pos, BlockState state) {
        super(DTBlockEntities.GENE_SPLICER.get(), pos, state);
    }

    public Container inventory() { return inv; }
    public ContainerData data() { return data; }

    public void serverTick(Level level) {
        ItemStack a = inv.getItem(SLOT_A);
        ItemStack b = inv.getItem(SLOT_B);

        // Both slots must hold filled Isolated vials. Empty vials or empty slots halt the machine
        // - otherwise a completed merge would immediately re-trigger on (merged, Empty container).
        VialContents ca = GeneVialItem.getContents(a);
        VialContents cb = GeneVialItem.getContents(b);
        Optional<VialContents> result = (a.getItem() instanceof GeneVialItem && b.getItem() instanceof GeneVialItem)
                ? GeneOps.splice(ca, cb)
                : Optional.empty();
        if (result.isEmpty()) {
            if (progressTicks != 0) { progressTicks = 0; markUpdated(); }
            return;
        }

        progressTicks++;
        if (progressTicks % 4 == 0) markUpdated();
        if (progressTicks < DTConfig.SPLICER_TICKS.get()) return;

        // Output always lands in slot B. Slot A becomes an Empty vial - it's the container
        // that would otherwise be lost, so the player can pull it back and reuse.
        inv.setItem(SLOT_A, GeneVialItem.empty());
        inv.setItem(SLOT_B, GeneVialItem.withContents(result.get()));
        progressTicks = 0;
        level.playSound(null, worldPosition, SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 0.7F, 1.0F);
        markUpdated();
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

    // -- Persistence + sync --

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

    // -- Menu --

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.dynetech.gene_splicer");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInv, Player player) {
        return new GeneSplicerMenu(id, playerInv, this);
    }
}
