package com.confect1on.dynetech.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import com.confect1on.dynetech.config.DTConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A captured cuboid of block states + block-entity NBT.
 * Serialized inside {@link ShrunkenStructureStorage} SavedData; never stored on items directly.
 */
public final class StructureBlob {

    private final Vec3i size;
    private final List<BlockState> palette;
    private final int[] indices;
    private final Map<BlockPos, CompoundTag> blockEntities;

    public StructureBlob(Vec3i size, List<BlockState> palette, int[] indices, Map<BlockPos, CompoundTag> blockEntities) {
        this.size = size;
        this.palette = palette;
        this.indices = indices;
        this.blockEntities = blockEntities;
    }

    public Vec3i size() { return size; }
    public boolean isEmpty() { return palette.stream().allMatch(BlockState::isAir); }

    public BlockState getBlockState(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0
                || x >= size.getX() || y >= size.getY() || z >= size.getZ()) {
            return Blocks.AIR.defaultBlockState();
        }
        return palette.get(indices[indexOf(x, y, z)]);
    }

    private int indexOf(int x, int y, int z) {
        return y * size.getX() * size.getZ() + z * size.getX() + x;
    }

    /**
     * Scans the region for container block-entities holding shrunken items (structures or
     * entities), including nested shulker box / bundle contents. Called before capture — nesting
     * is refused wholesale.
     */
    public static boolean containsShrunkenItems(ServerLevel level, BlockPos min, BlockPos max) {
        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                for (int x = min.getX(); x <= max.getX(); x++) {
                    BlockEntity be = level.getBlockEntity(new BlockPos(x, y, z));
                    if (!(be instanceof Container container)) continue;
                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        if (ShrunkenItemDetector.isShrunken(container.getItem(slot))) return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Serializes and counts bytes without allocating the full byte[]. Used to enforce the
     * server-side blob size cap before we commit the blob to storage and clear the region.
     */
    public int computeSerializedSize(HolderLookup.Provider registries) {
        ByteCountingOutputStream counter = new ByteCountingOutputStream();
        try (DataOutputStream dos = new DataOutputStream(counter)) {
            net.minecraft.nbt.NbtIo.write(save(registries), dos);
        } catch (IOException e) {
            return Integer.MAX_VALUE;
        }
        return counter.count;
    }

    private static final class ByteCountingOutputStream extends OutputStream {
        int count = 0;
        @Override public void write(int b) { count++; }
        @Override public void write(byte[] b, int off, int len) { count += len; }
    }

    public static StructureBlob capture(ServerLevel level, BlockPos min, BlockPos max) {
        int sx = max.getX() - min.getX() + 1;
        int sy = max.getY() - min.getY() + 1;
        int sz = max.getZ() - min.getZ() + 1;

        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> paletteIndex = new HashMap<>();
        int[] indices = new int[sx * sy * sz];
        Map<BlockPos, CompoundTag> beTags = new HashMap<>();

        HolderLookup.Provider registries = level.registryAccess();
        Set<Block> blacklist = DTConfig.resolveBlacklist();
        BlockState air = Blocks.AIR.defaultBlockState();

        int i = 0;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockPos worldPos = min.offset(x, y, z);
                    BlockState state = level.getBlockState(worldPos);
                    boolean blacklisted = blacklist.contains(state.getBlock());
                    if (blacklisted) {
                        // Record as a hole in the blob. The block stays in the world (see clearRegion) and won't reappear on paste.
                        state = air;
                    }
                    BlockState paletteState = state;
                    int idx = paletteIndex.computeIfAbsent(paletteState, s -> {
                        palette.add(s);
                        return palette.size() - 1;
                    });
                    indices[i++] = idx;
                    if (!blacklisted) {
                        BlockEntity be = level.getBlockEntity(worldPos);
                        if (be != null) {
                            CompoundTag beTag = be.saveWithoutMetadata(registries);
                            beTags.put(new BlockPos(x, y, z), beTag);
                        }
                    }
                }
            }
        }
        return new StructureBlob(new Vec3i(sx, sy, sz), palette, indices, beTags);
    }

    public static void clearRegion(ServerLevel level, BlockPos min, BlockPos max, Player breaker) {
        Set<Block> blacklist = DTConfig.resolveBlacklist();
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (blacklist.contains(state.getBlock())) continue;
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be != null) be.setRemoved();
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    public void paste(ServerLevel level, BlockPos origin) {
        HolderLookup.Provider registries = level.registryAccess();
        Set<Block> blacklist = DTConfig.resolveBlacklist();
        int sx = size.getX(), sy = size.getY(), sz = size.getZ();
        int i = 0;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockState structureState = palette.get(indices[i++]);
                    BlockPos worldPos = origin.offset(x, y, z);
                    CompoundTag beTag = blockEntities.get(new BlockPos(x, y, z));
                    BlockState existingState = level.getBlockState(worldPos);

                    // All drops below spawn off the TOP face
                    if (!existingState.isAir() && blacklist.contains(existingState.getBlock())) {
                        // Blacklisted world block: paste is refused here. Give the structure's own block back as an item so it isn't lost, and dump any container contents it was carrying (chest inventories, hopper items, furnace fuel, etc.).
                        if (!structureState.isAir()) {
                            Block.popResourceFromFace(level, worldPos, Direction.UP, new ItemStack(structureState.getBlock()));
                            if (beTag != null) {
                                dropCapturedContainerContents(level, worldPos, structureState, beTag, registries);
                            }
                        }
                        continue;
                    }

                    if (!existingState.isAir()) {
                        // Non-blacklisted world block getting replaced. Drop the block item first, then dump any live container contents and clear them so the impending setBlock doesn't also drop them via onRemove (double drop).
                        Block.popResourceFromFace(level, worldPos, Direction.UP, new ItemStack(existingState.getBlock()));
                        BlockEntity existingBE = level.getBlockEntity(worldPos);
                        if (existingBE instanceof Container container) {
                            Containers.dropContents(level, worldPos.above(), container);
                            container.clearContent();
                        }
                    }

                    level.setBlock(worldPos, structureState, 3);
                    if (beTag != null) {
                        BlockEntity be = level.getBlockEntity(worldPos);
                        if (be != null) {
                            be.loadWithComponents(beTag, registries);
                            be.setChanged();
                            // setBlock's flag-2 sends only the block state; the BE's init update packet was queued before loadWithComponents ran,
                            // so clients would see a fresh (default) shrinker. Force another BE sync now that its selection is populated.
                            level.sendBlockUpdated(worldPos, structureState, structureState, 3);
                        }
                    }
                }
            }
        }
    }

    /**
     * Drops container contents captured with a structure block whose paste was refused
     * (target world block is blacklisted). Synthesizes a throwaway BE from the captured NBT
     * so any {@link Container} (chest, hopper, dispenser, barrel, brewing stand, furnace)
     * can be enumerated by {@link Containers#dropContents}.
     */
    private static void dropCapturedContainerContents(ServerLevel level, BlockPos worldPos,
                                                      BlockState structureState, CompoundTag beTag,
                                                      HolderLookup.Provider registries) {
        if (!(structureState.getBlock() instanceof EntityBlock entityBlock)) return;
        BlockEntity temp = entityBlock.newBlockEntity(worldPos, structureState);
        if (temp == null) return;
        temp.setLevel(level);
        temp.loadWithComponents(beTag, registries);
        if (temp instanceof Container container) {
            // above the refused block bc spawning at worldPos would embed the items in it.
            Containers.dropContents(level, worldPos.above(), container);
        }
    }

    // ---- NBT ----
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("Size", new int[]{size.getX(), size.getY(), size.getZ()});
        ListTag paletteList = new ListTag();
        for (BlockState state : palette) {
            paletteList.add(NbtUtils.writeBlockState(state));
        }
        tag.put("Palette", paletteList);
        tag.putIntArray("Blocks", indices);
        ListTag beList = new ListTag();
        for (Map.Entry<BlockPos, CompoundTag> entry : blockEntities.entrySet()) {
            CompoundTag e = new CompoundTag();
            BlockPos p = entry.getKey();
            e.putIntArray("Pos", new int[]{p.getX(), p.getY(), p.getZ()});
            e.put("Data", entry.getValue());
            beList.add(e);
        }
        tag.put("BlockEntities", beList);
        return tag;
    }

    public static StructureBlob load(CompoundTag tag, HolderLookup.Provider registries) {
        int[] s = tag.getIntArray("Size");
        Vec3i size = new Vec3i(s[0], s[1], s[2]);
        ListTag paletteList = tag.getList("Palette", Tag.TAG_COMPOUND);
        var blockRegistry = registries.lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK);
        List<BlockState> palette = new ArrayList<>(paletteList.size());
        for (int i = 0; i < paletteList.size(); i++) {
            palette.add(NbtUtils.readBlockState(blockRegistry, paletteList.getCompound(i)));
        }
        int[] indices = tag.getIntArray("Blocks");
        Map<BlockPos, CompoundTag> beTags = new HashMap<>();
        ListTag beList = tag.getList("BlockEntities", Tag.TAG_COMPOUND);
        for (int i = 0; i < beList.size(); i++) {
            CompoundTag e = beList.getCompound(i);
            int[] p = e.getIntArray("Pos");
            beTags.put(new BlockPos(p[0], p[1], p[2]), e.getCompound("Data"));
        }
        return new StructureBlob(size, palette, indices, beTags);
    }
}
