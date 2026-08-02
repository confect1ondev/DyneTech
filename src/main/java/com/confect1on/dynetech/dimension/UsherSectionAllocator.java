package com.confect1on.dynetech.dimension;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Allocates and builds one 32^3 hollow section per Usher inside
 * {@link UsherInterior#DIMENSION}, walled with invisible barrier blocks so the cell reads
 * as open void (the client draws a shader sphere far beyond the walls for the horizon),
 * and handed to {@link UsherCellDecorator} for one of its preset dioramas
 * (each contains an open flame: the only light, and the walk-in way to die out of the
 * cell). Sections lay out along the +X axis 512 blocks
 * apart, so section {@code n} centers at {@code (n * 512, 64, 0)} - trivial to inspect with
 * F3 and impossible for two sections to collide.
 *
 * <p>SavedData is attached to the pocket dim, not the overworld, so it lives and dies with
 * the pocket world. Indexes are monotonically increasing and never reused, even after a
 * Phase Disk collapse deallocates the mapping. Recycling would risk teleporting a player
 * into a section whose previous occupant's chunks are still loaded.
 */
public class UsherSectionAllocator extends SavedData {

    private static final String NAME = "dynetech_usher_sections";

    public static final int SECTION_SIZE = 32;
    public static final int SECTION_SPACING_X = 512;
    public static final int CENTER_Y = 64;
    public static final int CENTER_Z = 0;
    // Section n occupies [center - HALF, center + HALF - 1] on X/Y/Z (32 blocks wide).
    private static final int HALF = SECTION_SIZE / 2;

    private int nextIndex = 0;
    private final Map<UUID, Integer> byUsher = new HashMap<>();

    public static Factory<UsherSectionAllocator> factory() {
        return new Factory<>(UsherSectionAllocator::new, UsherSectionAllocator::load, null);
    }

    public static UsherSectionAllocator get(MinecraftServer server) {
        ServerLevel interior = server.getLevel(UsherInterior.DIMENSION);
        if (interior == null) {
            throw new IllegalStateException("Usher interior dimension is not loaded");
        }
        return interior.getDataStorage().computeIfAbsent(factory(), NAME);
    }

    /** Section index for {@code usherId}, or -1 if it has never earned one. */
    public int getSection(UUID usherId) {
        Integer i = byUsher.get(usherId);
        return i == null ? -1 : i;
    }

    /** Drops the UUID-to-section mapping. {@code nextIndex} is intentionally not rewound. */
    public void deallocate(UUID usherId) {
        if (byUsher.remove(usherId) != null) {
            setDirty();
        }
    }

    /**
     * Wipes the whole 32^3 section (walls and interior) to air. Vanilla region-file
     * compression takes an all-air chunk down to near-nothing on disk.
     */
    public static void demolish(ServerLevel interior, int index) {
        int cx = sectionCenterX(index);
        int minX = cx - HALF, maxX = cx + HALF - 1;
        int minY = floorY(), maxY = ceilingY();
        int minZ = CENTER_Z - HALF, maxZ = CENTER_Z + HALF - 1;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    m.set(x, y, z);
                    interior.setBlock(m, air, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /**
     * Assign this Usher a fresh section, build its box, and persist the mapping. Idempotent
     * per Usher UUID: subsequent calls return the pre-existing index without rebuilding.
     */
    public int allocateAndBuild(UUID usherId, MinecraftServer server) {
        Integer existing = byUsher.get(usherId);
        if (existing != null) return existing;

        int index = nextIndex++;
        byUsher.put(usherId, index);
        setDirty();

        ServerLevel interior = server.getLevel(UsherInterior.DIMENSION);
        if (interior != null) {
            buildBox(interior, index);
            setSectionForced(interior, index, true);
        }
        return index;
    }

    /**
     * Force-loads (or unforces) the chunks covering section {@code index}. Persists across
     * restarts. Without this, dormant mobs in a section with no players inside drop out of
     * spatial queries, which broke the Phase Disk release step.
     */
    public static void setSectionForced(ServerLevel interior, int index, boolean forced) {
        int cx = sectionCenterX(index);
        int minCX = (cx - HALF) >> 4;
        int maxCX = (cx + HALF - 1) >> 4;
        int minCZ = (CENTER_Z - HALF) >> 4;
        int maxCZ = (CENTER_Z + HALF - 1) >> 4;
        for (int chunkX = minCX; chunkX <= maxCX; chunkX++) {
            for (int chunkZ = minCZ; chunkZ <= maxCZ; chunkZ++) {
                interior.setChunkForced(chunkX, chunkZ, forced);
            }
        }
    }

    public static int sectionCenterX(int index) {
        return index * SECTION_SPACING_X;
    }

    /** World-space position a caught player should arrive at: on the floor, centered. */
    public static BlockPos spawnPos(int index) {
        return new BlockPos(sectionCenterX(index), floorY() + 1, CENTER_Z);
    }

    public static int floorY() {
        return CENTER_Y - HALF; // 48
    }

    public static int ceilingY() {
        return CENTER_Y + HALF - 1; // 79
    }

    /**
     * Fills the shell of a 32^3 barrier cube around the section center and clears the
     * interior to air. Runs once per Usher, so a synchronous setBlock loop is fine.
     */
    private static void buildBox(ServerLevel level, int index) {
        int cx = sectionCenterX(index);
        int minX = cx - HALF, maxX = cx + HALF - 1;
        int minY = floorY(), maxY = ceilingY();
        int minZ = CENTER_Z - HALF, maxZ = CENTER_Z + HALF - 1;

        BlockState wall = Blocks.BARRIER.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean shell = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                    m.set(x, y, z);
                    // UPDATE_CLIENTS only: skip neighbor notifications, they trigger nothing
                    // inside a sealed shell and just churn ticks during a 32k-block build.
                    level.setBlock(m, shell ? wall : air, Block.UPDATE_CLIENTS);
                }
            }
        }

        UsherCellDecorator.furnish(level, index);
    }

    // ---- persistence ----
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("NextIndex", nextIndex);
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Integer> e : byUsher.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Usher", e.getKey());
            entry.putInt("Section", e.getValue());
            list.add(entry);
        }
        tag.put("Assignments", list);
        return tag;
    }

    public static UsherSectionAllocator load(CompoundTag tag, HolderLookup.Provider registries) {
        UsherSectionAllocator alloc = new UsherSectionAllocator();
        alloc.nextIndex = tag.getInt("NextIndex");
        ListTag list = tag.getList("Assignments", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            alloc.byUsher.put(entry.getUUID("Usher"), entry.getInt("Section"));
        }
        return alloc;
    }
}
