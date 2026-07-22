package com.confect1on.dynetech.gene.perks;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkCondition;
import com.confect1on.dynetech.gene.PerkEntry;

import java.util.Set;

/** Faster food drain. Player-only; non-player targets are unaffected (they have no food data). */
public final class GluttonyDefect implements Perk {

    private static final int TICK_INTERVAL = 40;

    private final ResourceLocation id;

    public GluttonyDefect(ResourceLocation id) { this.id = id; }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() {
        return Component.translatable("dynetech.perk.gluttony_defect").withStyle(ChatFormatting.DARK_GREEN);
    }
    @Override public boolean isDefect() { return true; }
    @Override public int color() { return 0x557744; }
    @Override public Set<PerkCondition> allowedConditions() { return Set.of(PerkCondition.ALWAYS); }

    @Override public void onEquip(LivingEntity entity, PerkEntry entry) {}
    @Override public void onUnequip(LivingEntity entity, PerkEntry entry) {}

    @Override
    public void tick(LivingEntity entity, PerkEntry entry) {
        if (entry.isDenatured()) return;
        if (entity.tickCount % TICK_INTERVAL != 0) return;
        if (!(entity instanceof Player p)) return;
        p.getFoodData().addExhaustion(0.5F + entry.quality() * 1.0F);
    }
}
