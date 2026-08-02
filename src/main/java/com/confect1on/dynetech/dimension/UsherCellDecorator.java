package com.confect1on.dynetech.dimension;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Furnishes a freshly built Usher cell with one of a few preset dioramas, picked
 * deterministically from the section index. Every preset shares the same grammar: a ragged
 * island of ground set flush into the floor, one open flame that doubles as the only light
 * and the walk-in way to die out of the cell, and set dressing that raises more questions
 * than it answers. Ground pieces are diggable and sit flush in the barrier floor, so digging
 * one out opens a real hole into the void below so basically a second exit.
 */
public final class UsherCellDecorator {

    private UsherCellDecorator() {}

    public static void furnish(ServerLevel level, int index) {
        long seed = index * 0x9E3779B97F4A7C15L + 0xC0FFEEL;
        int cx = UsherSectionAllocator.sectionCenterX(index);
        int floor = UsherSectionAllocator.floorY();
        switch ((int) (hash01(seed * 31L + 7L) * 3.0)) {
            case 0 -> campsite(level, seed, cx, floor);
            case 1 -> grave(level, seed, cx, floor);
            default -> shrine(level, seed, cx, floor);
        }
    }

    // ------------------------------------------------------------------
    //  Preset 0: the campsite. Somebody lived here. The fire is still lit.
    // ------------------------------------------------------------------

    private static void campsite(ServerLevel level, long seed, int cx, int floor) {
        int y = floor + 1;
        raggedPatch(level, seed, cx, floor, 6.0, 5.4, 4.2, roll ->
                roll < 0.60 ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : roll < 0.85 ? Blocks.COARSE_DIRT.defaultBlockState()
                        : Blocks.PODZOL.defaultBlockState());

        // Campfire clearing in front of the tent.
        set(level, cx + 3, floor, 0, Blocks.GRASS_BLOCK.defaultBlockState());
        set(level, cx + 3, y, 0, Blocks.CAMPFIRE.defaultBlockState());
        set(level, cx + 2, floor, 2, Blocks.GRASS_BLOCK.defaultBlockState());
        set(level, cx + 2, y, 2, Blocks.SPRUCE_LOG.defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        set(level, cx + 2, floor, -2, Blocks.COARSE_DIRT.defaultBlockState());
        set(level, cx + 2, y, -2, Blocks.DEAD_BUSH.defaultBlockState());

        // Boulder half-sunk at the patch edge.
        set(level, cx + 8, floor, -4, Blocks.COARSE_DIRT.defaultBlockState());
        set(level, cx + 9, floor, -4, Blocks.COARSE_DIRT.defaultBlockState());
        set(level, cx + 8, y, -4, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
        set(level, cx + 9, y, -4, Blocks.COBBLESTONE.defaultBlockState());
        set(level, cx + 8, y, -5, Blocks.COBBLESTONE.defaultBlockState());
        set(level, cx + 8, y + 1, -4, Blocks.STONE.defaultBlockState());

        // A-frame wool tent, five deep, tall enough to walk into. Opening faces the fire.
        BlockState white = Blocks.WHITE_WOOL.defaultBlockState();
        BlockState gray = Blocks.LIGHT_GRAY_WOOL.defaultBlockState();
        for (int dx = 6; dx <= 10; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                set(level, cx + dx, floor, dz, Blocks.DIRT.defaultBlockState());
            }
            for (int dz = -2; dz <= 2; dz += 4) {
                set(level, cx + dx, y, dz, wool(seed, dx, dz, white, gray));
            }
            for (int dz = -1; dz <= 1; dz += 2) {
                set(level, cx + dx, y + 1, dz, wool(seed, dx * 3, dz, white, gray));
            }
            set(level, cx + dx, y + 2, 0, wool(seed, dx * 7, 5, white, gray));
        }
        for (int dz = -1; dz <= 1; dz++) {
            set(level, cx + 10, y, dz, wool(seed, 10 + dz, 9, white, gray));
        }
        set(level, cx + 10, y + 1, 0, wool(seed, 47, 9, white, gray));

        // The bed nobody gets to use (bed_works is off, so trying detonates it).
        BlockState bed = Blocks.WHITE_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.EAST);
        set(level, cx + 7, y, 0, bed.setValue(BedBlock.PART, BedPart.FOOT));
        set(level, cx + 8, y, 0, bed.setValue(BedBlock.PART, BedPart.HEAD));
        set(level, cx + 9, y, 1, Blocks.BARREL.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.UP));
    }

    private static BlockState wool(long seed, int a, int b, BlockState white, BlockState gray) {
        return hash01(seed + a * 53L + b * 811L) < 0.4 ? gray : white;
    }

    // ------------------------------------------------------------------
    //  Preset 1: the grave. A dead tree, a swing, and someone under the mound.
    // ------------------------------------------------------------------

    private static void grave(ServerLevel level, long seed, int cx, int floor) {
        int y = floor + 1;
        raggedPatch(level, seed, cx, floor, 6.0, 4.6, 3.8, roll ->
                roll < 0.50 ? Blocks.COARSE_DIRT.defaultBlockState()
                        : roll < 0.78 ? Blocks.PODZOL.defaultBlockState()
                        : Blocks.ROOTED_DIRT.defaultBlockState());

        // Dead tree with one long branch.
        set(level, cx + 8, floor, 1, Blocks.ROOTED_DIRT.defaultBlockState());
        BlockState trunk = Blocks.SPRUCE_LOG.defaultBlockState();
        for (int dy = 0; dy < 4; dy++) {
            set(level, cx + 8, y + dy, 1, trunk);
        }
        BlockState branch = trunk.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        set(level, cx + 8, y + 3, 0, branch);
        set(level, cx + 8, y + 3, -1, branch);
        set(level, cx + 8, y + 3, -2, branch);

        // Swing hanging from the branch tip, seat swaying just off the ground.
        set(level, cx + 8, y + 2, -2, Blocks.CHAIN.defaultBlockState());
        set(level, cx + 8, y + 1, -2, Blocks.OAK_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.TOP));
        // Soul lantern under the branch - the cold light the swing gets to sway in.
        set(level, cx + 8, y + 2, -1, Blocks.SOUL_LANTERN.defaultBlockState()
                .setValue(LanternBlock.HANGING, true));

        // The mound, its rose, and a leaning headstone.
        set(level, cx + 4, floor, -1, Blocks.COARSE_DIRT.defaultBlockState());
        set(level, cx + 4, y, -1, Blocks.COARSE_DIRT.defaultBlockState());
        set(level, cx + 4, y + 1, -1, Blocks.WITHER_ROSE.defaultBlockState());
        set(level, cx + 3, floor, -1, Blocks.COARSE_DIRT.defaultBlockState());
        set(level, cx + 3, y, -1, Blocks.MOSSY_STONE_BRICK_WALL.defaultBlockState());

        // Vigil fire beside the grave.
        set(level, cx + 5, floor, 2, Blocks.COARSE_DIRT.defaultBlockState());
        set(level, cx + 5, y, 2, Blocks.SOUL_CAMPFIRE.defaultBlockState());

        set(level, cx + 6, floor, -3, Blocks.COARSE_DIRT.defaultBlockState());
        set(level, cx + 6, y, -3, Blocks.DEAD_BUSH.defaultBlockState());
        set(level, cx + 7, floor, 3, Blocks.COARSE_DIRT.defaultBlockState());
        set(level, cx + 7, y, 3, Blocks.DEAD_BUSH.defaultBlockState());
    }

    // ------------------------------------------------------------------
    //  Preset 2: the shrine. Ruined paving, broken pillars, a fire on the altar.
    // ------------------------------------------------------------------

    private static void shrine(ServerLevel level, long seed, int cx, int floor) {
        int y = floor + 1;
        raggedPatch(level, seed, cx, floor, 6.0, 4.8, 4.4, roll ->
                roll < 0.30 ? Blocks.STONE_BRICKS.defaultBlockState()
                        : roll < 0.55 ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState()
                        : roll < 0.75 ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState()
                        : Blocks.COBBLED_DEEPSLATE.defaultBlockState());

        // Central altar with the flame on top; dying here means climbing onto it.
        set(level, cx + 6, floor, 0, Blocks.COBBLED_DEEPSLATE.defaultBlockState());
        set(level, cx + 6, y, 0, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
        set(level, cx + 6, y + 1, 0, Blocks.SOUL_CAMPFIRE.defaultBlockState());

        // Broken colonnade, no two stubs the same height.
        int[][] pillars = { {3, -3}, {3, 3}, {9, -3}, {9, 3} };
        for (int i = 0; i < pillars.length; i++) {
            int px = pillars[i][0], pz = pillars[i][1];
            set(level, cx + px, floor, pz, Blocks.COBBLED_DEEPSLATE.defaultBlockState());
            int height = 1 + (int) (hash01(seed + i * 6151L) * 3.0);
            for (int dy = 0; dy < height; dy++) {
                set(level, cx + px, y + dy, pz,
                        hash01(seed + i * 6151L + dy * 389L) < 0.5
                                ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState()
                                : Blocks.STONE_BRICKS.defaultBlockState());
            }
            if (height < 3 && hash01(seed + i * 6151L + 97L) < 0.6) {
                set(level, cx + px, y + height, pz,
                        Blocks.COBBLESTONE_WALL.defaultBlockState());
            }
        }

        // Congregation of skulls and candles facing the altar.
        set(level, cx + 5, y, 2, Blocks.SKELETON_SKULL.defaultBlockState()
                .setValue(BlockStateProperties.ROTATION_16,
                        (int) (hash01(seed + 5L) * 16.0)));
        set(level, cx + 4, y, -2, Blocks.SKELETON_SKULL.defaultBlockState()
                .setValue(BlockStateProperties.ROTATION_16,
                        (int) (hash01(seed + 11L) * 16.0)));
        set(level, cx + 7, y, -2, Blocks.CANDLE.defaultBlockState()
                .setValue(CandleBlock.CANDLES, 3).setValue(CandleBlock.LIT, true));
        set(level, cx + 8, y, 2, Blocks.CANDLE.defaultBlockState()
                .setValue(CandleBlock.CANDLES, 1).setValue(CandleBlock.LIT, true));
        set(level, cx + 5, y, -1, Blocks.CANDLE.defaultBlockState()
                .setValue(CandleBlock.CANDLES, 2).setValue(CandleBlock.LIT, true));
    }

    // ------------------------------------------------------------------
    //  Shared ground stamping
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface GroundPicker {
        BlockState pick(double roll);
    }

    /**
     * Stamps a ragged ellipse of ground flush into the floor. The silhouette is torn by
     * per-angle noise so edges read as ripped rather than stamped, stray clumps land just past
     * the rim, and the occasional cell is left as bare barrier - it looks torn through into
     * the void but stays sealed. Structures overwrite their own footprints afterwards, so
     * pits never undermine the set pieces.
     */
    private static void raggedPatch(ServerLevel level, long seed, int cx, int floor,
                                    double centerDx, double rx, double rz,
                                    GroundPicker ground) {
        int minDx = (int) Math.floor(centerDx - rx) - 2;
        int maxDx = (int) Math.ceil(centerDx + rx) + 2;
        int maxDz = (int) Math.ceil(rz) + 2;
        int sectors = 11;
        for (int dx = minDx; dx <= maxDx; dx++) {
            for (int dz = -maxDz; dz <= maxDz; dz++) {
                double ex = (dx - centerDx) / rx;
                double ez = dz / rz;
                double edge = ex * ex + ez * ez;

                // Interpolated per-angle rip depth: long tears instead of per-block fuzz.
                double ang = Math.atan2(dz, dx - centerDx) / (Math.PI * 2.0) + 0.5;
                double s = ang * sectors;
                int s0 = ((int) Math.floor(s)) % sectors;
                int s1 = (s0 + 1) % sectors;
                float frac = (float) (s - Math.floor(s));
                double rip = Mth.lerp(frac,
                        (float) hash01(seed + s0 * 197L),
                        (float) hash01(seed + s1 * 197L));
                double threshold = 1.0 - rip * 0.55
                        + (hash01(seed + dx * 31L + dz * 131L) - 0.5) * 0.18;

                if (edge > threshold) {
                    // Stray clumps torn off the rim.
                    if (edge < threshold + 0.55
                            && hash01(seed + dx * 641L + dz * 269L) < 0.16) {
                        set(level, cx + dx, floor, dz,
                                ground.pick(hash01(seed + dx * 977L + dz * 389L)));
                    }
                    continue;
                }
                // Pits: the ground looks torn straight through into the void below, but the
                // barrier keeps the box sealed. Walking over one reads as stepping on nothing.
                if (edge > 0.3 && hash01(seed + dx * 421L + dz * 733L) < 0.05) {
                    set(level, cx + dx, floor, dz, Blocks.BARRIER.defaultBlockState());
                    continue;
                }
                set(level, cx + dx, floor, dz,
                        ground.pick(hash01(seed + dx * 977L + dz * 389L)));
            }
        }
    }

    private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_CLIENTS);
    }

    private static double hash01(long v) {
        long z = v + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }
}
