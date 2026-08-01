package com.confect1on.dynetech.gene.perks;

import com.confect1on.dynetech.config.DTConfig;
import com.confect1on.dynetech.gene.DTAttachments;
import com.confect1on.dynetech.gene.EquippedPerks;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkCondition;
import com.confect1on.dynetech.gene.PerkEntry;
import com.confect1on.dynetech.gene.Perks;
import com.confect1on.dynetech.gene.ShoalHostState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

import java.util.Optional;
import java.util.Set;

/**
 * Silent stage of Shoal infection. Applied on contact with a Shoal cloud; no HUD indicator, no
 * visual on the host. After the configured incubation time it is replaced by shoal_infection.
 *
 * <p>Death still clears everything via {@code PerkEvents.onDeath}, so dying during incubation
 * cures it (at the cost of the entire genome). The microscope reveals the perk on player-blood
 * samples because the sequencer snapshots the target's equipped perks.
 */
public final class ShoalIncubationPerk implements Perk {

    private final ResourceLocation id;

    public ShoalIncubationPerk(ResourceLocation id) {
        this.id = id;
    }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() {
        return Component.translatable("dynetech.perk.shoal_incubation").withStyle(ChatFormatting.DARK_BLUE);
    }
    @Override public int color() { return 0x2244AA; }
    @Override public Set<PerkCondition> allowedConditions() { return Set.of(PerkCondition.ALWAYS); }

    @Override
    public void onEquip(LivingEntity entity, PerkEntry entry) {
        if (entity.level().isClientSide) return;
        ShoalHostState state = entity.getData(DTAttachments.SHOAL_HOST_STATE.get());
        if (state.incubationStart() < 0L) {
            entity.setData(DTAttachments.SHOAL_HOST_STATE.get(),
                    state.withIncubationStart(entity.level().getDayTime()));
        }
    }

    @Override
    public void onUnequip(LivingEntity entity, PerkEntry entry) {
        if (entity.level().isClientSide) return;
        entity.setData(DTAttachments.SHOAL_HOST_STATE.get(), ShoalHostState.EMPTY);
    }

    @Override
    public void tick(LivingEntity entity, PerkEntry entry) {
        if (!(entity.level() instanceof ServerLevel server)) return;
        // Once-per-second check: nothing about incubation cares about sub-second precision.
        if (server.getGameTime() % 20L != 0L) return;

        // Day time, not game time, so /time add and sleeping through nights advance the
        // incubation like any other in-game-day clock.
        long now = server.getDayTime();
        ShoalHostState state = entity.getData(DTAttachments.SHOAL_HOST_STATE.get());
        if (state.incubationStart() < 0L || state.incubationStart() > now) {
            entity.setData(DTAttachments.SHOAL_HOST_STATE.get(),
                    state.withIncubationStart(now));
            return;
        }
        if (now - state.incubationStart() < DTConfig.SHOAL_INCUBATION_TICKS.get()) return;

        matureIntoInfection(entity, entry);
    }

    private void matureIntoInfection(LivingEntity entity, PerkEntry entry) {
        EquippedPerks eq = entity.getData(DTAttachments.EQUIPPED_PERKS.get());
        eq = eq.remove(this.id);
        PerkEntry infection = new PerkEntry(Perks.SHOAL_INFECTION.getId(), 1.0F,
                entry.donor(), Optional.empty());
        eq = eq.add(infection);
        entity.setData(DTAttachments.EQUIPPED_PERKS.get(), eq);
        Perks.SHOAL_INFECTION.get().onEquip(entity, infection);
    }
}
