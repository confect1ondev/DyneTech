package com.confect1on.dynetech.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Vigil goal: seek out and destroy light-emitting blocks near a player. Preempts pursuit
 * whenever a nearby light is closer to the Vigil than the player is; this is what makes a
 * dark-cave chase visceral, the player watches their torches get snuffed one by one.
 *
 * Placed at a lower priority number than MeleeAttackGoal so it wins the MOVE flag whenever
 * canUse is satisfied; when it stops (no more lights within reach, or player got closer),
 * MeleeAttackGoal takes over on the next goal-selector eval.
 */
public class LightExtinguishGoal extends Goal {

    private static final int SEARCH_COOLDOWN_TICKS = 10;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final double BREAK_DISTANCE_SQR = 4.0; // within 2 blocks
    private static final double PLAYER_SCAN_RANGE = 48.0;

    private final VigilEntity mob;
    private final int searchRadius;

    private BlockPos targetBlock;
    private int searchCooldown;
    private int repathCooldown;

    public LightExtinguishGoal(VigilEntity mob, double radius) {
        this.mob = mob;
        this.searchRadius = Math.max(1, (int) Math.ceil(radius));
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Level level = mob.level();
        Player player = level.getNearestPlayer(mob, PLAYER_SCAN_RANGE);
        if (player == null || player.isSpectator() || !player.isAlive()) return false;

        if (searchCooldown > 0) {
            searchCooldown--;
            return false;
        }
        searchCooldown = SEARCH_COOLDOWN_TICKS;

        BlockPos light = findNearestLightNear(level, player, searchRadius);
        if (light == null) return false;

        // Priority rule: light-first only if the Vigil is nearer to the light than to the player.
        // If the player is closer, MeleeAttackGoal takes over. Pursuit trumps extinguishing.
        double distToLightSqr = mob.distanceToSqr(centerOf(light));
        double distToPlayerSqr = mob.distanceToSqr(player);
        if (distToPlayerSqr <= distToLightSqr) return false;

        this.targetBlock = light;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (targetBlock == null) return false;
        Level level = mob.level();
        BlockState state = level.getBlockState(targetBlock);
        if (state.getLightEmission(level, targetBlock) <= 0) return false;

        // If the player closes in mid-chase, drop the light and switch to pursuit next eval.
        Player player = level.getNearestPlayer(mob, PLAYER_SCAN_RANGE);
        if (player != null) {
            double distToLightSqr = mob.distanceToSqr(centerOf(targetBlock));
            double distToPlayerSqr = mob.distanceToSqr(player);
            if (distToPlayerSqr < distToLightSqr) return false;
        }
        return true;
    }

    @Override
    public void start() {
        pathToTarget();
        repathCooldown = REPATH_INTERVAL_TICKS;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        this.targetBlock = null;
    }

    @Override
    public void tick() {
        if (targetBlock == null) return;
        Vec3 center = centerOf(targetBlock);
        mob.getLookControl().setLookAt(center.x, center.y, center.z);

        if (mob.distanceToSqr(center) <= BREAK_DISTANCE_SQR) {
            breakAndDrop();
            return;
        }

        if (--repathCooldown <= 0) {
            pathToTarget();
            repathCooldown = REPATH_INTERVAL_TICKS;
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private void pathToTarget() {
        if (targetBlock == null) return;
        mob.getNavigation().moveTo(
                targetBlock.getX() + 0.5,
                targetBlock.getY(),
                targetBlock.getZ() + 0.5,
                1.0);
    }

    private void breakAndDrop() {
        if (!(mob.level() instanceof ServerLevel server)) {
            targetBlock = null;
            return;
        }
        BlockState state = server.getBlockState(targetBlock);
        if (state.getLightEmission(server, targetBlock) <= 0) {
            targetBlock = null;
            return;
        }
        // destroyBlock(pos, dropBlock=true, breakingEntity) plays the vanilla break FX and drops
        // items per the block's loot table. Torches, lanterns, glowstone, etc. all cooperate.
        server.destroyBlock(targetBlock, true, mob);
        targetBlock = null;
    }

    private static BlockPos findNearestLightNear(Level level, Player player, int r) {
        BlockPos origin = player.blockPosition();
        BlockPos closest = null;
        double bestDistSqr = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int bx = origin.getX() + dx;
                    int by = origin.getY() + dy;
                    int bz = origin.getZ() + dz;
                    // Prune by distance before touching the blockstate. As bestDistSqr shrinks,
                    // subsequent iterations skip the expensive getBlockState/getLightEmission
                    // pair entirely.
                    double distSqr = player.distanceToSqr(bx + 0.5, by + 0.5, bz + 0.5);
                    if (distSqr >= bestDistSqr) continue;
                    cursor.set(bx, by, bz);
                    BlockState state = level.getBlockState(cursor);
                    if (state.getLightEmission(level, cursor) <= 0) continue;
                    // Skip fluids (lava) and fire: destroying these either does nothing useful or
                    // is a spread-block that returns no item.
                    if (!state.getFluidState().isEmpty()) continue;
                    bestDistSqr = distSqr;
                    closest = cursor.immutable();
                }
            }
        }
        return closest;
    }

    private static Vec3 centerOf(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}
