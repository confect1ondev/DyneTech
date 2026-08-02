package com.confect1on.dynetech.block;

import com.confect1on.dynetech.blockentity.ShoalBloomBlockEntity;
import com.confect1on.dynetech.gene.ShoalContact;
import com.confect1on.dynetech.item.DTItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
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
 * Consumed-matter shell. Inert once placed - it does not spread on its own. The bloom covers
 * the block it grew over rather than destroying it: breaking the bloom uncovers the original
 * block in place and pays out one Shoal Residue.
 */
public class ShoalBloomBlock extends Block implements EntityBlock {

    public ShoalBloomBlock(Properties properties) {
        super(properties);
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ShoalBloomBlockEntity(pos, state);
    }

    /**
     * Swap the bloom back to whatever it covered. Returns false for player-placed blooms with
     * nothing underneath, which then break like a normal block. Called from the global
     * break-event hook, which cancels the vanilla break when this succeeds.
     */
    public static boolean restoreCovered(ServerLevel server, BlockPos pos) {
        if (!(server.getBlockEntity(pos) instanceof ShoalBloomBlockEntity be)) return false;
        BlockState covered = be.covered();
        if (covered == null) return false;
        // Vanilla break FX for the bloom, then the covered block takes its spot.
        server.levelEvent(2001, pos, Block.getId(server.getBlockState(pos)));
        server.setBlock(pos, covered, 3);
        dropResidue(server, pos);
        return true;
    }

    private static void dropResidue(ServerLevel server, BlockPos pos) {
        ItemStack drop = new ItemStack(DTItems.SHOAL_RESIDUE.get());
        ItemEntity item = new ItemEntity(server,
                pos.getX() + 0.5D, pos.getY() + 1.1D, pos.getZ() + 0.5D, drop);
        item.setDefaultPickUpDelay();
        server.addFreshEntity(item);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // Only reached for blooms with nothing covered (the break event restores the rest).
        if (level instanceof ServerLevel server) {
            dropResidue(server, pos);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        return 7;
    }

    /**
     * Server-side. Walking on a bloom infects the walker with the silent incubation gene. Skips
     * creative/spectator via the shared helper.
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
