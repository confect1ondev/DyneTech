package com.confect1on.dynetech.blockentity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.block.DTBlocks;

public class DTBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DyneTech.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StructureShrinkerBlockEntity>> STRUCTURE_SHRINKER =
            BLOCK_ENTITIES.register("structure_shrinker",
                    () -> BlockEntityType.Builder.of(StructureShrinkerBlockEntity::new, DTBlocks.STRUCTURE_SHRINKER.get())
                            .build(null));
}
