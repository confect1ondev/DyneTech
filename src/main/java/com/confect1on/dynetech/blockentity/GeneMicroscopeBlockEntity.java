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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import com.confect1on.dynetech.menu.GeneMicroscopeMenu;
import org.jetbrains.annotations.Nullable;

/**
 * One-slot inspection block. Whatever vial is placed inside is the subject of the microscope's
 * info panel. No processing happens here - the sequencer and splicer handle transformations;
 * the microscope only reads.
 */
public class GeneMicroscopeBlockEntity extends BlockEntity implements MenuProvider {

    public static final int SIZE = 1;

    private final SimpleContainer inv = new SimpleContainer(SIZE) {
        @Override public void setChanged() {
            super.setChanged();
            GeneMicroscopeBlockEntity.this.setChanged();
        }
        @Override public int getMaxStackSize() { return 1; }
    };

    public GeneMicroscopeBlockEntity(BlockPos pos, BlockState state) {
        super(DTBlockEntities.GENE_MICROSCOPE.get(), pos, state);
    }

    public Container inventory() { return inv; }

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
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inv", inv.createTag(registries));
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
        return Component.translatable("block.dynetech.gene_microscope");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInv, Player player) {
        return new GeneMicroscopeMenu(id, playerInv, this);
    }
}
