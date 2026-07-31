package com.confect1on.dynetech.dimension;

import com.confect1on.dynetech.DyneTech;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Registry keys for the Usher's pocket dimension. Datapack-defined at
 * {@code data/dynetech/dimension/usher_interior.json} and
 * {@code data/dynetech/dimension_type/usher_interior.json}.
 */
public final class UsherInterior {

    public static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION, DyneTech.id("usher_interior"));

    private UsherInterior() {}
}
