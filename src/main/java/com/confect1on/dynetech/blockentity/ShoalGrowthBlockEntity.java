package com.confect1on.dynetech.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Storage for a Shoal Growth cluster. Holds up to {@link #CAPACITY} consumed blocks, each with
 * the exact state and the position it was taken from, so breaking the growth puts everything
 * back where it was. Block-entity payloads are never involved because the seep refuses to
 * target container blocks in the first place.
 */
public class ShoalGrowthBlockEntity extends BlockEntity {

    public static final int CAPACITY = 16;

    private final List<StoredBlock> stored = new ArrayList<>();

    public ShoalGrowthBlockEntity(BlockPos pos, BlockState state) {
        super(DTBlockEntities.SHOAL_GROWTH.get(), pos, state);
    }

    public int size() {
        return stored.size();
    }

    public boolean isFull() {
        return stored.size() >= CAPACITY;
    }

    public boolean absorb(BlockState state, BlockPos from) {
        if (isFull()) return false;
        stored.add(new StoredBlock(state, from.immutable()));
        setChanged();
        return true;
    }

    /**
     * Put every consumed block back exactly where it came from. If something now occupies an
     * origin, that entry falls back to an item drop at the growth so nothing is ever lost.
     */
    public void restoreAll(ServerLevel server, BlockPos selfPos) {
        for (StoredBlock s : stored) {
            BlockState at = server.getBlockState(s.pos());
            if ((at.isAir() || at.canBeReplaced()) && server.setBlock(s.pos(), s.state(), 3)) {
                continue;
            }
            ItemStack drop = new ItemStack(s.state().getBlock().asItem());
            if (drop.isEmpty()) continue;
            ItemEntity item = new ItemEntity(server,
                    selfPos.getX() + 0.5D, selfPos.getY() + 0.5D, selfPos.getZ() + 0.5D, drop);
            item.setDefaultPickUpDelay();
            server.addFreshEntity(item);
        }
        stored.clear();
        setChanged();
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        stored.clear();
        ListTag list = tag.getList("Stored", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            BlockState state = NbtUtils.readBlockState(
                    registries.lookupOrThrow(Registries.BLOCK), entry.getCompound("State"));
            if (state.isAir()) continue;
            Optional<BlockPos> pos = NbtUtils.readBlockPos(entry, "Pos");
            if (pos.isEmpty()) continue;
            stored.add(new StoredBlock(state, pos.get()));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (StoredBlock s : stored) {
            CompoundTag entry = new CompoundTag();
            entry.put("State", NbtUtils.writeBlockState(s.state()));
            entry.put("Pos", NbtUtils.writeBlockPos(s.pos()));
            list.add(entry);
        }
        tag.put("Stored", list);
    }

    private record StoredBlock(BlockState state, BlockPos pos) {}
}
