package com.confect1on.dynetech.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Storage for a Shoal Growth cluster. Holds up to {@link #CAPACITY} block-ids (as itemstacks).
 * Ordering is preserved for tooltips/debug but not otherwise meaningful. NBT serialization uses
 * a flat list of resource-location strings so the payload stays trivially small.
 */
public class ShoalGrowthBlockEntity extends BlockEntity {

    public static final int CAPACITY = 16;

    private final List<ItemStack> stored = new ArrayList<>();

    public ShoalGrowthBlockEntity(BlockPos pos, BlockState state) {
        super(DTBlockEntities.SHOAL_GROWTH.get(), pos, state);
    }

    public int size() {
        return stored.size();
    }

    public boolean isFull() {
        return stored.size() >= CAPACITY;
    }

    /**
     * Absorb a block state. The block is stored as its Item form; block-entity payloads are not
     * captured because the seep never targets container blocks in the first place.
     */
    public boolean absorb(BlockState state) {
        if (isFull()) return false;
        Block block = state.getBlock();
        ItemStack asItem = new ItemStack(block.asItem());
        if (asItem.isEmpty() || asItem.getItem() == Items.AIR) return false;
        stored.add(asItem);
        setChanged();
        return true;
    }

    public List<ItemStack> releaseStored() {
        List<ItemStack> copy = new ArrayList<>(stored);
        stored.clear();
        setChanged();
        return Collections.unmodifiableList(copy);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        stored.clear();
        ListTag list = tag.getList("Stored", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            ResourceLocation id = ResourceLocation.tryParse(list.getString(i));
            if (id == null) continue;
            Block block = BuiltInRegistries.BLOCK.get(id);
            if (block == null) continue;
            ItemStack stack = new ItemStack(block.asItem());
            if (!stack.isEmpty()) stored.add(stack);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (ItemStack s : stored) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
            list.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        }
        tag.put("Stored", list);
    }
}
