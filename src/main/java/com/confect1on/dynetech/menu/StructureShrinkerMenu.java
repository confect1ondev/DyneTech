package com.confect1on.dynetech.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import com.confect1on.dynetech.block.DTBlocks;
import com.confect1on.dynetech.blockentity.StructureShrinkerBlockEntity;
import org.jetbrains.annotations.Nullable;

public class StructureShrinkerMenu extends AbstractContainerMenu {

    @Nullable
    private final StructureShrinkerBlockEntity blockEntity;
    private final ContainerLevelAccess access;

    public StructureShrinkerMenu(int id, Inventory inv, @Nullable StructureShrinkerBlockEntity be) {
        super(DTMenus.STRUCTURE_SHRINKER.get(), id);
        this.blockEntity = be;
        this.access = be != null
                ? ContainerLevelAccess.create(be.getLevel(), be.getBlockPos())
                : ContainerLevelAccess.NULL;
    }

    @Nullable
    public StructureShrinkerBlockEntity getBlockEntity() {
        return blockEntity;
    }

    public BlockPos getSelectionStart() {
        return blockEntity != null ? blockEntity.getSelectionStart() : BlockPos.ZERO;
    }

    public BlockPos getSelectionEnd() {
        return blockEntity != null ? blockEntity.getSelectionEnd() : BlockPos.ZERO;
    }

    public boolean hasSelection() {
        return blockEntity != null
                && !(blockEntity.getSelectionStartRelative().equals(BlockPos.ZERO)
                     && blockEntity.getSelectionEndRelative().equals(BlockPos.ZERO));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return access.evaluate((level, pos) -> level.getBlockState(pos).is(DTBlocks.STRUCTURE_SHRINKER.get())
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0, true);
    }
}
