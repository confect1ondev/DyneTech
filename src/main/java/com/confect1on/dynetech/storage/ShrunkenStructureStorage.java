package com.confect1on.dynetech.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Global (overworld-scoped) server-side storage of every captured StructureBlob keyed by UUID.
 * Item stacks only carry the UUID via DataComponent — the payload lives here so items stay tiny
 * and identical stacks can share (or NOT share) data cleanly.
 *
 * <p>Also tracks a "last seen" epoch-second timestamp per UUID so orphaned blobs (item deleted in
 * lava, /cleared away, despawned) can be swept by a periodic GC pass. Timestamps refresh whenever
 * the blob is fetched (client request, regrow paste, tick-time inspection) or explicitly touched
 * by the storage maintenance handlers on player login / chunk load / entity load.
 */
public class ShrunkenStructureStorage extends SavedData {

    private static final String NAME = "dynetech_shrunken_structures";

    private final Map<UUID, StructureBlob> byId = new HashMap<>();
    private final Map<UUID, Long> lastSeen = new HashMap<>();

    public static Factory<ShrunkenStructureStorage> factory() {
        return new Factory<>(ShrunkenStructureStorage::new, ShrunkenStructureStorage::load, null);
    }

    public static ShrunkenStructureStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), NAME);
    }

    public UUID store(StructureBlob blob) {
        UUID id = UUID.randomUUID();
        byId.put(id, blob);
        lastSeen.put(id, nowSec());
        setDirty();
        return id;
    }

    @Nullable
    public StructureBlob get(UUID id) {
        StructureBlob blob = byId.get(id);
        if (blob != null) touch(id);
        return blob;
    }

    @Nullable
    public StructureBlob remove(UUID id) {
        StructureBlob removed = byId.remove(id);
        lastSeen.remove(id);
        if (removed != null) setDirty();
        return removed;
    }

    /** Refreshes lastSeen for an ID we know is still referenced. No-op if the ID isn't stored. */
    public void touch(UUID id) {
        if (byId.containsKey(id)) {
            lastSeen.put(id, nowSec());
            setDirty();
        }
    }

    public void touchAll(Iterable<UUID> ids) {
        long now = nowSec();
        boolean any = false;
        for (UUID id : ids) {
            if (byId.containsKey(id)) {
                lastSeen.put(id, now);
                any = true;
            }
        }
        if (any) setDirty();
    }

    /**
     * Removes any blob whose lastSeen is older than {@code ttlSeconds}. Missing timestamps are
     * treated as "just seen" — a defensive default so a load bug can't nuke persistent blobs.
     * @return number of entries purged.
     */
    public int sweep(long ttlSeconds) {
        return sweep(ttlSeconds, nowSec());
    }

    /** Same as {@link #sweep(long)} but with an injectable clock. Useful for tests. */
    public int sweep(long ttlSeconds, long nowSec) {
        long cutoff = nowSec - ttlSeconds;
        List<UUID> toRemove = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : lastSeen.entrySet()) {
            if (e.getValue() < cutoff) toRemove.add(e.getKey());
        }
        for (UUID id : toRemove) {
            byId.remove(id);
            lastSeen.remove(id);
        }
        if (!toRemove.isEmpty()) setDirty();
        return toRemove.size();
    }

    private static long nowSec() {
        return System.currentTimeMillis() / 1000L;
    }

    // ---- persistence ----
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        long now = nowSec();
        for (Map.Entry<UUID, StructureBlob> entry : byId.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putUUID("Id", entry.getKey());
            e.put("Blob", entry.getValue().save(registries));
            e.putLong("LastSeen", lastSeen.getOrDefault(entry.getKey(), now));
            list.add(e);
        }
        tag.put("Structures", list);
        return tag;
    }

    public static ShrunkenStructureStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        ShrunkenStructureStorage store = new ShrunkenStructureStorage();
        ListTag list = tag.getList("Structures", Tag.TAG_COMPOUND);
        long now = nowSec();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            UUID id = e.getUUID("Id");
            store.byId.put(id, StructureBlob.load(e.getCompound("Blob"), registries));
            // Pre-existing worlds saved before this migration have no LastSeen — bump to now so
            // the GC grace period starts fresh instead of purging them immediately.
            store.lastSeen.put(id, e.contains("LastSeen") ? e.getLong("LastSeen") : now);
        }
        return store;
    }
}
