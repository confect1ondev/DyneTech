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

    private DTAttachments() {}
}
