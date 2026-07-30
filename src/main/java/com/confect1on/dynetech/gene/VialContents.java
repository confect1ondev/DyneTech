package com.confect1on.dynetech.gene;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data-component payload attached to every non-empty Gene Vial stack.
 *
 * @param state         one of EMPTY/RAW/ISOLATED/SERUM (see {@link VialState})
 * @param perks         perks the vial currently carries (empty for EMPTY vials; species pool
 *                      for RAW mob vials; the drawn player's snapshot lives in
 *                      {@link #playerSnapshot} instead for player RAW vials)
 * @param donor         donor UUID
 * @param donorName     donor's display name at draw time
 * @param donorType     donor's {@link net.minecraft.world.entity.EntityType} id
 * @param playerSnapshot when present, this is a locked snapshot of the drawn player's equipped
 *                      perks at the moment of the draw. Player blood vials are un-sequenceable
 *                      - the microscope reads this list to show that specific player's genes,
 *                      drawbacks, and conditions all at once.
 */
public record VialContents(
        VialState state,
        List<PerkEntry> perks,
        Optional<UUID> donor,
        Optional<String> donorName,
        Optional<ResourceLocation> donorType,
        Optional<List<PerkEntry>> playerSnapshot
) {
    public static final VialContents EMPTY =
            new VialContents(VialState.EMPTY, List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    /**
     * Synthetic donor type used exclusively by op-issued {@code /dynetech vial} outputs. A vial
     * carrying this donor type bypasses the injection compatibility check in
     * {@link PerkLifecycle}, so a creative-mode serum works on any target regardless of species
     * or player identity. Not producible by the sequencer, splicer, or blood-draw pipeline.
     */
    public static final ResourceLocation UNIVERSAL_DONOR_TYPE =
            ResourceLocation.fromNamespaceAndPath("dynetech", "universal");

    public static final Codec<VialContents> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            VialState.CODEC.fieldOf("state").forGetter(VialContents::state),
            PerkEntry.CODEC.listOf().fieldOf("perks").forGetter(VialContents::perks),
            UUIDUtil.CODEC.optionalFieldOf("donor").forGetter(VialContents::donor),
            Codec.STRING.optionalFieldOf("donor_name").forGetter(VialContents::donorName),
            ResourceLocation.CODEC.optionalFieldOf("donor_type").forGetter(VialContents::donorType),
            PerkEntry.CODEC.listOf().optionalFieldOf("player_snapshot").forGetter(VialContents::playerSnapshot)
    ).apply(inst, VialContents::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, VialContents> STREAM_CODEC = StreamCodec.composite(
            VialState.STREAM_CODEC,                                      VialContents::state,
            PerkEntry.STREAM_CODEC.apply(ByteBufCodecs.list()),          VialContents::perks,
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC),               VialContents::donor,
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8),           VialContents::donorName,
            ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC),       VialContents::donorType,
            ByteBufCodecs.optional(PerkEntry.STREAM_CODEC.apply(ByteBufCodecs.list())), VialContents::playerSnapshot,
            VialContents::new);

    public VialContents {
        perks = List.copyOf(perks);
        playerSnapshot = playerSnapshot.map(List::copyOf);
    }

    public static VialContents raw(List<PerkEntry> pool, UUID donorId, Optional<String> donorName, ResourceLocation donorType) {
        return new VialContents(VialState.RAW, pool,
                Optional.of(donorId), donorName, Optional.of(donorType), Optional.empty());
    }

    public static VialContents rawPlayerBlood(UUID donorId, String donorName, ResourceLocation donorType,
                                              List<PerkEntry> equippedSnapshot) {
        return new VialContents(VialState.RAW, List.of(),
                Optional.of(donorId), Optional.of(donorName), Optional.of(donorType),
                Optional.of(equippedSnapshot));
    }

    public static VialContents isolated(PerkEntry entry, Optional<UUID> donor,
                                        Optional<String> donorName, Optional<ResourceLocation> donorType) {
        return new VialContents(VialState.ISOLATED, List.of(entry), donor, donorName, donorType, Optional.empty());
    }

    public static VialContents serum(List<PerkEntry> perks, Optional<UUID> donor,
                                     Optional<String> donorName, Optional<ResourceLocation> donorType) {
        return new VialContents(VialState.SERUM, perks, donor, donorName, donorType, Optional.empty());
    }

    /** True if this is a locked player-blood RAW vial - sequencer must refuse it. */
    public boolean isPlayerBlood() { return playerSnapshot.isPresent(); }

    /** Merge two ISOLATED contents by concatenating their perk lists (dupe perkIds are averaged). */
    public static VialContents mergeIsolated(VialContents a, VialContents b) {
        List<PerkEntry> merged = new ArrayList<>(a.perks);
        outer:
        for (PerkEntry other : b.perks) {
            for (int i = 0; i < merged.size(); i++) {
                if (merged.get(i).perkId().equals(other.perkId())) {
                    PerkEntry existing = merged.get(i);
                    float q = (existing.quality() + other.quality()) * 0.5F;
                    merged.set(i, existing.withQuality(q));
                    continue outer;
                }
            }
            merged.add(other);
        }
        Optional<UUID> donor = a.donor.or(() -> b.donor);
        Optional<String> donorName = a.donorName.or(() -> b.donorName);
        Optional<ResourceLocation> donorType = a.donorType.equals(b.donorType) ? a.donorType : Optional.empty();
        return new VialContents(VialState.ISOLATED, Collections.unmodifiableList(merged), donor, donorName, donorType, Optional.empty());
    }

    public float maxQuality() {
        float q = 0.0F;
        for (PerkEntry entry : perks) if (entry.quality() > q) q = entry.quality();
        return q;
    }
}
