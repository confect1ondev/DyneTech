package com.confect1on.dynetech.particle;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.confect1on.dynetech.DyneTech;

public final class DTParticles {

    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, DyneTech.MODID);

    /**
     * A single Shoal mote: the same soft luminous blue dot the swarm renderer draws, as a
     * server-spawnable particle. Used wherever loose swarm-matter shows outside a living Shoal:
     * the infection trail, seep conversions, and the Phase Disk extraction stream.
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SHOAL_MOTE =
            PARTICLE_TYPES.register("shoal_mote", () -> new SimpleParticleType(false));

    /**
     * The still, dim cousin of the mote: used to build the ambient haze that hangs around a
     * cluster of Shoal blocks. Softer, longer-lived, mostly stationary. Spawned client-side from
     * animateTick.
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SHOAL_HAZE =
            PARTICLE_TYPES.register("shoal_haze", () -> new SimpleParticleType(false));

    private DTParticles() {}
}
