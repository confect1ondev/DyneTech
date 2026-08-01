package com.confect1on.dynetech.item;

import com.confect1on.dynetech.entity.DTEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Shoal isn't a {@code Mob}, so it can't use {@link net.neoforged.neoforge.common.DeferredSpawnEggItem}
 * (which is typed against Mob). This item is functionally the same for MVP purposes: right-click
 * a block face to spawn a Shoal on top of it.
 */
public class ShoalSpawnEggItem extends Item {

    public ShoalSpawnEggItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (!(level instanceof ServerLevel server)) {
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        ItemStack stack = context.getItemInHand();
        BlockPos hitPos = context.getClickedPos();
        BlockState state = server.getBlockState(hitPos);
        BlockPos spawnAt = state.getCollisionShape(server, hitPos).isEmpty()
                ? hitPos
                : hitPos.relative(context.getClickedFace() == Direction.DOWN ? Direction.DOWN : Direction.UP);

        // A single Shoal. The multi-lobe reading now lives inside the swarm sim, where the
        // cloud splits into wandering sub-clusters and folds back together, instead of three
        // separate entities that tended to read as disconnected neighbors.
        DTEntityTypes.SHOAL.get().spawn(server, stack, context.getPlayer(),
                spawnAt, MobSpawnType.SPAWN_EGG, true, false);
        if (context.getPlayer() != null && !context.getPlayer().getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResult.CONSUME;
    }
}
