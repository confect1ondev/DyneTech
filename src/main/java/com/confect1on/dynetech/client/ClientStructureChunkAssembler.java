package com.confect1on.dynetech.client;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import com.confect1on.dynetech.network.DTPayloads;
import com.confect1on.dynetech.storage.StructureBlob;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reassembles multi-packet structure blobs streamed by the server. Chunks are indexed by
 * {@code seq} and delivered in TCP order; the assembler concatenates on completion, decodes the
 * compressed NBT, and hands the resulting {@link StructureBlob} to {@link ClientStructureCache}.
 * If a request is retried mid-flight and total shifts, the buffer is reset.
 */
public final class ClientStructureChunkAssembler {

    private static final Map<UUID, byte[][]> BUFFERS = new HashMap<>();
    private static final Map<UUID, Integer> RECEIVED = new HashMap<>();

    private ClientStructureChunkAssembler() {}

    public static void accept(UUID id, int seq, int total, byte[] chunk, HolderLookup.Provider registries) {
        if (total <= 0 || seq < 0 || seq >= total) return;
        byte[][] buf = BUFFERS.get(id);
        if (buf == null || buf.length != total) {
            buf = new byte[total][];
            BUFFERS.put(id, buf);
            RECEIVED.put(id, 0);
        }
        if (buf[seq] != null) return; // duplicate — ignore
        buf[seq] = chunk;
        int received = RECEIVED.merge(id, 1, Integer::sum);
        if (received < total) return;

        int totalLen = 0;
        for (byte[] c : buf) totalLen += c.length;
        byte[] combined = new byte[totalLen];
        int off = 0;
        for (byte[] c : buf) {
            System.arraycopy(c, 0, combined, off, c.length);
            off += c.length;
        }
        BUFFERS.remove(id);
        RECEIVED.remove(id);

        try {
            CompoundTag tag = DTPayloads.decodeCompressedNbt(combined);
            StructureBlob blob = StructureBlob.load(tag, registries);
            ClientStructureCache.put(id, blob);
        } catch (IOException ignored) {
            // Corrupt payload — leave cache empty; client will re-request on next render.
        }
    }

    public static void clear() {
        BUFFERS.clear();
        RECEIVED.clear();
    }
}
