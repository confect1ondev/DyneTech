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

        // Shoals come as a bonded trio: one primary and two secondaries that loosely travel,
        // hunt, and die together while running their own state machines.
        var primary = DTEntityTypes.SHOAL.get().spawn(server, stack, context.getPlayer(),
                spawnAt, MobSpawnType.SPAWN_EGG, true, false);
        var second = DTEntityTypes.SHOAL.get().spawn(server, stack, context.getPlayer(),
                spawnAt.offset(2, 0, 2), MobSpawnType.SPAWN_EGG, true, false);
        var third = DTEntityTypes.SHOAL.get().spawn(server, stack, context.getPlayer(),
                spawnAt.offset(-2, 0, 2), MobSpawnType.SPAWN_EGG, true, false);
        if (primary != null && second != null && third != null) {
            primary.addPartner(second.getUUID());
            primary.addPartner(third.getUUID());
            second.addPartner(primary.getUUID());
            second.setPrimary(false);
            third.addPartner(primary.getUUID());
            third.setPrimary(false);
        }
        if (context.getPlayer() != null && !context.getPlayer().getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResult.CONSUME;
    }
}
