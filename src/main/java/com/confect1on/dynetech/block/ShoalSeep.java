package com.confect1on.dynetech.block;

import com.confect1on.dynetech.blockentity.ShoalGrowthBlockEntity;
import com.confect1on.dynetech.config.DTConfig;
import com.confect1on.dynetech.gene.DTAttachments;
import com.confect1on.dynetech.gene.ShoalHostState;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BedBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Per-carrier tick that converts nearby blocks. Three outcomes:
 *
 * <ul>
 *   <li>{@code displace}: block is nudged a block or so from where it sat, so lingering
 *   carriers slowly unsettle their surroundings without destroying anything.</li>
 *   <li>{@code growth feed}: block leaves the world and enters a nearby Shoal Growth spire
 *   ({@link ShoalGrowthBlockEntity}). Full spires grow taller off the existing cluster rather
 *   than starting new piles. Broken growths drop every block they absorbed.</li>
 *   <li>{@code consume}: block is replaced in place with Shoal Bloom. Breaking bloom drops one
 *   Shoal Residue item.</li>
 * </ul>
 *
 * <p>All hard rules (blacklist, protected radii around beds/respawn anchors, container guards,
 * per-day budget, lingering vs passing-through) live here so the perk itself stays a thin marker.
 */
public final class ShoalSeep {

    private static final int SCAN_ATTEMPTS = 24;

    private ShoalSeep() {}

    public static void tick(ServerLevel server, Player player) {
        if (player.isSpectator() || player.isCreative()) return;

        long now = server.getGameTime();
        ShoalHostState state = player.getData(DTAttachments.SHOAL_HOST_STATE.get());
        // Budget days follow day time so /time add and sleeping roll the budget over, while the
        // conversion interval stays on game time and can't be skipped the same way.
        long today = server.getDayTime() / 24_000L;

        int budgetUsed = state.seepBudgetUsed();
        long bookedDay = state.seepDay();
        if (bookedDay != today) {
            budgetUsed = 0;
            bookedDay = today;
        }
        if (budgetUsed >= DTConfig.SEEP_DAILY_BUDGET.get()) {
            saveIfChanged(player, state, state.lastSeepTick(), budgetUsed, bookedDay);
            return;
        }

        if (now - state.lastSeepTick() < DTConfig.SEEP_INTERVAL_TICKS.get()) return;
        if (isMovingFast(player)) return;

        RandomSource rng = server.random;
        BlockPos target = findConvertible(server, player, rng);
        if (target == null) {
            saveIfChanged(player, state, now, budgetUsed, bookedDay);
            return;
        }

        boolean converted;
        if (rng.nextDouble() < DTConfig.SEEP_REARRANGE_WEIGHT.get()) {
            converted = rng.nextDouble() < DTConfig.SEEP_DISPLACE_WEIGHT.get()
                    ? tryDisplace(server, target, rng)
                    : tryGrowthFeed(server, target, rng);
        } else {
            converted = tryConsume(server, target);
        }
        if (converted) {
            budgetUsed++;
            playFx(server, target);
        }

        saveIfChanged(player, state, now, budgetUsed, bookedDay);
    }

    private static void saveIfChanged(Player player, ShoalHostState prev, long lastTick,
                                      int budgetUsed, long day) {
        if (prev.lastSeepTick() == lastTick && prev.seepBudgetUsed() == budgetUsed
                && prev.seepDay() == day) return;
        player.setData(DTAttachments.SHOAL_HOST_STATE.get(),
                prev.withSeepTick(lastTick, budgetUsed, day));
    }

    private static boolean isMovingFast(Player player) {
        Vec3 delta = player.getDeltaMovement();
        double limit = DTConfig.SEEP_LINGER_SPEED.get();
        return delta.x * delta.x + delta.z * delta.z > limit * limit;
    }

    private static BlockPos findConvertible(ServerLevel server, Player player, RandomSource rng) {
        BlockPos playerPos = player.blockPosition();
        BlockPos crafted = null;
        BlockPos natural = null;
        int scanRadius = DTConfig.SEEP_SCAN_RADIUS.get();
        for (int i = 0; i < SCAN_ATTEMPTS; i++) {
            int dx = rng.nextInt(scanRadius * 2 + 1) - scanRadius;
            int dy = rng.nextInt(scanRadius * 2 + 1) - scanRadius;
            int dz = rng.nextInt(scanRadius * 2 + 1) - scanRadius;
            BlockPos p = playerPos.offset(dx, dy, dz);
            if (!isEligible(server, p)) continue;
            BlockState state = server.getBlockState(p);
            if (isCraftedPreference(state)) {
                crafted = p;
                break;
            }
            if (natural == null) natural = p;
        }
        return crafted != null ? crafted : natural;
    }

    private static boolean isEligible(ServerLevel server, BlockPos pos) {
        BlockState state = server.getBlockState(pos);
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        Block block = state.getBlock();
        if (block instanceof ShoalGrowthBlock || block instanceof ShoalBloomBlock) return false;
        if (state.is(Blocks.BEDROCK) || state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN)
                || state.is(Blocks.END_PORTAL_FRAME) || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.BARRIER)
                || state.is(Blocks.REINFORCED_DEEPSLATE)) return false;
        if (state.is(BlockTags.BEDS) || state.is(Blocks.RESPAWN_ANCHOR)) return false;
        if (state.hasBlockEntity()) {
            BlockEntity be = server.getBlockEntity(pos);
            // Skip containers and any BE with nontrivial data.
            if (be != null && !isSafeBlockEntity(be)) return false;
        }
        if (isNearProtectedFixture(server, pos)) return false;
        return true;
    }

    private static boolean isSafeBlockEntity(BlockEntity be) {
        // Beds are already excluded above via the tag check, but the BE branch is reachable if
        // some other mod's stored-data block-entity slips through. Be conservative: reject.
        if (be instanceof BedBlockEntity) return false;
        return false;
    }

    private static boolean isNearProtectedFixture(ServerLevel server, BlockPos pos) {
        int r = DTConfig.SEEP_BED_PROTECT_RADIUS.get();
        int r2 = r * r;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r2) continue;
                    BlockPos p = pos.offset(dx, dy, dz);
                    BlockState s = server.getBlockState(p);
                    if (s.is(BlockTags.BEDS) || s.is(Blocks.RESPAWN_ANCHOR)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Crafted-block hint. Not a definitive check; the seep prefers matches when found but falls
     * back to natural terrain rather than doing nothing. This is intentionally loose so tuning
     * the block palette is a matter of block tags rather than code changes.
     */
    private static boolean isCraftedPreference(BlockState state) {
        return state.is(BlockTags.PLANKS)
                || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_STAIRS)
                || state.is(BlockTags.WOOL)
                || state.is(BlockTags.SLABS)
                || state.is(BlockTags.STAIRS)
                || state.is(BlockTags.WALLS)
                || state.is(Blocks.SMOOTH_STONE)
                || state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.MOSSY_STONE_BRICKS);
    }

    /** The block is nudged, not taken: it reappears a step away, unsettling but intact. */
    private static boolean tryDisplace(ServerLevel server, BlockPos target, RandomSource rng) {
        BlockState state = server.getBlockState(target);
        for (int i = 0; i < 8; i++) {
            int dx = rng.nextInt(3) - 1;
            int dy = rng.nextInt(3) - 1;
            int dz = rng.nextInt(3) - 1;
            if (dx == 0 && dy == 0 && dz == 0) continue;
            BlockPos dest = target.offset(dx, dy, dz);
            if (!server.getBlockState(dest).isAir()) continue;
            if (!state.canSurvive(server, dest)) continue;
            server.removeBlock(target, false);
            server.setBlock(dest, state, 3);
            return true;
        }
        return false;
    }

    private static boolean tryGrowthFeed(ServerLevel server, BlockPos target, RandomSource rng) {
        BlockState taken = server.getBlockState(target);
        BlockPos growthPos = findOrCreateGrowth(server, target, rng);
        if (growthPos == null) return false;
        BlockEntity be = server.getBlockEntity(growthPos);
        if (!(be instanceof ShoalGrowthBlockEntity growth)) return false;
        if (!growth.absorb(taken)) return false;
        server.removeBlock(target, false);
        server.sendBlockUpdated(target, taken, server.getBlockState(target), 3);
        return true;
    }

    private static BlockPos findOrCreateGrowth(ServerLevel server, BlockPos near, RandomSource rng) {
        int radius = DTConfig.SEEP_GROWTH_SEARCH_RADIUS.get();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos open = null;
        BlockPos anyGrowth = null;
        int checked = 0;
        for (int dx = -radius; dx <= radius && open == null && checked < 64; dx += 2) {
            for (int dy = -radius; dy <= radius && open == null && checked < 64; dy += 2) {
                for (int dz = -radius; dz <= radius && open == null && checked < 64; dz += 2) {
                    cursor.setWithOffset(near, dx, dy, dz);
                    BlockState s = server.getBlockState(cursor);
                    if (!(s.getBlock() instanceof ShoalGrowthBlock)) continue;
                    if (anyGrowth == null) anyGrowth = cursor.immutable();
                    BlockEntity be = server.getBlockEntity(cursor);
                    if (be instanceof ShoalGrowthBlockEntity growth && !growth.isFull()) {
                        open = cursor.immutable();
                    }
                    checked++;
                }
            }
        }
        if (open != null) return open;

        // Every growth nearby is full: grow the existing spire taller instead of scattering
        // fresh piles around the area.
        if (anyGrowth != null) {
            BlockPos grown = extendSpire(server, anyGrowth);
            if (grown != null) return grown;
        }

        // No cluster anywhere near: seed a new pile adjacent to the taken block where there's
        // air. Prefer above the block for visual continuity with the seep motion.
        BlockPos[] candidates = new BlockPos[] {
                near.above(), near.north(), near.south(), near.east(), near.west(), near.below()
        };
        return placeGrowth(server, candidates);
    }

    private static BlockPos extendSpire(ServerLevel server, BlockPos base) {
        BlockPos top = base;
        for (int i = 0; i < 12
                && server.getBlockState(top.above()).getBlock() instanceof ShoalGrowthBlock; i++) {
            top = top.above();
        }
        return placeGrowth(server, new BlockPos[] {
                top.above(), base.north(), base.south(), base.east(), base.west()
        });
    }

    private static BlockPos placeGrowth(ServerLevel server, BlockPos[] candidates) {
        BlockState growth = com.confect1on.dynetech.block.DTBlocks.SHOAL_GROWTH.get().defaultBlockState();
        for (BlockPos c : candidates) {
            if (!server.getBlockState(c).isAir()) continue;
            if (server.setBlock(c, growth, 3)) return c;
        }
        return null;
    }

    private static boolean tryConsume(ServerLevel server, BlockPos target) {
        BlockState bloom = com.confect1on.dynetech.block.DTBlocks.SHOAL_BLOOM.get().defaultBlockState();
        return server.setBlock(target, bloom, 3);
    }

    private static void playFx(ServerLevel server, BlockPos at) {
        server.sendParticles(com.confect1on.dynetech.particle.DTParticles.SHOAL_MOTE.get(),
                at.getX() + 0.5D, at.getY() + 0.7D, at.getZ() + 0.5D,
                6, 0.35D, 0.35D, 0.35D, 0.005D);
        server.playSound(null, at, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.3F, 1.4F);
    }

}
