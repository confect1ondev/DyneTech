package com.confect1on.dynetech.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.Vec3i;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/**
 * The bit that goes on the {@code ShrunkenStructureItem} via a DataComponent —
 * a pointer to server-side {@link ShrunkenStructureStorage} plus size (for tooltip).
 */
public record ShrunkenStructureRef(UUID id, Vec3i size) {

    public static final Codec<ShrunkenStructureRef> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(ShrunkenStructureRef::id),
            Vec3i.CODEC.fieldOf("size").forGetter(ShrunkenStructureRef::size)
    ).apply(inst, ShrunkenStructureRef::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShrunkenStructureRef> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ShrunkenStructureRef decode(RegistryFriendlyByteBuf buf) {
            UUID id = UUIDUtil.STREAM_CODEC.decode(buf);
            int x = buf.readVarInt();
            int y = buf.readVarInt();
            int z = buf.readVarInt();
            return new ShrunkenStructureRef(id, new Vec3i(x, y, z));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, ShrunkenStructureRef ref) {
            UUIDUtil.STREAM_CODEC.encode(buf, ref.id);
            buf.writeVarInt(ref.size.getX());
            buf.writeVarInt(ref.size.getY());
            buf.writeVarInt(ref.size.getZ());
        }
    };
}
