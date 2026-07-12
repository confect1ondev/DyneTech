package com.confect1on.dynetech.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Global (overworld-scoped) server-side storage of every captured StructureBlob keyed by UUID.
 * Item stacks only carry the UUID via DataComponent — the payload lives here so items stay tiny
 * and identical stacks can share (or NOT share) data cleanly.
 */
public class ShrunkenStructureStorage extends SavedData {

    private static final String NAME = "dynetech_shrunken_structures";

    private final Map<UUID, StructureBlob> byId = new HashMap<>();

    public static Factory<ShrunkenStructureStorage> factory() {
        return new Factory<>(ShrunkenStructureStorage::new, ShrunkenStructureStorage::load, null);
    }

    public static ShrunkenStructureStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), NAME);
    }

    public UUID store(StructureBlob blob) {
        UUID id = UUID.randomUUID();
        byId.put(id, blob);
        setDirty();
        return id;
    }

    @Nullable
    public StructureBlob get(UUID id) {
        return byId.get(id);
    }

    @Nullable
    public StructureBlob remove(UUID id) {
        StructureBlob removed = byId.remove(id);
        if (removed != null) setDirty();
        return removed;
    }

    // ---- persistence ----
    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, StructureBlob> entry : byId.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putUUID("Id", entry.getKey());
            e.put("Blob", entry.getValue().save(registries));
            list.add(e);
        }
        tag.put("Structures", list);
        return tag;
    }

    public static ShrunkenStructureStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        ShrunkenStructureStorage store = new ShrunkenStructureStorage();
        ListTag list = tag.getList("Structures", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            store.byId.put(e.getUUID("Id"), StructureBlob.load(e.getCompound("Blob"), registries));
        }
        return store;
    }
}
