package com.confect1on.dynetech.block;

import com.confect1on.dynetech.blockentity.ShoalGrowthBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Rearranged-matter cluster. Stores the block states pulled out of the world by an infected
 * player's seep and drops every one of them on break; nothing is ever destroyed by the swarm
 * itself, only relocated.
 */
public class ShoalGrowthBlock extends Block implements EntityBlock {

    public ShoalGrowthBlock(Properties properties) {
        super(properties);
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ShoalGrowthBlockEntity(pos, state);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel server) {
            BlockEntity be = server.getBlockEntity(pos);
            if (be instanceof ShoalGrowthBlockEntity growth) {
                for (ItemStack drop : growth.releaseStored()) {
                    if (drop.isEmpty()) continue;
                    double dx = pos.getX() + 0.5D;
                    double dy = pos.getY() + 0.5D;
                    double dz = pos.getZ() + 0.5D;
                    ItemEntity item = new ItemEntity(server, dx, dy, dz, drop);
                    item.setDefaultPickUpDelay();
                    server.addFreshEntity(item);
                }
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        return 6;
    }
}
