package com.confect1on.dynetech.gene;

import com.mojang.serialization.Codec;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import com.confect1on.dynetech.DyneTech;

import java.util.function.Supplier;

public final class DTAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, DyneTech.MODID);

    /**
     * Deliberately NOT {@code copyOnDeath()} - a player's genome is discarded on death and the
     * respawned entity starts clean. Attribute modifiers reset with the entity anyway; scale
     * perks read from a clean attachment on respawn and revert to 1.0.
     */
    public static final Supplier<AttachmentType<EquippedPerks>> EQUIPPED_PERKS =
            ATTACHMENT_TYPES.register("equipped_perks",
                    () -> AttachmentType.builder(() -> EquippedPerks.EMPTY)
                            .serialize(EquippedPerks.CODEC)
                            .build());

    /**
     * Tick number the entity was last hit on. Stamped by {@code PerkEvents.onDamageStampTimer}
     * and read by {@code AbsorptionPerk} to decide when to re-grant an expired Absorption effect.
     * Reset happens on the damage event so a quick heal-back-to-max between tick observations
     * doesn't fool the regen delay.
     */
    public static final Supplier<AttachmentType<Long>> LAST_DAMAGED_TICK =
            ATTACHMENT_TYPES.register("last_damaged_tick",
                    () -> AttachmentType.builder(() -> -1L)
                            .serialize(Codec.LONG)
                            .build());

    /**
     * Godhood state - regeneration charges + timers for the in-place regeneration and post-regen
     * vulnerability window. Persists across sessions and dimension change but resets on a true
     * death like the equipped-perks attachment (respawned entity starts fresh).
     */
    public static final Supplier<AttachmentType<GodhoodState>> GODHOOD_STATE =
            ATTACHMENT_TYPES.register("godhood_state",
                    () -> AttachmentType.builder(() -> GodhoodState.EMPTY)
                            .serialize(GodhoodState.CODEC)
                            .build());

    /**
     * Marks an entity as a valid essence source for the Godhood gene. A god killing a marked
     * entity earns a charge through the same {@code tryGrantEssence} path a player kill goes
     * through. Only spawned via the {@code /dynetech godhood spawn_dummy} command; not used
     * anywhere in the survival pipeline.
     */
    public static final Supplier<AttachmentType<Boolean>> TEST_ESSENCE_TARGET =
            ATTACHMENT_TYPES.register("test_essence_target",
                    () -> AttachmentType.builder(() -> Boolean.FALSE)
                            .serialize(Codec.BOOL)
                            .build());

    /**
     * Shoal-carrier bookkeeping: incubation start, seep clock and per-day conversion budget.
     * Lives on the player attachment and is cleared with everything else on death via
     * {@code clearAllPerks}'s companion sweep in {@code PerkEvents.onDeath}. A fresh player has
     * no record; the fast path in the seep tick avoids materializing the default for non-hosts.
     */
    public static final Supplier<AttachmentType<ShoalHostState>> SHOAL_HOST_STATE =
            ATTACHMENT_TYPES.register("shoal_host_state",
                    () -> AttachmentType.builder(() -> ShoalHostState.EMPTY)
                            .serialize(ShoalHostState.CODEC)
                            .build());

    private DTAttachments() {}
}
