package com.confect1on.dynetech.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Remembers the block a Shoal Bloom grew over. The bloom is a shell, not a replacement: break
 * it and the covered block is put back in place. Player-placed blooms have nothing underneath
 * and break like any ordinary block.
 */
public class ShoalBloomBlockEntity extends BlockEntity {

    @Nullable
    private BlockState covered;

    public ShoalBloomBlockEntity(BlockPos pos, BlockState state) {
        super(DTBlockEntities.SHOAL_BLOOM.get(), pos, state);
    }

    public void setCovered(BlockState state) {
        this.covered = state;
        setChanged();
    }

    @Nullable
    public BlockState covered() {
        return covered;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        covered = null;
        if (tag.contains("Covered")) {
            BlockState state = NbtUtils.readBlockState(
                    registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("Covered"));
            if (!state.isAir()) covered = state;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (covered != null) {
            tag.put("Covered", NbtUtils.writeBlockState(covered));
        }
    }
}
