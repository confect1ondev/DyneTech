package com.confect1on.dynetech.network;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.blockentity.StructureShrinkerBlockEntity;
import com.confect1on.dynetech.config.DTConfig;
import com.confect1on.dynetech.menu.StructureShrinkerMenu;
import com.confect1on.dynetech.storage.ShrunkenStructureStorage;
import com.confect1on.dynetech.storage.StructureBlob;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

public final class DTPayloads {

    // Vanilla's per-packet payload limit is ~1 MiB. Sub-payload byte arrays get sized-checked via
    // ByteBufCodecs.byteArray(max) to reject anything obviously oversized before decoding.
    private static final int MAX_CHUNK_BYTES_HARD = 1_000_000;

    private DTPayloads() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(DyneTech.MODID).versioned("1");

        registrar.playToServer(
                ActivateShrinker.TYPE,
                ActivateShrinker.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    var player = ctx.player();
                    if (player.containerMenu instanceof StructureShrinkerMenu menu
                            && menu.getBlockEntity() instanceof StructureShrinkerBlockEntity be) {
                        be.shrink(player);
                        player.closeContainer();
                    }
                }));

        registrar.playToServer(
                RequestStructure.TYPE,
                RequestStructure.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (!(ctx.player() instanceof ServerPlayer sp)) return;
                    StructureBlob blob = ShrunkenStructureStorage.get(sp.getServer()).get(payload.id());
                    if (blob == null) return;
                    sendBlobChunked(sp, payload.id(), blob, sp.registryAccess());
                }));

        registrar.playToClient(
                SyncStructureChunk.TYPE,
                SyncStructureChunk.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() ->
                        com.confect1on.dynetech.client.ClientStructureChunkAssembler.accept(
                                payload.id(), payload.seq(), payload.total(), payload.data(),
                                ctx.player().registryAccess())));

        registrar.playToClient(
                SpawnPulses.TYPE,
                SpawnPulses.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() ->
                        com.confect1on.dynetech.client.DiscoPulseManager.trigger(payload.entityId())));
    }

    /**
     * Serializes the blob to compressed NBT bytes and dispatches them across N SyncStructureChunk
     * packets sized under the vanilla packet cap. TCP guarantees order; assembler on the client
     * concatenates and decodes when the final chunk arrives.
     */
    private static void sendBlobChunked(ServerPlayer sp, UUID id, StructureBlob blob, HolderLookup.Provider registries) {
        CompoundTag tag = blob.save(registries);
        byte[] bytes;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, baos);
            bytes = baos.toByteArray();
        } catch (IOException e) {
            return;
        }
        int chunkSize = Math.min(DTConfig.SYNC_CHUNK_BYTES.get(), MAX_CHUNK_BYTES_HARD);
        int total = Math.max(1, (bytes.length + chunkSize - 1) / chunkSize);
        for (int i = 0; i < total; i++) {
            int off = i * chunkSize;
            int len = Math.min(chunkSize, bytes.length - off);
            byte[] chunk = Arrays.copyOfRange(bytes, off, off + len);
            PacketDistributor.sendToPlayer(sp, new SyncStructureChunk(id, i, total, chunk));
        }
    }

    /**
     * Client-side helper wrapping {@link NbtIo#readCompressed}; used by the chunk assembler.
     */
    public static CompoundTag decodeCompressedNbt(byte[] bytes) throws IOException {
        return NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
    }

    public record ActivateShrinker() implements CustomPacketPayload {
        public static final Type<ActivateShrinker> TYPE = new Type<>(DyneTech.id("activate_shrinker"));
        public static final StreamCodec<FriendlyByteBuf, ActivateShrinker> STREAM_CODEC =
                StreamCodec.unit(new ActivateShrinker());

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RequestStructure(UUID id) implements CustomPacketPayload {
        public static final Type<RequestStructure> TYPE = new Type<>(DyneTech.id("request_structure"));
        public static final StreamCodec<FriendlyByteBuf, RequestStructure> STREAM_CODEC =
                StreamCodec.composite(UUIDUtil.STREAM_CODEC, RequestStructure::id, RequestStructure::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SpawnPulses(int entityId) implements CustomPacketPayload {
        public static final Type<SpawnPulses> TYPE = new Type<>(DyneTech.id("spawn_pulses"));
        public static final StreamCodec<FriendlyByteBuf, SpawnPulses> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, SpawnPulses::entityId, SpawnPulses::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SyncStructureChunk(UUID id, int seq, int total, byte[] data) implements CustomPacketPayload {
        public static final Type<SyncStructureChunk> TYPE = new Type<>(DyneTech.id("sync_structure_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncStructureChunk> STREAM_CODEC =
                StreamCodec.composite(
                        UUIDUtil.STREAM_CODEC, SyncStructureChunk::id,
                        ByteBufCodecs.VAR_INT, SyncStructureChunk::seq,
                        ByteBufCodecs.VAR_INT, SyncStructureChunk::total,
                        ByteBufCodecs.byteArray(MAX_CHUNK_BYTES_HARD), SyncStructureChunk::data,
                        SyncStructureChunk::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
