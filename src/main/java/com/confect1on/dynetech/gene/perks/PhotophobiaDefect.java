package com.confect1on.dynetech.gene.perks;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkCondition;
import com.confect1on.dynetech.gene.PerkEntry;

import java.util.Set;

/**
 * Sun sickness: takes damage while in direct sunlight. Head-slot pumpkin blocks the effect the
 * same way it lets zombies survive daylight in vanilla - this is intentionally the standard
 * escape hatch so players have a way to bandage a bad injection.
 */
public final class PhotophobiaDefect implements Perk {

    private static final int TICK_INTERVAL = 40;
    private static final float DAMAGE_AMOUNT = 1.0F;

    private final ResourceLocation id;

    public PhotophobiaDefect(ResourceLocation id) { this.id = id; }

    @Override public ResourceLocation id() { return id; }
    @Override public Component displayName() {
        return Component.translatable("dynetech.perk.photophobia_defect").withStyle(ChatFormatting.YELLOW);
    }
    @Override public boolean isDefect() { return true; }
    @Override public int color() { return 0xFFFF44; }
    @Override public Set<PerkCondition> allowedConditions() { return Set.of(PerkCondition.ALWAYS); }

    @Override public void onEquip(LivingEntity entity, PerkEntry entry) {}
    @Override public void onUnequip(LivingEntity entity, PerkEntry entry) {}

    @Override
    public void tick(LivingEntity entity, PerkEntry entry) {
        if (entry.isDenatured()) return;
        if (entity.tickCount % TICK_INTERVAL != 0) return;
        Level level = entity.level();
        if (!level.isDay() || !level.canSeeSky(entity.blockPosition())) return;

        ItemStack head = entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
        if (head.is(Items.CARVED_PUMPKIN)) return;

        entity.hurt(entity.damageSources().onFire(), DAMAGE_AMOUNT);
    }
}
