package com.confect1on.dynetech.gene;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/**
 * A single perk instance carried by a vial or equipped on a living entity.
 *
 * @param perkId    identifier of the perk in {@link Perks}
 * @param quality   [0,1] float driving the discrete {@link PerkGrade}
 * @param donor     original donor UUID (may be absent for dev-spawn stacks)
 * @param condition when present, the perk only expresses while the condition is satisfied. Absent
 *                  or {@link PerkCondition#ALWAYS} both mean "always on"
 */
public record PerkEntry(ResourceLocation perkId, float quality,
                        Optional<UUID> donor, Optional<PerkCondition> condition) {

    public static final Codec<PerkEntry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            ResourceLocation.CODEC.fieldOf("perk").forGetter(PerkEntry::perkId),
            Codec.FLOAT.fieldOf("quality").forGetter(PerkEntry::quality),
            UUIDUtil.CODEC.optionalFieldOf("donor").forGetter(PerkEntry::donor),
            PerkCondition.CODEC.optionalFieldOf("condition").forGetter(PerkEntry::condition)
    ).apply(inst, PerkEntry::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, PerkEntry> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, PerkEntry::perkId,
            ByteBufCodecs.FLOAT, PerkEntry::quality,
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), PerkEntry::donor,
            ByteBufCodecs.optional(PerkCondition.STREAM_CODEC), PerkEntry::condition,
            PerkEntry::new);

    public PerkEntry withQuality(float q) { return new PerkEntry(perkId, q, donor, condition); }

    public PerkGrade grade() { return PerkGrade.fromQuality(quality); }

    /** Effective condition: {@link PerkCondition#ALWAYS} when unset. */
    public PerkCondition effectiveCondition() { return condition.orElse(PerkCondition.ALWAYS); }

    /**
     * True when the entry has fallen below the CORRUPTED grade threshold. Denatured entries
     * express no effect at all; they occupy a genome slot but are inert.
     */
    public boolean isDenatured() { return PerkGrade.isDenatured(quality); }
}
