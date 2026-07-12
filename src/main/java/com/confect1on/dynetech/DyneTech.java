package com.confect1on.dynetech;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import com.confect1on.dynetech.block.DTBlocks;
import com.confect1on.dynetech.blockentity.DTBlockEntities;
import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.config.DTConfig;
import com.confect1on.dynetech.entity.DTEntityTypes;
import com.confect1on.dynetech.item.DTItems;
import com.confect1on.dynetech.menu.DTMenus;
import com.confect1on.dynetech.network.DTPayloads;

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

        modBus.addListener(DTPayloads::register);

        container.registerConfig(ModConfig.Type.SERVER, DTConfig.SPEC);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
