package com.confect1on.dynetech.block;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.fluid.DTFluids;
import com.confect1on.dynetech.fluid.PymParticleLiquidBlock;
import com.confect1on.dynetech.item.DTItems;

public class DTBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(DyneTech.MODID);

    public static final DeferredBlock<StructureShrinkerBlock> STRUCTURE_SHRINKER = BLOCKS.register(
            "structure_shrinker",
            () -> new StructureShrinkerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    public static final DeferredHolder<Item, BlockItem> STRUCTURE_SHRINKER_ITEM = DTItems.ITEMS.register(
            "structure_shrinker",
            () -> new BlockItem(STRUCTURE_SHRINKER.get(), new Item.Properties()));

    public static final DeferredBlock<LiquidBlock> SHRINK_PYM_PARTICLES = BLOCKS.register(
            "shrink_pym_particles",
            () -> new PymParticleLiquidBlock(DTFluids.SHRINK_PYM_PARTICLES.get(),
                    BlockBehaviour.Properties.ofFullCopy(Blocks.WATER).mapColor(MapColor.COLOR_RED)));

    public static final DeferredBlock<LiquidBlock> ENLARGE_PYM_PARTICLES = BLOCKS.register(
            "enlarge_pym_particles",
            () -> new PymParticleLiquidBlock(DTFluids.ENLARGE_PYM_PARTICLES.get(),
                    BlockBehaviour.Properties.ofFullCopy(Blocks.WATER).mapColor(MapColor.COLOR_LIGHT_BLUE)));
}
