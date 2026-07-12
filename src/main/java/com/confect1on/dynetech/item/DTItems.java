package com.confect1on.dynetech.item;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.block.DTBlocks;

import java.util.function.Supplier;

public class DTItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(DyneTech.MODID);

    public static final DeferredHolder<Item, PymParticleDiskItem> SHRINK_DISK =
            ITEMS.register("shrink_disk", () -> new PymParticleDiskItem(new Item.Properties().stacksTo(16), 0.15F));

    public static final DeferredHolder<Item, PymParticleDiskItem> ENLARGE_DISK =
            ITEMS.register("enlarge_disk", () -> new PymParticleDiskItem(new Item.Properties().stacksTo(16), 5.0F));

    public static final DeferredHolder<Item, StructureShrinkerRemoteItem> STRUCTURE_SHRINKER_REMOTE =
            ITEMS.register("structure_shrinker_remote", () -> new StructureShrinkerRemoteItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, ShrunkenStructureItem> SHRUNKEN_STRUCTURE =
            ITEMS.register("shrunken_structure", () -> new ShrunkenStructureItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, ShrunkenEntityItem> SHRUNKEN_ENTITY =
            ITEMS.register("shrunken_entity", () -> new ShrunkenEntityItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, TissueCompressionEliminator> TISSUE_COMPRESSION_ELIMINATOR =
            ITEMS.register("tissue_compression_eliminator", () -> new TissueCompressionEliminator(new Item.Properties().stacksTo(1)));

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, DyneTech.MODID);

    public static final Supplier<CreativeModeTab> MAIN_TAB = CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.dynetech"))
            .icon(() -> SHRINK_DISK.get().getDefaultInstance())
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .displayItems((params, output) -> {
                output.accept(SHRINK_DISK.get());
                output.accept(ENLARGE_DISK.get());
                output.accept(DTBlocks.STRUCTURE_SHRINKER.get());
                output.accept(STRUCTURE_SHRINKER_REMOTE.get());
                output.accept(SHRUNKEN_STRUCTURE.get());
                output.accept(TISSUE_COMPRESSION_ELIMINATOR.get());
                output.accept(SHRUNKEN_ENTITY.get());
            })
            .build());
}
