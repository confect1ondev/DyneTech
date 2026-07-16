package com.confect1on.dynetech.fluid;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.item.DTItems;
import com.confect1on.dynetech.pehkui.PehkuiCompat;

@EventBusSubscriber(modid = DyneTech.MODID)
public final class PymParticleFluidEffects {

    // ~1% scale change per tick: roughly 8s from 1.0 to the grow limit, 9.5s to the shrink limit.
    private static final float STEP = 1.01F;

    private PymParticleFluidEffects() {}

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide || !(entity instanceof LivingEntity)) return;

        if (entity.isInFluidType(DTFluids.SHRINK_PYM_PARTICLES_TYPE.get())) {
            float limit = DTItems.SHRINK_DISC.get().size;
            float next = Math.max(limit, PehkuiCompat.getScale(entity) / STEP);
            PehkuiCompat.setScaleImmediate(entity, next);
        } else if (entity.isInFluidType(DTFluids.ENLARGE_PYM_PARTICLES_TYPE.get())) {
            float limit = DTItems.ENLARGE_DISC.get().size;
            float next = Math.min(limit, PehkuiCompat.getScale(entity) * STEP);
            PehkuiCompat.setScaleImmediate(entity, next);
        }
    }
}
