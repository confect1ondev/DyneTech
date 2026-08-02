package com.confect1on.dynetech.block;

import com.confect1on.dynetech.config.DTConfig;
import com.confect1on.dynetech.gene.DTAttachments;
import com.confect1on.dynetech.gene.ShoalHostState;
import com.confect1on.dynetech.particle.DTParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BedBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-carrier tick that spreads the infection through nearby blocks. Two outcomes:
 *
 * <ul>
 *   <li>{@code consume}: a world block is covered in place by Shoal Bloom, which remembers
 *   what it grew over. The block never moves; breaking the bloom uncovers the original and
 *   pays one Shoal Residue.</li>
 *   <li>{@code pyre}: the swarm conjures fresh bloom out of nothing and stacks it into
 *   biomass mounds, growing in eight-block bursts round-robin across a small number of paired
 *   mounds. Pyre blooms cover nothing, so breaking them just pays residue.</li>
 * </ul>
 *
 * <p>All hard rules (blacklist, protected radii around beds/respawn anchors, container guards,
 * per-day budget, lingering vs passing-through) live here so the perk itself stays a thin marker.
 */
public final class ShoalSeep {

    private static final int SCAN_ATTEMPTS = 24;

    // Pyre policy: each spire grows in eight-block bursts; up to two paired spires alternate,
    // then both spires keep extending in matching layers.
    private static final int SPIRE_BURST = 8;
    private static final int SPIRE_CAP = 2;

    // Speed scaling: nearby existing shoal blocks accelerate the seep. Ratio is per stride-2
    // sample hit (see countNearbyShoalBlocks). Floor keeps a heavily-clustered carrier from
    // stripping the area in a single second.
    private static final double SPEED_RATIO_PER_BLOCK = 0.15;
    private static final int SPEED_FLOOR_TICKS = 6;

    // Flinch: a player breaking a shoal block startles the biomass. All seep activity in that
    // dimension pauses briefly, the biomass around the wound stays visibly agitated for the
    // whole pause (see tickFlinch), and broken growth positions queue up for regrowth. Both
    // maps are transient by design; a shutdown (see clearTransient) js forgets old flinches
    // and unfilled spots.
    private static final long FLINCH_PAUSE_TICKS = 100L;
    private static final int FLINCH_AGITATION_RADIUS = 5;
    private static final int REGROW_CAP = 48;
    private static final Map<ResourceKey<Level>, Flinch> FLINCH = new HashMap<>();
    private static final Map<ResourceKey<Level>, ArrayDeque<BlockPos>> REGROW_QUEUE = new HashMap<>();

    private ShoalSeep() {}

    public static void tick(ServerLevel server, Player player) {
        if (player.isSpectator() || player.isCreative()) return;

        long now = server.getGameTime();
        Flinch flinch = FLINCH.get(server.dimension());
        if (flinch != null && now < flinch.until()) return;
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

        // Cheap early-out: even the fully-accelerated interval can't be below the floor, so a
        // player that fired last tick can never seep again this tick regardless of density.
        long elapsed = now - state.lastSeepTick();
        if (elapsed < SPEED_FLOOR_TICKS) return;
        int base = DTConfig.SEEP_INTERVAL_TICKS.get();
        // Past the unaccelerated interval, we always fire; only pay for the density scan when
        // we might be firing early because of a big cluster.
        if (elapsed < base) {
            int nearbyShoal = countNearbyShoalBlocks(server, player.blockPosition(),
                    DTConfig.SEEP_GROWTH_SEARCH_RADIUS.get());
            if (elapsed < effectiveInterval(nearbyShoal)) return;
        }
        if (isMovingFast(player)) return;

        RandomSource rng = server.random;
        BlockPos target = findConvertible(server, player, rng);
        if (target == null) {
            saveIfChanged(player, state, now, budgetUsed, bookedDay);
            return;
        }

        boolean converted;
        BlockPos fxPos = target;
        if (rng.nextDouble() < DTConfig.SEEP_REARRANGE_WEIGHT.get()) {
            BlockPos placed = findOrCreatePyre(server, target, rng);
            converted = placed != null;
            if (placed != null) fxPos = placed;
        } else {
            converted = tryConsume(server, target);
        }
        if (converted) {
            budgetUsed++;
            playFx(server, fxPos);
            spawnFlowTrail(server, player, fxPos);
        }

        saveIfChanged(player, state, now, budgetUsed, bookedDay);
    }

    /**
     * Called from the global break-event hook when a player breaks either shoal block. Pauses
     * the seep, plays the recoil, and (when {@code regrow} is set) remembers the spot so the
     * biomass fills it back in later.
     */
    public static void notifyBroken(ServerLevel server, BlockPos pos, boolean regrow) {
        FLINCH.put(server.dimension(),
                new Flinch(server.getGameTime() + FLINCH_PAUSE_TICKS, pos.immutable()));
        if (regrow) {
            ArrayDeque<BlockPos> queue = REGROW_QUEUE.computeIfAbsent(
                    server.dimension(), k -> new ArrayDeque<>());
            if (queue.size() < REGROW_CAP) queue.addLast(pos.immutable());
        }
        // Immediate wince: a violent burst at the wound, a deep groan, and a sharp high cry on
        // top. The sustained convulsion runs from tickFlinch for the rest of the pause.
        server.sendParticles(DTParticles.SHOAL_MOTE.get(),
                pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                24, 0.4D, 0.4D, 0.4D, 0.12D);
        server.playSound(null, pos, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_RESONATE,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 0.35F);
        server.playSound(null, pos, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.9F, 1.9F);
    }

    /**
     * Phase Disk impact: phases out every shoal block within {@code radius} of the landing
     * point. Covered blooms swap back to whatever they grew over, pyre blooms and legacy
     * growth blocks simply vanish (growths still return their stored contents first), and
     * queued regrowth in the area is forgotten. No residue and no flinch; the disk removes
     * the infection cleanly rather than wounding it.
     */
    public static boolean purge(ServerLevel server, BlockPos center, int radius) {
        List<BlockPos> hits = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.setWithOffset(center, dx, dy, dz);
                    Block block = server.getBlockState(cursor).getBlock();
                    if (block instanceof ShoalBloomBlock || block instanceof ShoalGrowthBlock) {
                        hits.add(cursor.immutable());
                    }
                }
            }
        }
        if (hits.isEmpty()) return false;

        for (BlockPos pos : hits) {
            BlockState replacement = Blocks.AIR.defaultBlockState();
            BlockEntity be = server.getBlockEntity(pos);
            if (be instanceof com.confect1on.dynetech.blockentity.ShoalBloomBlockEntity bloom
                    && bloom.covered() != null) {
                replacement = bloom.covered();
            } else if (be instanceof com.confect1on.dynetech.blockentity.ShoalGrowthBlockEntity growth) {
                growth.restoreAll(server, pos);
            }
            server.setBlock(pos, replacement, 3);
            server.sendParticles(DTParticles.SHOAL_MOTE.get(),
                    pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                    5, 0.3D, 0.3D, 0.3D, 0.03D);
        }
        ArrayDeque<BlockPos> queue = REGROW_QUEUE.get(server.dimension());
        if (queue != null) queue.removeIf(p -> p.closerThan(center, radius + 0.5D));

        server.playSound(null, center, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_RESONATE,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.9F, 1.6F);
        server.playSound(null, center, net.minecraft.sounds.SoundEvents.BEACON_DEACTIVATE,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.6F, 1.3F);
        return true;
    }

    /**
     * Runs every server tick per level while a flinch is active. Every few ticks the shoal
     * blocks around the wound spit motes off their surfaces away from it, with unsettled
     * resonates underneath, so the mass visibly convulses for the whole five-second pause.
     */
    public static void tickFlinch(ServerLevel server) {
        Flinch flinch = FLINCH.get(server.dimension());
        if (flinch == null) return;
        long now = server.getGameTime();
        if (now >= flinch.until()) {
            FLINCH.remove(server.dimension());
            return;
        }
        if ((now & 1L) != 0L) return;

        BlockPos center = flinch.center();
        List<BlockPos> reacting = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int r = FLINCH_AGITATION_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    cursor.setWithOffset(center, dx, dy, dz);
                    Block block = server.getBlockState(cursor).getBlock();
                    if (block instanceof ShoalGrowthBlock || block instanceof ShoalBloomBlock) {
                        reacting.add(cursor.immutable());
                    }
                }
            }
        }
        if (reacting.isEmpty()) return;

        RandomSource rng = server.random;
        double cx = center.getX() + 0.5D;
        double cy = center.getY() + 0.5D;
        double cz = center.getZ() + 0.5D;
        int bursts = Math.min(14, Math.max(4, reacting.size()));
        for (int i = 0; i < bursts; i++) {
            BlockPos p = reacting.get(rng.nextInt(reacting.size()));
            double bx = p.getX() + 0.5D;
            double by = p.getY() + 0.5D;
            double bz = p.getZ() + 0.5D;
            double ax = bx - cx;
            double ay = by - cy;
            double az = bz - cz;
            double dist = Math.sqrt(ax * ax + ay * ay + az * az);
            if (dist < 0.01D) {
                ax = rng.nextDouble() - 0.5D;
                ay = rng.nextDouble() * 0.5D;
                az = rng.nextDouble() - 0.5D;
                dist = Math.sqrt(ax * ax + ay * ay + az * az);
            }
            double inv = 1.0D / dist;
            double nx = ax * inv;
            double ny = ay * inv;
            double nz = az * inv;
            // Spawn on the away-facing surface, not the block center, so nothing hides inside
            // the opaque block. Jitter keeps repeated bursts from stacking on one point.
            double sx = bx + nx * 0.65D + (rng.nextDouble() - 0.5D) * 0.4D;
            double sy = by + ny * 0.65D + (rng.nextDouble() - 0.5D) * 0.4D;
            double sz = bz + nz * 0.65D + (rng.nextDouble() - 0.5D) * 0.4D;
            // One mote thrown hard away from the wound, one erupting upward: together the mass
            // reads as thrashing rather than politely shedding.
            server.sendParticles(DTParticles.SHOAL_MOTE.get(),
                    sx, sy, sz, 0,
                    nx * 0.28D, ny * 0.28D + 0.06D, nz * 0.28D, 1.0D);
            server.sendParticles(DTParticles.SHOAL_MOTE.get(),
                    bx + (rng.nextDouble() - 0.5D) * 0.8D, by + 0.7D,
                    bz + (rng.nextDouble() - 0.5D) * 0.8D, 0,
                    (rng.nextDouble() - 0.5D) * 0.08D, 0.22D + rng.nextDouble() * 0.12D,
                    (rng.nextDouble() - 0.5D) * 0.08D, 1.0D);
        }
        // Distress calls: frantic high chimes over an uneasy low groan.
        if (rng.nextFloat() < 0.5F) {
            BlockPos p = reacting.get(rng.nextInt(reacting.size()));
            server.playSound(null, p, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                    net.minecraft.sounds.SoundSource.BLOCKS,
                    0.45F, 1.5F + rng.nextFloat() * 0.5F);
        }
        if (rng.nextFloat() < 0.3F) {
            BlockPos p = reacting.get(rng.nextInt(reacting.size()));
            server.playSound(null, p, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_RESONATE,
                    net.minecraft.sounds.SoundSource.BLOCKS,
                    0.4F, 0.35F + rng.nextFloat() * 0.15F);
        }
    }

    private record Flinch(long until, BlockPos center) {}

    /**
     * Server shutdown hook. Both maps are static and keyed by dimension, so without this a
     * singleplayer world switch would carry one world's flinch timers and regrow spots into
     * the next world under the same dimension key.
     */
    public static void clearTransient() {
        FLINCH.clear();
        REGROW_QUEUE.clear();
    }

    private static int effectiveInterval(int nearbyShoal) {
        int base = DTConfig.SEEP_INTERVAL_TICKS.get();
        if (nearbyShoal <= 0) return base;
        double scaled = base / (1.0 + nearbyShoal * SPEED_RATIO_PER_BLOCK);
        return (int) Math.max(SPEED_FLOOR_TICKS, Math.round(scaled));
    }

    private static int countNearbyShoalBlocks(ServerLevel server, BlockPos center, int radius) {
        int count = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int step = 2;
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dy = -radius; dy <= radius; dy += step) {
                for (int dz = -radius; dz <= radius; dz += step) {
                    cursor.setWithOffset(center, dx, dy, dz);
                    Block block = server.getBlockState(cursor).getBlock();
                    if (block instanceof ShoalGrowthBlock || block instanceof ShoalBloomBlock) {
                        count++;
                    }
                }
            }
        }
        return count;
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

    /**
     * Biomass-style pyre building. Scan for existing clusters (connected bloom blocks) in the
     * search radius, then conjure a fresh bloom onto one of them per the burst policy. New
     * blocks go onto a weighted-random neighbor of any member of the chosen cluster, with a
     * strong upward bias so the shape reads as a spike with organic sprawl and branches.
     */
    private static BlockPos findOrCreatePyre(ServerLevel server, BlockPos near, RandomSource rng) {
        // Healing comes first: refill spots the players carved out before growing anywhere new.
        BlockPos regrown = tryRegrow(server, near);
        if (regrown != null) return regrown;
        int radius = DTConfig.SEEP_GROWTH_SEARCH_RADIUS.get();
        List<Cluster> clusters = findNearbyClusters(server, near, radius);
        if (clusters.isEmpty()) {
            return seedNewCluster(server, near, rng);
        }
        clusters.sort(Comparator.comparingInt((Cluster c) -> c.size()).thenComparingInt(c -> c.base.hashCode()));

        // If any cluster is partway through a burst, finish that burst before touching another.
        Cluster toExtend = null;
        for (Cluster c : clusters) {
            if (c.size() % SPIRE_BURST != 0) {
                toExtend = c;
                break;
            }
        }
        if (toExtend == null) {
            // Every cluster sits on a clean burst boundary. Either open a new cluster (paired
            // biomass mound) or start the next 8-block burst on the smallest existing one.
            if (clusters.size() < SPIRE_CAP) {
                BlockPos seeded = seedNewCluster(server, near, rng);
                if (seeded != null) return seeded;
            }
            toExtend = clusters.get(0);
        }

        BlockPos placed = extendCluster(server, toExtend, rng);
        if (placed != null) return placed;

        // Cluster is completely walled in. Fall back to any placement near the anchor so the
        // seep still resolves.
        return placePyre(server, new BlockPos[] {
                near.above(), near.north(), near.south(), near.east(), near.west(), near.below()
        });
    }

    /**
     * Pop a queued regrow position if one is loaded, still air, and close enough to the carrier
     * that the flow trail won't look absurd. Far or unloaded entries stay queued for later;
     * entries someone filled with another block are dropped, the wound is closed.
     */
    private static BlockPos tryRegrow(ServerLevel server, BlockPos near) {
        ArrayDeque<BlockPos> queue = REGROW_QUEUE.get(server.dimension());
        if (queue == null || queue.isEmpty()) return null;
        int checks = Math.min(queue.size(), 8);
        for (int i = 0; i < checks; i++) {
            BlockPos pos = queue.pollFirst();
            if (pos == null) break;
            if (!pos.closerThan(near, 32.0D) || !server.isLoaded(pos)) {
                queue.addLast(pos);
                continue;
            }
            if (!server.getBlockState(pos).isAir()) continue;
            if (placePyre(server, new BlockPos[] { pos }) != null) return pos;
        }
        return null;
    }

    /**
     * Flood-fill the search volume: every group of 26-connected shoal blocks becomes one
     * cluster. Legacy growth blocks still count so old pyres keep growing. Base is the
     * lowest-Y member.
     */
    private static List<Cluster> findNearbyClusters(ServerLevel server, BlockPos near, int radius) {
        Set<BlockPos> all = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.setWithOffset(near, dx, dy, dz);
                    Block block = server.getBlockState(cursor).getBlock();
                    if (block instanceof ShoalBloomBlock || block instanceof ShoalGrowthBlock) {
                        all.add(cursor.immutable());
                    }
                }
            }
        }
        List<Cluster> clusters = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos start : all) {
            if (!visited.add(start)) continue;
            List<BlockPos> members = new ArrayList<>();
            BlockPos base = start;
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                BlockPos p = queue.pollFirst();
                members.add(p);
                if (p.getY() < base.getY()) base = p;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos n = p.offset(dx, dy, dz);
                            if (!all.contains(n) || !visited.add(n)) continue;
                            queue.add(n);
                        }
                    }
                }
            }
            clusters.add(new Cluster(base, members));
        }
        return clusters;
    }

    /**
     * Weighted-random extension: enumerate every air neighbor of every member, weight positions
     * by direction (strong up bias, some diagonal-up, mild horizontal, weak down), then pick one.
     * Duplicate candidates naturally add weight to positions adjacent to multiple members, so
     * concavities fill in faster than the tip flies away.
     */
    private static BlockPos extendCluster(ServerLevel server, Cluster cluster, RandomSource rng) {
        List<BlockPos> positions = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0.0D;
        for (BlockPos p : cluster.members) {
            total = considerNeighbor(server, p.above(), 6.0D, positions, weights, total);
            total = considerNeighbor(server, p.above().north(), 2.5D, positions, weights, total);
            total = considerNeighbor(server, p.above().south(), 2.5D, positions, weights, total);
            total = considerNeighbor(server, p.above().east(), 2.5D, positions, weights, total);
            total = considerNeighbor(server, p.above().west(), 2.5D, positions, weights, total);
            total = considerNeighbor(server, p.north(), 1.0D, positions, weights, total);
            total = considerNeighbor(server, p.south(), 1.0D, positions, weights, total);
            total = considerNeighbor(server, p.east(), 1.0D, positions, weights, total);
            total = considerNeighbor(server, p.west(), 1.0D, positions, weights, total);
            total = considerNeighbor(server, p.below(), 0.15D, positions, weights, total);
        }
        if (positions.isEmpty()) return null;
        double roll = rng.nextDouble() * total;
        double acc = 0.0D;
        for (int i = 0; i < positions.size(); i++) {
            acc += weights.get(i);
            if (roll <= acc) {
                BlockPos chosen = positions.get(i);
                BlockState bloom = DTBlocks.SHOAL_BLOOM.get().defaultBlockState();
                if (server.setBlock(chosen, bloom, 3)) return chosen;
                return null;
            }
        }
        return null;
    }

    private static double considerNeighbor(ServerLevel server, BlockPos pos, double weight,
                                           List<BlockPos> positions, List<Double> weights,
                                           double runningTotal) {
        if (!server.getBlockState(pos).isAir()) return runningTotal;
        positions.add(pos);
        weights.add(weight);
        return runningTotal + weight;
    }

    /**
     * Seed a fresh cluster near the taken block. Prefers an air position with a solid block
     * beneath so the mound has a floor to grow from; falls back to a wider scatter if no such
     * position exists.
     */
    private static BlockPos seedNewCluster(ServerLevel server, BlockPos near, RandomSource rng) {
        BlockPos[] preferred = new BlockPos[] {
                near.above(), near.north(), near.south(), near.east(), near.west()
        };
        for (BlockPos p : preferred) {
            if (!server.getBlockState(p).isAir()) continue;
            if (!server.getBlockState(p.below()).blocksMotion()) continue;
            if (placePyre(server, new BlockPos[] { p }) != null) return p;
        }
        for (int i = 0; i < 8; i++) {
            int dx = rng.nextInt(5) - 2;
            int dz = rng.nextInt(5) - 2;
            BlockPos p = near.offset(dx, 0, dz);
            if (!server.getBlockState(p).isAir()) continue;
            if (placePyre(server, new BlockPos[] { p }) != null) return p;
        }
        return null;
    }

    private static BlockPos placePyre(ServerLevel server, BlockPos[] candidates) {
        BlockState bloom = DTBlocks.SHOAL_BLOOM.get().defaultBlockState();
        for (BlockPos c : candidates) {
            if (!server.getBlockState(c).isAir()) continue;
            if (server.setBlock(c, bloom, 3)) return c;
        }
        return null;
    }

    private static boolean tryConsume(ServerLevel server, BlockPos target) {
        BlockState prior = server.getBlockState(target);
        BlockState bloom = DTBlocks.SHOAL_BLOOM.get().defaultBlockState();
        if (!server.setBlock(target, bloom, 3)) return false;
        if (server.getBlockEntity(target)
                instanceof com.confect1on.dynetech.blockentity.ShoalBloomBlockEntity be) {
            be.setCovered(prior);
        }
        return true;
    }

    private static void playFx(ServerLevel server, BlockPos at) {
        server.sendParticles(DTParticles.SHOAL_MOTE.get(),
                at.getX() + 0.5D, at.getY() + 0.7D, at.getZ() + 0.5D,
                6, 0.35D, 0.35D, 0.35D, 0.005D);
        server.playSound(null, at, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.3F, 1.4F);
    }

    /**
     * Directional mote trail from the carrier's chest to the newly-converted block. Rides
     * SHOAL_MOTE with a nonzero velocity vector so each mote drifts toward the target across
     * its lifetime.
     */
    private static void spawnFlowTrail(ServerLevel server, Player player, BlockPos target) {
        double srcX = player.getX();
        double srcY = player.getY() + player.getBbHeight() * 0.6D;
        double srcZ = player.getZ();
        double dstX = target.getX() + 0.5D;
        double dstY = target.getY() + 0.5D;
        double dstZ = target.getZ() + 0.5D;
        double dx = dstX - srcX;
        double dy = dstY - srcY;
        double dz = dstZ - srcZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.05D) return;
        double invDist = 1.0D / dist;
        double nx = dx * invDist;
        double ny = dy * invDist;
        double nz = dz * invDist;
        int steps = Math.max(2, (int) Math.min(6, Math.round(dist)));
        RandomSource rng = server.random;
        for (int i = 0; i < steps; i++) {
            double t = (i + 1.0D) / (steps + 1.0D);
            double px = srcX + dx * t + (rng.nextDouble() - 0.5D) * 0.18D;
            double py = srcY + dy * t + (rng.nextDouble() - 0.5D) * 0.18D;
            double pz = srcZ + dz * t + (rng.nextDouble() - 0.5D) * 0.18D;
            // count=0 sends velocity as the offsets * speed; magnitude is roughly blocks/tick.
            server.sendParticles(DTParticles.SHOAL_MOTE.get(),
                    px, py, pz, 0,
                    nx * 0.14D, ny * 0.14D, nz * 0.14D, 1.0D);
        }
    }

    private record Cluster(BlockPos base, List<BlockPos> members) {
        int size() { return members.size(); }
    }
}
