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
import com.confect1on.dynetech.item.InjectionGunItem;
import com.confect1on.dynetech.menu.StructureShrinkerMenu;
import com.confect1on.dynetech.storage.ShrunkenStructureStorage;
import com.confect1on.dynetech.storage.StructureBlob;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

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

        registrar.playToClient(
                SpawnPulsesColored.TYPE,
                SpawnPulsesColored.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() ->
                        com.confect1on.dynetech.client.DiscoPulseManager.trigger(payload.entityId(), payload.rgb())));

        registrar.playToClient(
                GodhoodRegenStart.TYPE,
                GodhoodRegenStart.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() ->
                        com.confect1on.dynetech.client.GodhoodBurnupManager.start(payload.entityId(), payload.durationTicks())));

        registrar.playToClient(
                GodhoodChargeSync.TYPE,
                GodhoodChargeSync.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() ->
                        com.confect1on.dynetech.client.GodhoodBurnupManager.updateCharges(payload.charges(), payload.max())));

        registrar.playToClient(
                GodhoodDetonation.TYPE,
                GodhoodDetonation.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() ->
                        com.confect1on.dynetech.client.GodhoodShockwaveManager.trigger(
                                payload.entityId(), payload.innerRadius(), payload.outerRadius())));

        registrar.playToClient(
                UsherBlast.TYPE,
                UsherBlast.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() ->
                        com.confect1on.dynetech.client.renderer.usher.UsherAuraManager.triggerBlast(
                                payload.entityId(), payload.innerRadius(), payload.outerRadius())));

        registrar.playToClient(
                UsherRiftCharge.TYPE,
                UsherRiftCharge.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() ->
                        com.confect1on.dynetech.client.renderer.usher.UsherAuraManager.startCharge(
                                payload.entityId(), payload.durationTicks())));

        registrar.playToServer(
                InjectSelfWithGun.TYPE,
                InjectSelfWithGun.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    var player = ctx.player();
                    // Try main hand first, then off-hand — the client should only have sent this
                    // when the gun is actually in one of them, but be defensive.
                    for (InteractionHand hand : InteractionHand.values()) {
                        ItemStack held = player.getItemInHand(hand);
                        if (held.getItem() instanceof InjectionGunItem) {
                            InjectionGunItem.fireAtSelf(player, held);
                            return;
                        }
                    }
                }));

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

    /**
     * Client → server signal for "the player left-clicked empty air while sneaking with a
     * loaded injection gun." The client can't apply perks to the server-authoritative player
     * state directly, so it delegates via this trigger.
     */
    public record InjectSelfWithGun() implements CustomPacketPayload {
        public static final Type<InjectSelfWithGun> TYPE = new Type<>(DyneTech.id("inject_self_with_gun"));
        public static final StreamCodec<FriendlyByteBuf, InjectSelfWithGun> STREAM_CODEC =
                StreamCodec.unit(new InjectSelfWithGun());

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

    /**
     * Fixed-color variant of {@link SpawnPulses}. Used by the Usher Departure so caught mobs
     * flash a consistent purple regardless of their Pehkui scale.
     */
    public record SpawnPulsesColored(int entityId, int rgb) implements CustomPacketPayload {
        public static final Type<SpawnPulsesColored> TYPE = new Type<>(DyneTech.id("spawn_pulses_colored"));
        public static final StreamCodec<FriendlyByteBuf, SpawnPulsesColored> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, SpawnPulsesColored::entityId,
                        ByteBufCodecs.VAR_INT, SpawnPulsesColored::rgb,
                        SpawnPulsesColored::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Kicks off the burn-up render + particle spam for the target entity on all clients that
     * received this packet (all tracking players of that entity). Duration in ticks so the
     * client knows when to stop; server also fires the completion effects independently.
     */
    public record GodhoodRegenStart(int entityId, int durationTicks) implements CustomPacketPayload {
        public static final Type<GodhoodRegenStart> TYPE = new Type<>(DyneTech.id("godhood_regen_start"));
        public static final StreamCodec<FriendlyByteBuf, GodhoodRegenStart> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, GodhoodRegenStart::entityId,
                        ByteBufCodecs.VAR_INT, GodhoodRegenStart::durationTicks,
                        GodhoodRegenStart::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Sent from server the moment the blast fires, driving the client-side expanding shockwave
     * rings so the visible thresholds line up with the actual damage tick (not the client's
     * best guess based on when GodhoodRegenStart arrived). Carries the radii explicitly so the
     * animation matches whatever the server used, even if tuning changes.
     */
    public record GodhoodDetonation(int entityId, float innerRadius, float outerRadius) implements CustomPacketPayload {
        public static final Type<GodhoodDetonation> TYPE = new Type<>(DyneTech.id("godhood_detonation"));
        public static final StreamCodec<FriendlyByteBuf, GodhoodDetonation> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, GodhoodDetonation::entityId,
                        ByteBufCodecs.FLOAT, GodhoodDetonation::innerRadius,
                        ByteBufCodecs.FLOAT, GodhoodDetonation::outerRadius,
                        GodhoodDetonation::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Client-side trigger for the Usher's Departure blast ring visual - same expanding annulus
     * technique as the Godhood shockwave but rendered in the Usher's black-and-purple palette.
     */
    public record UsherBlast(int entityId, float innerRadius, float outerRadius) implements CustomPacketPayload {
        public static final Type<UsherBlast> TYPE = new Type<>(DyneTech.id("usher_blast"));
        public static final StreamCodec<FriendlyByteBuf, UsherBlast> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, UsherBlast::entityId,
                        ByteBufCodecs.FLOAT, UsherBlast::innerRadius,
                        ByteBufCodecs.FLOAT, UsherBlast::outerRadius,
                        UsherBlast::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Opens the Usher's Departure charge visual on the client. Duration lets the client run the
     * ring-contraction timeline without polling the entity's sync data every tick, and lines the
     * collapse pinch up with the server's blast broadcast.
     */
    public record UsherRiftCharge(int entityId, int durationTicks) implements CustomPacketPayload {
        public static final Type<UsherRiftCharge> TYPE = new Type<>(DyneTech.id("usher_rift_charge"));
        public static final StreamCodec<FriendlyByteBuf, UsherRiftCharge> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, UsherRiftCharge::entityId,
                        ByteBufCodecs.VAR_INT, UsherRiftCharge::durationTicks,
                        UsherRiftCharge::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Personal action-bar update so a god sees their own remaining regeneration charges. */
    public record GodhoodChargeSync(int charges, int max) implements CustomPacketPayload {
        public static final Type<GodhoodChargeSync> TYPE = new Type<>(DyneTech.id("godhood_charge_sync"));
        public static final StreamCodec<FriendlyByteBuf, GodhoodChargeSync> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, GodhoodChargeSync::charges,
                        ByteBufCodecs.VAR_INT, GodhoodChargeSync::max,
                        GodhoodChargeSync::new);

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
