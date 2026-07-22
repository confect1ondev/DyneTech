package com.confect1on.dynetech.effect;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.confect1on.dynetech.DyneTech;

public final class DTEffects {

    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, DyneTech.MODID);

    /**
     * "Camouflage" is a marker effect - it has no in-game gameplay behavior on its own. The
     * client-side render hook picks up its presence and draws the affected entity translucent
     * instead of hiding it fully, so Chameleon reads as "faded" rather than "gone."
     */
    public static final DeferredHolder<MobEffect, MobEffect> CAMOUFLAGE =
            EFFECTS.register("camouflage", () ->
                    new MobEffect(MobEffectCategory.BENEFICIAL, 0x8899BB) {});

    private DTEffects() {}
}
