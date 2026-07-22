package com.confect1on.dynetech.component;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.gene.VialContents;
import com.confect1on.dynetech.storage.ShrunkenEntityRef;
import com.confect1on.dynetech.storage.ShrunkenStructureRef;

public class DTDataComponents {

    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, DyneTech.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ShrunkenStructureRef>> SHRUNKEN_STRUCTURE =
            DATA_COMPONENTS.registerComponentType("shrunken_structure", builder -> builder
                    .persistent(ShrunkenStructureRef.CODEC)
                    .networkSynchronized(ShrunkenStructureRef.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ShrunkenEntityRef>> SHRUNKEN_ENTITY =
            DATA_COMPONENTS.registerComponentType("shrunken_entity", builder -> builder
                    .persistent(ShrunkenEntityRef.CODEC)
                    .networkSynchronized(ShrunkenEntityRef.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> TCE_LETHAL =
            DATA_COMPONENTS.registerComponentType("tce_lethal", builder -> builder
                    .persistent(com.mojang.serialization.Codec.BOOL)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.BOOL));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> CORNER_A =
            DATA_COMPONENTS.registerComponentType("corner_a", builder -> builder
                    .persistent(BlockPos.CODEC)
                    .networkSynchronized(BlockPos.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> CORNER_B =
            DATA_COMPONENTS.registerComponentType("corner_b", builder -> builder
                    .persistent(BlockPos.CODEC)
                    .networkSynchronized(BlockPos.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<VialContents>> VIAL_CONTENTS =
            DATA_COMPONENTS.registerComponentType("vial_contents", builder -> builder
                    .persistent(VialContents.CODEC)
                    .networkSynchronized(VialContents.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<VialContents>> LOADED_VIAL =
            DATA_COMPONENTS.registerComponentType("loaded_vial", builder -> builder
                    .persistent(VialContents.CODEC)
                    .networkSynchronized(VialContents.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> CYCLE_INDEX =
            DATA_COMPONENTS.registerComponentType("cycle_index", builder -> builder
                    .persistent(com.mojang.serialization.Codec.INT)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_INT));
}
