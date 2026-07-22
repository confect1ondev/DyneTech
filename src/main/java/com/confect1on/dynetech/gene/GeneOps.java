package com.confect1on.dynetech.gene;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure vial operations. Deterministic given the same inputs (barring the RNG source), no entity
 * mutation. If you want to mutate a target's genome, use {@link PerkLifecycle}.
 *
 * <h3>The three ops</h3>
 * <ul>
 *   <li>{@link #rollRawFromEntity} produces a RAW vial from a mob or a player. Player-blood is
 *   special: the resulting vial is inspection-locked (the sequencer refuses it) and carries a
 *   snapshot of that player's currently-equipped perks.</li>
 *   <li>{@link #sequenceOne} draws a single perk out of a RAW mob vial into an ISOLATED vial and
 *   rolls a condition based on quality.</li>
 *   <li>{@link #splice} either merges two ISOLATED vials or turns a RAW + ISOLATED pair into a
 *   SERUM that inherits the RAW's donor identity.</li>
 * </ul>
 */
public final class GeneOps {

    private static final int SAMPLE_MIN_ENTRIES = 2;
    private static final int SAMPLE_MAX_ENTRIES = 5;

    private GeneOps() {}

    // ============================================================================
    //  Sampling
    // ============================================================================

    public static VialContents rollRawFromEntity(LivingEntity donor, RandomSource rng) {
        ResourceLocation donorTypeId = BuiltInRegistries.ENTITY_TYPE.getKey(donor.getType());
        Optional<String> donorName = resolveDonorName(donor);

        // Player blood is treated specially: instead of rolling from a species pool, we snapshot
        // the donor's actual equipped perks. The resulting vial is locked. The sequencer refuses
        // it, but the microscope can read the snapshot to show that specific player's genes.
        if (donor instanceof Player) {
            EquippedPerks eq = donor.getData(DTAttachments.EQUIPPED_PERKS.get());
            return VialContents.rawPlayerBlood(donor.getUUID(), donorName.orElse(donor.getName().getString()),
                    donorTypeId, eq.perks());
        }

        List<SpeciesPool.Weighted> pool = SpeciesPool.forEntity(donor);
        if (pool.isEmpty()) {
            return VialContents.raw(List.of(), donor.getUUID(), donorName, donorTypeId);
        }

        int desired = SAMPLE_MIN_ENTRIES + rng.nextInt(SAMPLE_MAX_ENTRIES - SAMPLE_MIN_ENTRIES + 1);
        desired = Math.min(desired, pool.size());

        List<SpeciesPool.Weighted> remaining = new ArrayList<>(pool);
        List<PerkEntry> chosen = new ArrayList<>(desired);
        for (int i = 0; i < desired && !remaining.isEmpty(); i++) {
            SpeciesPool.Weighted picked = pickWeighted(remaining, rng);
            remaining.remove(picked);
            chosen.add(new PerkEntry(picked.perkId(), rollQuality(rng), Optional.of(donor.getUUID()), Optional.empty()));
        }

        return VialContents.raw(chosen, donor.getUUID(), donorName, donorTypeId);
    }

    /**
     * Locale-independent donor naming. Players carry their real name; mobs carry a name only when
     * they've been renamed (nametag etc.). Everything else falls through to the species label
     * that the client resolves off the entity type id.
     */
    private static Optional<String> resolveDonorName(LivingEntity donor) {
        if (donor instanceof Player) return Optional.of(donor.getName().getString());
        if (donor.hasCustomName()) {
            var custom = donor.getCustomName();
            return custom != null ? Optional.of(custom.getString()) : Optional.empty();
        }
        return Optional.empty();
    }

    // ============================================================================
    //  Sequencing
    // ============================================================================

    /**
     * Draws one perk out of the raw pool, rolls a fresh quality, and rolls a random condition.
     * Higher quality biases toward {@link PerkCondition#ALWAYS}; low-quality vials get more
     * restrictive conditions.
     *
     * <p>Restrictive conditions are drawn from the perk's own {@link Perk#allowedConditions()}
     * so the sequencer can't produce useless combos like "Gills only while swimming".
     */
    public static VialContents sequenceOne(VialContents raw, RandomSource rng) {
        if (raw.state() != VialState.RAW || raw.perks().isEmpty()) return VialContents.EMPTY;
        // Player-blood vials are inspection-only. The sequencer refuses to touch them.
        if (raw.isPlayerBlood()) return VialContents.EMPTY;

        PerkEntry picked = raw.perks().get(rng.nextInt(raw.perks().size()));
        float quality = rollQuality(rng);
        Perk perk = Perks.get(picked.perkId());
        Optional<PerkCondition> condition = rollCondition(perk, quality, rng);
        PerkEntry isolatedEntry = new PerkEntry(picked.perkId(), quality, picked.donor(), condition);

        return VialContents.isolated(isolatedEntry, raw.donor(), raw.donorName(), raw.donorType());
    }

    private static Optional<PerkCondition> rollCondition(Perk perk, float quality, RandomSource rng) {
        if (perk == null) return Optional.empty();
        if (rng.nextFloat() > 1.0F - quality) return Optional.empty();
        List<PerkCondition> restrictive = perk.allowedConditions().stream()
                .filter(c -> c != PerkCondition.ALWAYS)
                .toList();
        if (restrictive.isEmpty()) return Optional.empty();
        return Optional.of(restrictive.get(rng.nextInt(restrictive.size())));
    }

    // ============================================================================
    //  Splicing
    //   ISOLATED + ISOLATED -> merged ISOLATED (donor may drop if lineages disagree)
    //   RAW + ISOLATED      -> SERUM with donor pulled from the RAW blood
    // ============================================================================

    /**
     * Blood is the delivery vehicle: the resulting SERUM inherits its donor identity from the
     * RAW input, and the gene payload from the ISOLATED input. Injection later validates that
     * the target matches this donor (player UUID for player blood, entity type for mob blood).
     */
    public static Optional<VialContents> splice(VialContents a, VialContents b) {
        if (a.state() == VialState.ISOLATED && b.state() == VialState.ISOLATED) {
            return Optional.of(VialContents.mergeIsolated(a, b));
        }
        if (a.state() == VialState.RAW && b.state() == VialState.ISOLATED) {
            return Optional.of(VialContents.serum(b.perks(), a.donor(), a.donorName(), a.donorType()));
        }
        if (a.state() == VialState.ISOLATED && b.state() == VialState.RAW) {
            return Optional.of(VialContents.serum(a.perks(), b.donor(), b.donorName(), b.donorType()));
        }
        return Optional.empty();
    }

    // ============================================================================
    //  RNG helpers
    // ============================================================================

    /** Average of two rolls, giving a triangular distribution centered near 0.5. */
    public static float rollQuality(RandomSource rng) {
        float a = rng.nextFloat();
        float b = rng.nextFloat();
        return Math.min(1.0F, Math.max(0.0F, (a + b) * 0.5F));
    }

    static SpeciesPool.Weighted pickWeighted(List<SpeciesPool.Weighted> pool, RandomSource rng) {
        int total = 0;
        for (SpeciesPool.Weighted w : pool) total += w.weight();
        int roll = rng.nextInt(Math.max(1, total));
        for (SpeciesPool.Weighted w : pool) {
            roll -= w.weight();
            if (roll < 0) return w;
        }
        return pool.get(pool.size() - 1);
    }
}
