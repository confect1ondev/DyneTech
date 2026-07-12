package com.confect1on.dynetech.client;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.item.ItemProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.blockentity.DTBlockEntities;
import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.client.renderer.ShrunkenEntityBEWLR;
import com.confect1on.dynetech.client.renderer.ShrunkenEntityEntityRenderer;
import com.confect1on.dynetech.client.renderer.ShrunkenStructureBEWLR;
import com.confect1on.dynetech.client.renderer.ShrunkenStructureEntityRenderer;
import com.confect1on.dynetech.client.renderer.StructureShrinkerBlockEntityRenderer;
import com.confect1on.dynetech.client.screen.StructureShrinkerScreen;
import com.confect1on.dynetech.entity.DTEntityTypes;
import com.confect1on.dynetech.item.DTItems;
import com.confect1on.dynetech.menu.DTMenus;

public class DTClient {

    @EventBusSubscriber(modid = DyneTech.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static class ModBus {

        @SubscribeEvent
        public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(DTEntityTypes.PYM_PARTICLE_DISK.get(), ThrownItemRenderer::new);
            event.registerEntityRenderer(DTEntityTypes.SHRUNKEN_STRUCTURE.get(), ShrunkenStructureEntityRenderer::new);
            event.registerEntityRenderer(DTEntityTypes.SHRUNKEN_ENTITY.get(), ShrunkenEntityEntityRenderer::new);
            event.registerBlockEntityRenderer(DTBlockEntities.STRUCTURE_SHRINKER.get(), StructureShrinkerBlockEntityRenderer::new);
        }

        @SubscribeEvent
        public static void registerScreens(RegisterMenuScreensEvent event) {
            event.register(DTMenus.STRUCTURE_SHRINKER.get(), StructureShrinkerScreen::new);
        }

        // Lazy holder — BEWLR touches Minecraft.getInstance().getBlockEntityRenderDispatcher(),
        // which isn't ready until the client bootstraps.
        private static BlockEntityWithoutLevelRenderer shrunkenStructureBewlr;
        private static BlockEntityWithoutLevelRenderer shrunkenEntityBewlr;

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            // Model override predicate: `dynetech:lethal` returns 1.0 for a lethal-mode TCE so
            // its item model swaps to the red-tip variant declared in the JSON overrides list.
            event.enqueueWork(() -> ItemProperties.register(
                    DTItems.TISSUE_COMPRESSION_ELIMINATOR.get(),
                    DyneTech.id("lethal"),
                    (stack, level, entity, seed) -> {
                        Boolean v = stack.get(DTDataComponents.TCE_LETHAL.get());
                        return v != null && v ? 1.0F : 0.0F;
                    }));
        }

        @SubscribeEvent
        public static void registerClientExtensions(RegisterClientExtensionsEvent event) {
            event.registerItem(new IClientItemExtensions() {
                @Override
                public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                    if (shrunkenStructureBewlr == null) {
                        shrunkenStructureBewlr = new ShrunkenStructureBEWLR();
                    }
                    return shrunkenStructureBewlr;
                }
            }, DTItems.SHRUNKEN_STRUCTURE.get());

            event.registerItem(new IClientItemExtensions() {
                @Override
                public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                    if (shrunkenEntityBewlr == null) {
                        shrunkenEntityBewlr = new ShrunkenEntityBEWLR();
                    }
                    return shrunkenEntityBewlr;
                }
            }, DTItems.SHRUNKEN_ENTITY.get());
        }
    }

    @EventBusSubscriber(modid = DyneTech.MODID, value = Dist.CLIENT)
    public static class GameBus {

        @SubscribeEvent
        public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut e) {
            ClientStructureCache.clear();
        }

        @SubscribeEvent
        public static void onLoggedIn(ClientPlayerNetworkEvent.LoggingIn e) {
            ClientStructureCache.clear();
        }
    }
}
