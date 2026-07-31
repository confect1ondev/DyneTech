package com.confect1on.dynetech.entity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.confect1on.dynetech.DyneTech;

public class DTEntityTypes {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, DyneTech.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<PymParticleDiscEntity>> PYM_PARTICLE_DISC =
            ENTITY_TYPES.register("pym_particle_disc", () -> EntityType.Builder
                    .<PymParticleDiscEntity>of(PymParticleDiscEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(4)
                    .updateInterval(10)
                    .build("pym_particle_disc"));

    public static final DeferredHolder<EntityType<?>, EntityType<ShrunkenStructureEntity>> SHRUNKEN_STRUCTURE =
            ENTITY_TYPES.register("shrunken_structure", () -> EntityType.Builder
                    .<ShrunkenStructureEntity>of(ShrunkenStructureEntity::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F)
                    .clientTrackingRange(16)
                    .updateInterval(5)
                    .build("shrunken_structure"));

    public static final DeferredHolder<EntityType<?>, EntityType<ShrunkenEntityEntity>> SHRUNKEN_ENTITY =
            ENTITY_TYPES.register("shrunken_entity", () -> EntityType.Builder
                    .<ShrunkenEntityEntity>of(ShrunkenEntityEntity::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F)
                    .clientTrackingRange(16)
                    .updateInterval(5)
                    .build("shrunken_entity"));

    public static final DeferredHolder<EntityType<?>, EntityType<VigilEntity>> VIGIL =
            ENTITY_TYPES.register("vigil", () -> EntityType.Builder
                    .of(VigilEntity::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.95F)
                    .clientTrackingRange(10)
                    .build("vigil"));

    public static final DeferredHolder<EntityType<?>, EntityType<ThrownPhaseDiskEntity>> THROWN_PHASE_DISK =
            ENTITY_TYPES.register("thrown_phase_disk", () -> EntityType.Builder
                    .<ThrownPhaseDiskEntity>of(ThrownPhaseDiskEntity::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F)
                    .clientTrackingRange(4)
                    .updateInterval(10)
                    .build("thrown_phase_disk"));

    // Creature category so vanilla treats it like passive fauna. Natural spawning is out of
    // scope for MVP, so this doesn't affect any mob cap, but the category still drives render
    // distance and vanilla despawn heuristics.
    public static final DeferredHolder<EntityType<?>, EntityType<UsherEntity>> USHER =
            ENTITY_TYPES.register("usher", () -> EntityType.Builder
                    .of(UsherEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.95F)
                    .clientTrackingRange(10)
                    .build("usher"));
}
