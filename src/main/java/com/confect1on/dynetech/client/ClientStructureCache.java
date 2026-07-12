package com.confect1on.dynetech.client;

import net.neoforged.neoforge.network.PacketDistributor;
import com.confect1on.dynetech.network.DTPayloads;
import com.confect1on.dynetech.storage.StructureBlob;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Client-side cache of shrunken structure block data, populated on request from the server.
 * The item stack itself only holds the UUID — heavy data is streamed in when needed for rendering.
 */
public final class ClientStructureCache {

    private static final Map<UUID, StructureBlob> CACHE = new HashMap<>();
    private static final Set<UUID> REQUESTED = new HashSet<>();

    private ClientStructureCache() {}

    public static StructureBlob get(UUID id) {
        return CACHE.get(id);
    }

    public static void put(UUID id, StructureBlob blob) {
        CACHE.put(id, blob);
        REQUESTED.remove(id);
    }

    public static void requestIfMissing(UUID id) {
        if (CACHE.containsKey(id) || !REQUESTED.add(id)) return;
        PacketDistributor.sendToServer(new DTPayloads.RequestStructure(id));
    }

    public static void clear() {
        CACHE.clear();
        REQUESTED.clear();
    }
}
