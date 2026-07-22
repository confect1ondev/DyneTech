package com.confect1on.dynetech.gene;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

import java.util.Set;

/**
 * A single trait a vial can carry and an entity can be injected with. Compositional over
 * vanilla attributes, mob effects, Pehkui scale, and event hooks - deliberately no LucraftCore
 * dependency.
 *
 * <h3>Lifecycle</h3>
 * <ul>
 *   <li>{@link #onEquip} - runs once on injection. Should apply state <em>only if the entry's
 *   condition is currently satisfied</em>; the {@code isCurrentlyActive} helper handles the
 *   check.</li>
 *   <li>{@link #onUnequip} - runs once when the perk is stripped. Must reverse everything
 *   {@code onEquip} did, unconditionally.</li>
 *   <li>{@link #tick} - runs every server tick. For conditional perks this is where the
 *   condition-transition reconciliation happens.</li>
 * </ul>
 *
 * <h3>{@link #allowedConditions()}</h3>
 * <p>Curated per perk. This is how we guarantee that random condition rolls never produce a
 * useless combination like "Gills only while swimming" or "Fireproof only when not on fire".
 * Include {@link PerkCondition#ALWAYS} and any conditions that read as pure tradeoffs, never
 * ones that would negate the perk's own purpose.
 */
public interface Perk {

    ResourceLocation id();

    Component displayName();

    /**
     * Short explanation of what the perk does. Default resolves via translatable so lang can
     * live in {@code dynetech.perk.desc.<id_path>}; individual perks can override for dynamic
     * text (e.g. quality-scaled numbers).
     */
    default Component description() {
        return Component.translatable("dynetech.perk.desc." + id().getPath());
    }

    /** Tint used when rendering the vial helix - vanilla-potion-style layer1 recolor. */
    default int color() { return 0xFFFFFF; }

    /** Defects are perks that only ever appear via botched injections. Filtered out of sample pools. */
    default boolean isDefect() { return false; }

    /**
     * Which conditions may be attached to this perk by the sequencer's condition roll. Must
     * always include {@link PerkCondition#ALWAYS} as the "no-condition" branch.
     */
    Set<PerkCondition> allowedConditions();

    void onEquip(LivingEntity entity, PerkEntry entry);

    void onUnequip(LivingEntity entity, PerkEntry entry);

    default void tick(LivingEntity entity, PerkEntry entry) {}

    /**
     * Convenience for perks that gate their apply/tick on the entry's condition. Also enforces
     * the denatured floor: an entry with {@link PerkEntry#isDenatured()} never expresses,
     * regardless of condition. All conditional, event-driven, and defect perks route through
     * this helper, so the gate is uniform across the whole gene system.
     */
    default boolean isCurrentlyActive(LivingEntity entity, PerkEntry entry) {
        if (entry.isDenatured()) return false;
        return entry.effectiveCondition().check(entity);
    }
}
