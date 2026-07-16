package com.confect1on.dynetech;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.capability.wrappers.FluidBucketWrapper;
import com.confect1on.dynetech.block.DTBlocks;
import com.confect1on.dynetech.blockentity.DTBlockEntities;
import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.config.DTConfig;
import com.confect1on.dynetech.entity.DTEntityTypes;
import com.confect1on.dynetech.fluid.DTFluids;
import com.confect1on.dynetech.item.DTItems;
import com.confect1on.dynetech.menu.DTMenus;
import com.confect1on.dynetech.network.DTPayloads;
import com.confect1on.dynetech.sound.DTSounds;

@Mod(DyneTech.MODID)
public class DyneTech {

    public static final String MODID = "dynetech";

    public DyneTech(IEventBus modBus, ModContainer container) {
        DTDataComponents.DATA_COMPONENTS.register(modBus);
        DTItems.ITEMS.register(modBus);
        DTBlocks.BLOCKS.register(modBus);
        DTBlockEntities.BLOCK_ENTITIES.register(modBus);
        DTEntityTypes.ENTITY_TYPES.register(modBus);
        DTMenus.MENUS.register(modBus);
        DTItems.CREATIVE_TABS.register(modBus);
        DTFluids.FLUID_TYPES.register(modBus);
        DTFluids.FLUIDS.register(modBus);
        DTSounds.SOUNDS.register(modBus);

        modBus.addListener(DTPayloads::register);
        modBus.addListener(DyneTech::registerCapabilities);

        container.registerConfig(ModConfig.Type.SERVER, DTConfig.SPEC);
    }

    // Exposes the buckets as item fluid handlers so other mods (tanks, pipes) can drain/fill them.
    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerItem(Capabilities.FluidHandler.ITEM,
                (stack, ctx) -> new FluidBucketWrapper(stack),
                DTItems.SHRINK_PYM_PARTICLE_BUCKET.get(),
                DTItems.ENLARGE_PYM_PARTICLE_BUCKET.get());
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
