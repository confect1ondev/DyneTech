package com.confect1on.dynetech.block;

import com.confect1on.dynetech.blockentity.ShoalGrowthBlockEntity;
import com.confect1on.dynetech.gene.ShoalContact;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Rearranged-matter cluster. Stores the blocks pulled out of the world by an infected player's
 * seep, along with where each one came from, and puts them all back on break; nothing is ever
 * destroyed by the swarm itself, only borrowed.
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
                growth.restoreAll(server, pos);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        return 6;
    }

    /**
     * Server-side. Touching a Shoal block infects the walker with the silent incubation gene.
     * Skips creative/spectator via the shared helper.
     */
    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        super.stepOn(level, pos, state, entity);
        if (level instanceof ServerLevel server && entity instanceof Player player) {
            ShoalContact.tryInfectByContact(server, player);
        }
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        super.animateTick(state, level, pos, random);
        ShoalCluster.animateHaze(level, pos, random);
    }
}
