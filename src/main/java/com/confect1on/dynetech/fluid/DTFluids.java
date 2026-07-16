package com.confect1on.dynetech.fluid;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.block.DTBlocks;
import com.confect1on.dynetech.config.DTConfig;
import com.confect1on.dynetech.item.DTItems;

public class DTFluids {

    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, DyneTech.MODID);

    public static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(Registries.FLUID, DyneTech.MODID);

    public static final DeferredHolder<FluidType, FluidType> SHRINK_PYM_PARTICLES_TYPE =
            FLUID_TYPES.register("shrink_pym_particles", DTFluids::brandedFluidType);

    public static final DeferredHolder<FluidType, FluidType> ENLARGE_PYM_PARTICLES_TYPE =
            FLUID_TYPES.register("enlarge_pym_particles", DTFluids::brandedFluidType);

    public static final DeferredHolder<Fluid, BaseFlowingFluid.Source> SHRINK_PYM_PARTICLES =
            FLUIDS.register("shrink_pym_particles", () -> new BaseFlowingFluid.Source(shrinkProperties()));

    public static final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> FLOWING_SHRINK_PYM_PARTICLES =
            FLUIDS.register("flowing_shrink_pym_particles", () -> new BaseFlowingFluid.Flowing(shrinkProperties()));

    public static final DeferredHolder<Fluid, BaseFlowingFluid.Source> ENLARGE_PYM_PARTICLES =
            FLUIDS.register("enlarge_pym_particles", () -> new BaseFlowingFluid.Source(enlargeProperties()));

    public static final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> FLOWING_ENLARGE_PYM_PARTICLES =
            FLUIDS.register("flowing_enlarge_pym_particles", () -> new BaseFlowingFluid.Flowing(enlargeProperties()));

    private static FluidType brandedFluidType() {
        FluidType.Properties properties = FluidType.Properties.create()
                .density(1000)
                .viscosity(1000)
                .sound(SoundActions.BUCKET_FILL, SoundEvents.BUCKET_FILL)
                .sound(SoundActions.BUCKET_EMPTY, SoundEvents.BUCKET_EMPTY);
        return new FluidType(properties) {
            @Override
            public Component getDescription() {
                return Component.translatable(this.getDescriptionId(), DTConfig.particleBrand());
            }

            @Override
            public Component getDescription(FluidStack stack) {
                return getDescription();
            }
        };
    }

    private static BaseFlowingFluid.Properties shrinkProperties() {
        return new BaseFlowingFluid.Properties(SHRINK_PYM_PARTICLES_TYPE, SHRINK_PYM_PARTICLES, FLOWING_SHRINK_PYM_PARTICLES)
                .block(DTBlocks.SHRINK_PYM_PARTICLES)
                .bucket(DTItems.SHRINK_PYM_PARTICLE_BUCKET);
    }

    private static BaseFlowingFluid.Properties enlargeProperties() {
        return new BaseFlowingFluid.Properties(ENLARGE_PYM_PARTICLES_TYPE, ENLARGE_PYM_PARTICLES, FLOWING_ENLARGE_PYM_PARTICLES)
                .block(DTBlocks.ENLARGE_PYM_PARTICLES)
                .bucket(DTItems.ENLARGE_PYM_PARTICLE_BUCKET);
    }
}
