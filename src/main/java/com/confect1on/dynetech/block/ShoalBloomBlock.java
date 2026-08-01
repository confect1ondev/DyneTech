package com.confect1on.dynetech.block;

import com.confect1on.dynetech.item.DTItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Consumed-matter marker. Inert once placed - it does not spread on its own. Breaking a bloom
 * drops one Shoal Residue so cleanup pays out a token amount of the future material.
 */
public class ShoalBloomBlock extends Block {

    public ShoalBloomBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel server) {
            ItemStack drop = new ItemStack(DTItems.SHOAL_RESIDUE.get());
            ItemEntity item = new ItemEntity(server,
                    pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, drop);
            item.setDefaultPickUpDelay();
            server.addFreshEntity(item);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        return 7;
    }
}
