package com.confect1on.dynetech.gene;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.EntityType;

import java.util.List;
import java.util.Optional;

/**
 * Shared vial-contents tooltip renderer. Used by both {@code GeneVialItem} and
 * {@code InjectionGunItem} so the two containers describe the same payload identically.
 *
 * <p>donorName is only populated at draw time for players and custom-named mobs; unnamed mobs
 * leave it empty so the tooltip renders the species label off the entity type id, which
 * localizes on the viewer's client.
 */
public final class VialTooltip {

    private VialTooltip() {}

    public static void append(VialContents c, List<Component> lines, boolean includeStateHeader) {
        if (includeStateHeader) {
            lines.add(Component.translatable(c.state().langKey()).withStyle(ChatFormatting.GRAY));
        }

        switch (c.state()) {
            case RAW -> appendRaw(c, lines);
            case SERUM -> {
                // Serum is blood + spliced gene. Show the blood source line first so it reads
                // as "this is X's blood, spliced with:" followed by the perk list.
                appendBloodFromLine(c, lines);
                lines.add(Component.translatable("dynetech.vial.spliced_with").withStyle(ChatFormatting.DARK_GRAY));
                appendPerks(c, lines);
            }
            case ISOLATED -> appendPerks(c, lines);
            default -> {}
        }

        // Isolated still gets the compact donor/species footer. RAW/SERUM already carry it,
        // and EMPTY has nothing to attribute.
        if (c.state() == VialState.ISOLATED) appendDonorLine(c, lines);
    }

    private static void appendBloodFromLine(VialContents c, List<Component> lines) {
        Component source = c.donorName()
                .<Component>map(Component::literal)
                .or(() -> speciesLabel(c))
                .orElse(Component.literal("?"));
        lines.add(Component.translatable("dynetech.vial.blood_from", source).withStyle(ChatFormatting.DARK_RED));
    }

    private static void appendRaw(VialContents c, List<Component> lines) {
        appendBloodFromLine(c, lines);
    }

    private static void appendPerks(VialContents c, List<Component> lines) {
        for (PerkEntry entry : c.perks()) {
            Perk perk = Perks.get(entry.perkId());
            MutableComponent name = perk != null
                    ? perk.displayName().copy()
                    : Component.literal(entry.perkId().toString());
            PerkGrade grade = entry.grade();
            MutableComponent line = Component.empty()
                    .append(Component.literal(" \u2022 ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(name)
                    .append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
                    .append(Component.translatable(grade.langKey()).withStyle(grade.formatting()));
            // Restrictive condition (if any) shown in brackets after the grade so it's obvious
            // when a perk is only going to fire in specific situations.
            entry.condition().ifPresent(cond -> {
                if (cond == PerkCondition.ALWAYS) return;
                line.append(Component.literal(" [").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.translatable(cond.langKey()).withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY));
            });
            lines.add(line);
        }
    }

    /**
     * Draws one donor/species line for Isolated/Serum vials. Skips entirely for Empty (nothing
     * to attribute) and Raw (blood_from already carried the species). When donorName is present
     * we render "donor (species)"; otherwise just the species.
     */
    private static void appendDonorLine(VialContents c, List<Component> lines) {
        if (c.state() == VialState.EMPTY || c.state() == VialState.RAW) return;

        Optional<Component> species = speciesLabel(c);
        Optional<String> donorName = c.donorName();

        if (donorName.isPresent() && species.isPresent()) {
            lines.add(Component.translatable("dynetech.vial.donor_and_species", donorName.get(), species.get())
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else if (species.isPresent()) {
            lines.add(Component.translatable("dynetech.vial.source_species", species.get())
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else if (donorName.isPresent()) {
            lines.add(Component.translatable("dynetech.vial.donor", donorName.get())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static Optional<Component> speciesLabel(VialContents c) {
        return c.donorType().map(id -> {
            EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.get(id);
            return t != null ? Component.translatable(t.getDescriptionId()) : Component.literal(id.toString());
        });
    }
}
