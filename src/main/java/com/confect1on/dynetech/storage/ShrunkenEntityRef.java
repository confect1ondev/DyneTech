package com.confect1on.dynetech.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * Data component payload for the shrunken-entity item — the captured mob's type id, its full NBT
 * (so it can be resurrected verbatim on regrow), and whether the capture was lethal (permanent).
 *
 * <p>{@code lethal} defaults to false via {@link Codec#optionalFieldOf} so pre-existing stacks
 * from the non-lethal-only era load as non-lethal.
 */
public record ShrunkenEntityRef(ResourceLocation entityType, CompoundTag data, boolean lethal) {

    public static final Codec<ShrunkenEntityRef> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            ResourceLocation.CODEC.fieldOf("type").forGetter(ShrunkenEntityRef::entityType),
            CompoundTag.CODEC.fieldOf("data").forGetter(ShrunkenEntityRef::data),
            Codec.BOOL.optionalFieldOf("lethal", false).forGetter(ShrunkenEntityRef::lethal)
    ).apply(inst, ShrunkenEntityRef::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShrunkenEntityRef> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ShrunkenEntityRef decode(RegistryFriendlyByteBuf buf) {
            ResourceLocation type = ResourceLocation.STREAM_CODEC.decode(buf);
            CompoundTag data = ByteBufCodecs.TRUSTED_COMPOUND_TAG.decode(buf);
            boolean lethal = buf.readBoolean();
            return new ShrunkenEntityRef(type, data, lethal);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, ShrunkenEntityRef ref) {
            ResourceLocation.STREAM_CODEC.encode(buf, ref.entityType);
            ByteBufCodecs.TRUSTED_COMPOUND_TAG.encode(buf, ref.data);
            buf.writeBoolean(ref.lethal);
        }
    };
}
