package com.confect1on.dynetech.network;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
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
import com.confect1on.dynetech.menu.StructureShrinkerMenu;
import com.confect1on.dynetech.storage.ShrunkenStructureStorage;
import com.confect1on.dynetech.storage.StructureBlob;

import java.util.UUID;

public final class DTPayloads {

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
                    CompoundTag tag = blob.save(sp.registryAccess());
                    PacketDistributor.sendToPlayer(sp, new SyncStructure(payload.id(), tag));
                }));

        registrar.playToClient(
                SyncStructure.TYPE,
                SyncStructure.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    StructureBlob blob = StructureBlob.load(payload.blob(), ctx.player().registryAccess());
                    com.confect1on.dynetech.client.ClientStructureCache.put(payload.id(), blob);
                }));

        registrar.playToClient(
                SpawnPulses.TYPE,
                SpawnPulses.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() ->
                        com.confect1on.dynetech.client.DiscoPulseManager.trigger(payload.entityId())));
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

    public record SyncStructure(UUID id, CompoundTag blob) implements CustomPacketPayload {
        public static final Type<SyncStructure> TYPE = new Type<>(DyneTech.id("sync_structure"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncStructure> STREAM_CODEC =
                StreamCodec.composite(
                        UUIDUtil.STREAM_CODEC, SyncStructure::id,
                        ByteBufCodecs.TRUSTED_COMPOUND_TAG, SyncStructure::blob,
                        SyncStructure::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
