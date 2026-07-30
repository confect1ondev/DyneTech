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

        // Player-blood samples show their decay clock right under the state header so a glance
        // at the tooltip tells the player whether the vial is still safe to use or needs to be
        // slotted into a Cryo Preservator.
        appendExpiryLine(c, lines);

        switch (c.state()) {
            case RAW -> appendRaw(c, lines);
            case SERUM, BOUND_SERUM -> {
                // Serum is blood + spliced gene. Show the blood source line first so it reads
                // as "this is X's blood, spliced with:" followed by the perk list. Bound serums
                // render the same way; the state header already tells the reader which is which.
                appendBloodFromLine(c, lines);
                lines.add(Component.translatable("dynetech.vial.spliced_with").withStyle(ChatFormatting.DARK_GRAY));
                appendPerks(c, lines);
            }
            case ISOLATED -> appendPerks(c, lines);
            default -> {}
        }

        // Isolated still gets the compact donor/species footer. RAW/SERUM/BOUND_SERUM already
        // carry it, and EMPTY has nothing to attribute.
        if (c.state() == VialState.ISOLATED) appendDonorLine(c, lines);
    }

    /**
     * Read the vial's expiry against the client's wall-clock game time. This only fires for
     * player-blood samples; nothing else carries a decay timer. Uses the client level so the
     * countdown updates every render tick without the item needing to sync on every change.
     */
    private static void appendExpiryLine(VialContents c, List<Component> lines) {
        if (c.expiresAtGameTime().isEmpty()) return;
        long expiresAt = c.expiresAtGameTime().get();
        long now = clientGameTime();
        long remaining = expiresAt - now;
        if (remaining <= 0) {
            lines.add(Component.translatable("dynetech.vial.expired").withStyle(ChatFormatting.DARK_RED));
            return;
        }
        long seconds = remaining / 20L;
        long m = seconds / 60L;
        long s = seconds % 60L;
        String stamp = String.format("%d:%02d", m, s);
        ChatFormatting color = seconds < 30 ? ChatFormatting.RED
                : seconds < 120 ? ChatFormatting.GOLD
                : ChatFormatting.AQUA;
        lines.add(Component.translatable("dynetech.vial.fresh_for", stamp).withStyle(color));
    }

    private static long clientGameTime() {
        // Tooltips only render client-side, but this class also ends up class-loaded on dedicated
        // servers for stat logging. Route the Minecraft lookup through DistExecutor so the
        // client-only reference is never linked in a server JVM.
        if (net.neoforged.fml.loading.FMLEnvironment.dist != net.neoforged.api.distmarker.Dist.CLIENT) {
            return 0L;
        }
        return ClientGameTime.get();
    }

    /** Split into a nested class so its Minecraft reference is only touched on the client. */
    private static final class ClientGameTime {
        static long get() {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            return mc != null && mc.level != null ? mc.level.getGameTime() : 0L;
        }
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
