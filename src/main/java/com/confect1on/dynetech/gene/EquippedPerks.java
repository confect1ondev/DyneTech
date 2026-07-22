package com.confect1on.dynetech.gene;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data-attachment payload stored on every {@link net.minecraft.world.entity.LivingEntity}
 * that has ever been injected. Persistent; codec-serialized via NeoForge attachment API.
 */
public record EquippedPerks(List<PerkEntry> perks) {

    public static final EquippedPerks EMPTY = new EquippedPerks(List.of());

    public static final Codec<EquippedPerks> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            PerkEntry.CODEC.listOf().fieldOf("perks").forGetter(EquippedPerks::perks)
    ).apply(inst, EquippedPerks::new));

    public EquippedPerks {
        perks = List.copyOf(perks);
    }

    /**
     * Merges an incoming entry using the same semantics as {@link VialContents#mergeIsolated}:
     * duplicate perk ids average their quality, and the existing entry's condition/donor are
     * preserved. Distinct ids append. Callers that need the resolved entry (e.g. to fire
     * {@link Perk#onEquip} with the averaged quality) should read it back via {@link #find}.
     */
    public EquippedPerks add(PerkEntry entry) {
        List<PerkEntry> next = new ArrayList<>(perks);
        for (int i = 0; i < next.size(); i++) {
            PerkEntry existing = next.get(i);
            if (existing.perkId().equals(entry.perkId())) {
                float q = (existing.quality() + entry.quality()) * 0.5F;
                next.set(i, existing.withQuality(q));
                return new EquippedPerks(next);
            }
        }
        next.add(entry);
        return new EquippedPerks(next);
    }

    public EquippedPerks remove(ResourceLocation perkId) {
        List<PerkEntry> next = new ArrayList<>(perks);
        next.removeIf(e -> e.perkId().equals(perkId));
        return new EquippedPerks(next);
    }

    public boolean has(ResourceLocation perkId) {
        for (PerkEntry e : perks) if (e.perkId().equals(perkId)) return true;
        return false;
    }

    /** Current entry for a perk id, if equipped. Reflects any averaging applied by {@link #add}. */
    public Optional<PerkEntry> find(ResourceLocation perkId) {
        for (PerkEntry e : perks) if (e.perkId().equals(perkId)) return Optional.of(e);
        return Optional.empty();
    }
}
