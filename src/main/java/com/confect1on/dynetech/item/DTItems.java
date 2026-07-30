package com.confect1on.dynetech.item;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.block.DTBlocks;
import com.confect1on.dynetech.fluid.DTFluids;
import com.confect1on.dynetech.fluid.PymParticleBucketItem;

import java.util.function.Supplier;

public class DTItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(DyneTech.MODID);

    public static final DeferredHolder<Item, Item> EMPTY_DISC =
            ITEMS.register("empty_disc", () -> new Item(new Item.Properties()));

    public static final DeferredHolder<Item, PymParticleDiscItem> SHRINK_DISC =
            ITEMS.register("shrink_disc", () -> new PymParticleDiscItem(new Item.Properties().stacksTo(16), 0.15F));

    public static final DeferredHolder<Item, PymParticleDiscItem> ENLARGE_DISC =
            ITEMS.register("enlarge_disc", () -> new PymParticleDiscItem(new Item.Properties().stacksTo(16), 5.0F));

    public static final DeferredHolder<Item, StructureShrinkerRemoteItem> STRUCTURE_SHRINKER_REMOTE =
            ITEMS.register("structure_shrinker_remote", () -> new StructureShrinkerRemoteItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, ShrunkenStructureItem> SHRUNKEN_STRUCTURE =
            ITEMS.register("shrunken_structure", () -> new ShrunkenStructureItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, ShrunkenEntityItem> SHRUNKEN_ENTITY =
            ITEMS.register("shrunken_entity", () -> new ShrunkenEntityItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, TissueCompressionEliminator> TISSUE_COMPRESSION_ELIMINATOR =
            ITEMS.register("tissue_compression_eliminator", () -> new TissueCompressionEliminator(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, BucketItem> SHRINK_PYM_PARTICLE_BUCKET =
            ITEMS.register("shrink_pym_particle_bucket", () -> new PymParticleBucketItem(
                    DTFluids.SHRINK_PYM_PARTICLES.get(),
                    new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));

    public static final DeferredHolder<Item, BucketItem> ENLARGE_PYM_PARTICLE_BUCKET =
            ITEMS.register("enlarge_pym_particle_bucket", () -> new PymParticleBucketItem(
                    DTFluids.ENLARGE_PYM_PARTICLES.get(),
                    new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));

    public static final DeferredHolder<Item, GeneVialItem> GENE_VIAL =
            ITEMS.register("gene_vial", () -> new GeneVialItem(new Item.Properties().stacksTo(16)));

    public static final DeferredHolder<Item, InjectionGunItem> INJECTION_GUN =
            ITEMS.register("injection_gun", () -> new InjectionGunItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, PerkCyclerItem> PERK_CYCLER =
            ITEMS.register("perk_cycler", () -> new PerkCyclerItem(new Item.Properties().stacksTo(1)));

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, DyneTech.MODID);

    public static final Supplier<CreativeModeTab> MAIN_TAB = CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.dynetech"))
            .icon(() -> SHRINK_DISC.get().getDefaultInstance())
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .displayItems((params, output) -> {
                output.accept(EMPTY_DISC.get());
                output.accept(SHRINK_DISC.get());
                output.accept(ENLARGE_DISC.get());
                output.accept(DTBlocks.STRUCTURE_SHRINKER.get());
                output.accept(STRUCTURE_SHRINKER_REMOTE.get());
                output.accept(TISSUE_COMPRESSION_ELIMINATOR.get());
                output.accept(SHRINK_PYM_PARTICLE_BUCKET.get());
                output.accept(ENLARGE_PYM_PARTICLE_BUCKET.get());
                output.accept(GENE_VIAL.get());
                output.accept(INJECTION_GUN.get());
                output.accept(DTBlocks.GENE_SEQUENCER.get());
                output.accept(DTBlocks.GENE_SPLICER.get());
                output.accept(DTBlocks.GENE_MICROSCOPE.get());
                output.accept(DTBlocks.CRYO_PRESERVATOR.get());
                // PerkCycler is a dev-only cycler: reachable via /give but not shown in the tab.
            })
            .build());
}
