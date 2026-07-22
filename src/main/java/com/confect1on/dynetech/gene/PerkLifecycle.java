package com.confect1on.dynetech.gene;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Entity-side gene lifecycle: everything that mutates the target's equipped-perks attachment or
 * dispatches into individual perk hooks. Pure vial operations live in {@link GeneOps} instead.
 *
 * <p>The four entrypoints:
 * <ul>
 *   <li>{@link #inject} runs a serum against a target, applying its perks and rolling any purity
 *   defects.</li>
 *   <li>{@link #tickPerks} runs once per server tick per living entity that has equipped perks.
 *   The fast path exits before touching the attachment for the vast majority of entities.</li>
 *   <li>{@link #reapplyAll} runs onEquip against every currently-equipped entry. Used by
 *   login / dimension-change hooks so mob effects, scale, and marker effects re-attach.</li>
 *   <li>{@link #clearAllPerks} tears down every equipped entry via onUnequip and resets the
 *   attachment. Used on death so a revived entity does not carry stale modifiers.</li>
 * </ul>
 */
public final class PerkLifecycle {

    /**
     * Perks with quality below this receive a defect chance = (threshold - quality) / threshold.
     * At quality 0.0 that is a 100% defect roll. At quality {@code PURITY_THRESHOLD} it is 0%.
     */
    private static final float PURITY_THRESHOLD = 0.4F;

    private PerkLifecycle() {}

    // ============================================================================
    //  Injection
    // ============================================================================

    /**
     * Applies every perk in {@code contents} to {@code target}. Every perk equips regardless of
     * whether it is in the target's native pool; the "wrong species" restriction is replaced by
     * the purity threshold. Low-quality perks additionally roll a random defect that gets added
     * to the target rather than replacing the intended perk.
     */
    public static void inject(LivingEntity target, VialContents contents, RandomSource rng) {
        if (contents.perks().isEmpty()) return;
        // Genes have to be added back into blood via the splicer before injection: only SERUM
        // can be injected. Bare ISOLATED vials no longer take effect on right-click.
        if (contents.state() != VialState.SERUM) return;
        if (target.level().isClientSide) return;

        if (!isCompatible(target, contents)) {
            if (target instanceof Player p) {
                p.sendSystemMessage(Component.translatable("dynetech.inject.incompatible")
                        .withStyle(ChatFormatting.RED));
            }
            return;
        }

        EquippedPerks current = target.getData(DTAttachments.EQUIPPED_PERKS.get());
        List<Perk> defects = collectDefects();

        List<PerkEntry> equipped = new ArrayList<>();
        List<PerkEntry> defected = new ArrayList<>();

        for (PerkEntry entry : contents.perks()) {
            Perk perk = Perks.get(entry.perkId());
            if (perk == null) continue;

            // Merge into the tracker first; the resolved entry may have averaged quality. Fire
            // onEquip with THAT entry so the modifier and the tracker agree on the current value.
            current = current.add(entry);
            PerkEntry resolved = current.find(entry.perkId()).orElse(entry);
            perk.onEquip(target, resolved);
            equipped.add(resolved);

            // Below the purity threshold, roll for an additional defect. Chance is linear in the
            // quality gap: at PURITY_THRESHOLD chance = 0, at quality 0 chance = 100%.
            if (entry.quality() >= PURITY_THRESHOLD || defects.isEmpty()) continue;
            float defectChance = (PURITY_THRESHOLD - entry.quality()) / PURITY_THRESHOLD;
            if (rng.nextFloat() >= defectChance) continue;

            Perk defect = defects.get(rng.nextInt(defects.size()));
            PerkEntry defectEntry = new PerkEntry(defect.id(), GeneOps.rollQuality(rng), entry.donor(), Optional.empty());
            current = current.add(defectEntry);
            PerkEntry resolvedDefect = current.find(defect.id()).orElse(defectEntry);
            defect.onEquip(target, resolvedDefect);
            defected.add(resolvedDefect);
        }

        target.setData(DTAttachments.EQUIPPED_PERKS.get(), current);
        notifyInjection(target, equipped, defected);
    }

    /**
     * Blood-carrier compatibility gate. Player serums lock to their specific donor UUID; mob
     * serums lock to the donor's {@link net.minecraft.world.entity.EntityType}. A SERUM without
     * a donor type is malformed and is refused.
     */
    private static boolean isCompatible(LivingEntity target, VialContents serum) {
        ResourceLocation donorTypeId = serum.donorType().orElse(null);
        if (donorTypeId == null) return false;

        ResourceLocation playerTypeId = BuiltInRegistries.ENTITY_TYPE.getKey(net.minecraft.world.entity.EntityType.PLAYER);
        boolean donorWasPlayer = donorTypeId.equals(playerTypeId);

        if (donorWasPlayer) {
            return target instanceof Player && serum.donor().map(id -> id.equals(target.getUUID())).orElse(false);
        }
        ResourceLocation targetTypeId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        return donorTypeId.equals(targetTypeId);
    }

    private static void notifyInjection(LivingEntity target, List<PerkEntry> equipped, List<PerkEntry> defected) {
        if (target.level() instanceof ServerLevel sl) {
            if (!equipped.isEmpty()) {
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                        target.getX(), target.getY() + target.getBbHeight() * 0.6, target.getZ(),
                        Math.min(12, equipped.size() * 4), 0.3, 0.4, 0.3, 0.02);
            }
            if (!defected.isEmpty()) {
                sl.sendParticles(ParticleTypes.ANGRY_VILLAGER,
                        target.getX(), target.getY() + target.getBbHeight() * 0.6, target.getZ(),
                        Math.min(6, defected.size() * 2), 0.3, 0.4, 0.3, 0.02);
            }
        }
        if (!(target instanceof Player p)) return;

        for (PerkEntry e : equipped) {
            Perk perk = Perks.get(e.perkId());
            if (perk == null) continue;
            Component condLine = e.condition()
                    .map(c -> Component.literal(" [")
                            .append(Component.translatable(c.langKey()))
                            .append("]")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .orElse(Component.empty());
            p.sendSystemMessage(Component.translatable("dynetech.inject.gained",
                            perk.displayName(),
                            Component.translatable(e.grade().langKey()).withStyle(e.grade().formatting()))
                    .withStyle(ChatFormatting.GREEN)
                    .append(condLine));
        }
        for (PerkEntry e : defected) {
            Perk perk = Perks.get(e.perkId());
            if (perk == null) continue;
            p.sendSystemMessage(Component.translatable("dynetech.inject.defect", perk.displayName())
                    .withStyle(ChatFormatting.RED));
        }
    }

    // ============================================================================
    //  Per-tick + lifecycle
    // ============================================================================

    public static void tickPerks(LivingEntity entity) {
        if (entity.level().isClientSide) return;
        // Fast path for the common case: entities that have never been injected have no
        // attachment stored. hasData is a single map probe and avoids materializing the default
        // EquippedPerks.EMPTY that getData would otherwise return and pin in memory.
        if (!entity.hasData(DTAttachments.EQUIPPED_PERKS.get())) return;
        EquippedPerks eq = entity.getData(DTAttachments.EQUIPPED_PERKS.get());
        if (eq.perks().isEmpty()) return;
        for (PerkEntry entry : eq.perks()) {
            Perk perk = Perks.get(entry.perkId());
            if (perk != null) perk.tick(entity, entry);
        }
    }

    /** Re-runs {@link Perk#onEquip} on every currently-equipped entry. Used on login and dimension change. */
    public static void reapplyAll(LivingEntity entity) {
        if (entity.level().isClientSide) return;
        if (!entity.hasData(DTAttachments.EQUIPPED_PERKS.get())) return;
        EquippedPerks eq = entity.getData(DTAttachments.EQUIPPED_PERKS.get());
        for (PerkEntry entry : eq.perks()) {
            Perk perk = Perks.get(entry.perkId());
            if (perk != null) perk.onEquip(entity, entry);
        }
    }

    /**
     * Runs {@link Perk#onUnequip} on every equipped entry and resets the attachment to EMPTY.
     * Called on death so a totem or other revive path leaves the entity in a clean state.
     */
    public static void clearAllPerks(LivingEntity entity) {
        if (entity.level().isClientSide) return;
        if (!entity.hasData(DTAttachments.EQUIPPED_PERKS.get())) return;
        EquippedPerks eq = entity.getData(DTAttachments.EQUIPPED_PERKS.get());
        for (PerkEntry entry : eq.perks()) {
            Perk perk = Perks.get(entry.perkId());
            if (perk != null) perk.onUnequip(entity, entry);
        }
        entity.setData(DTAttachments.EQUIPPED_PERKS.get(), EquippedPerks.EMPTY);
    }

    // ============================================================================
    //  Internals
    // ============================================================================

    private static List<Perk> collectDefects() {
        List<Perk> defects = new ArrayList<>();
        for (Perk perk : Perks.all()) if (perk.isDefect()) defects.add(perk);
        return defects;
    }
}
