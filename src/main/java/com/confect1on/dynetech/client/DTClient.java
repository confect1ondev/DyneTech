package com.confect1on.dynetech.client;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.item.ItemProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.Perks;
import com.confect1on.dynetech.gene.VialContents;
import com.confect1on.dynetech.gene.VialState;
import com.confect1on.dynetech.item.GeneVialItem;
import com.confect1on.dynetech.item.InjectionGunItem;
import com.confect1on.dynetech.blockentity.DTBlockEntities;
import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.client.renderer.PymParticleDiscRenderer;
import com.confect1on.dynetech.client.renderer.ShrunkenEntityBEWLR;
import com.confect1on.dynetech.client.renderer.ShrunkenEntityEntityRenderer;
import com.confect1on.dynetech.client.renderer.ShoalMoteParticle;
import com.confect1on.dynetech.client.renderer.ShoalMoteTexture;
import com.confect1on.dynetech.client.renderer.ShoalRenderer;
import com.confect1on.dynetech.client.renderer.ShrunkenStructureBEWLR;
import com.confect1on.dynetech.client.renderer.ShrunkenStructureEntityRenderer;
import com.confect1on.dynetech.client.renderer.StructureShrinkerBlockEntityRenderer;
import com.confect1on.dynetech.client.renderer.SpinningDiscRenderer;
import com.confect1on.dynetech.client.renderer.UsherModel;
import com.confect1on.dynetech.client.renderer.UsherRenderer;
import com.confect1on.dynetech.client.renderer.VigilModel;
import com.confect1on.dynetech.client.renderer.VigilRenderer;
import com.confect1on.dynetech.entity.ThrownPhaseDiskEntity;
import net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterLayerDefinitions;
import com.confect1on.dynetech.client.screen.CryoPreservatorScreen;
import com.confect1on.dynetech.client.screen.GeneMicroscopeScreen;
import com.confect1on.dynetech.client.screen.GeneSequencerScreen;
import com.confect1on.dynetech.client.screen.GeneSplicerScreen;
import com.confect1on.dynetech.client.screen.StructureShrinkerScreen;
import com.confect1on.dynetech.entity.DTEntityTypes;
import com.confect1on.dynetech.fluid.DTFluids;
import com.confect1on.dynetech.item.DTItems;
import com.confect1on.dynetech.menu.DTMenus;
import com.confect1on.dynetech.network.DTPayloads;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public class DTClient {

    @EventBusSubscriber(modid = DyneTech.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static class ModBus {

        @SubscribeEvent
        public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(DTEntityTypes.PYM_PARTICLE_DISC.get(), PymParticleDiscRenderer::new);
            event.registerEntityRenderer(DTEntityTypes.SHRUNKEN_STRUCTURE.get(), ShrunkenStructureEntityRenderer::new);
            event.registerEntityRenderer(DTEntityTypes.SHRUNKEN_ENTITY.get(), ShrunkenEntityEntityRenderer::new);
            event.registerEntityRenderer(DTEntityTypes.VIGIL.get(), VigilRenderer::new);
            event.registerEntityRenderer(DTEntityTypes.USHER.get(), UsherRenderer::new);
            event.registerEntityRenderer(DTEntityTypes.SHOAL.get(), ShoalRenderer::new);
            event.registerEntityRenderer(DTEntityTypes.THROWN_PHASE_DISK.get(),
                    ctx -> new SpinningDiscRenderer<ThrownPhaseDiskEntity>(ctx));
            event.registerBlockEntityRenderer(DTBlockEntities.STRUCTURE_SHRINKER.get(), StructureShrinkerBlockEntityRenderer::new);
        }

        @SubscribeEvent
        public static void registerLayerDefinitions(RegisterLayerDefinitions event) {
            event.registerLayerDefinition(VigilRenderer.MODEL_LAYER, () -> VigilModel.createBodyLayer(false));
            event.registerLayerDefinition(VigilRenderer.MODEL_LAYER_SLIM, () -> VigilModel.createBodyLayer(true));
            event.registerLayerDefinition(UsherRenderer.MODEL_LAYER, UsherModel::createBodyLayer);
        }

        @SubscribeEvent
        public static void registerParticleProviders(
                net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent event) {
            event.registerSpecial(
                    com.confect1on.dynetech.particle.DTParticles.SHOAL_MOTE.get(),
                    new ShoalMoteParticle.Provider());
        }

        @SubscribeEvent
        public static void registerScreens(RegisterMenuScreensEvent event) {
            event.register(DTMenus.STRUCTURE_SHRINKER.get(), StructureShrinkerScreen::new);
            event.register(DTMenus.GENE_SEQUENCER.get(), GeneSequencerScreen::new);
            event.register(DTMenus.GENE_SPLICER.get(), GeneSplicerScreen::new);
            event.register(DTMenus.GENE_MICROSCOPE.get(), GeneMicroscopeScreen::new);
            event.register(DTMenus.CRYO_PRESERVATOR.get(), CryoPreservatorScreen::new);
        }

        // Lazy holder — BEWLR touches Minecraft.getInstance().getBlockEntityRenderDispatcher(),
        // which isn't ready until the client bootstraps.
        private static BlockEntityWithoutLevelRenderer shrunkenStructureBewlr;
        private static BlockEntityWithoutLevelRenderer shrunkenEntityBewlr;

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> {
                // Register the runtime-generated Shoal mote sprite before anything can request it.
                ShoalMoteTexture.ensureRegistered();

                // Hook the GPU Shoal manager into Veil's render-stage and resource-free events.
                com.confect1on.dynetech.client.renderer.shoal.ShoalSwarmManager.init();

                // Lethal-mode TCE swaps to a red-tip model via this override.
                ItemProperties.register(
                        DTItems.TISSUE_COMPRESSION_ELIMINATOR.get(),
                        DyneTech.id("lethal"),
                        (stack, level, entity, seed) -> {
                            Boolean v = stack.get(DTDataComponents.TCE_LETHAL.get());
                            return v != null && v ? 1.0F : 0.0F;
                        });

                // Gene Vial variant: continuous 0-1 float that overrides route to
                //   0.15 = blood, 0.25 = low-quality helix, 0.45 = mid, 0.75 = high.
                ItemProperties.register(
                        DTItems.GENE_VIAL.get(),
                        DyneTech.id("variant"),
                        (stack, level, entity, seed) -> vialVariant(GeneVialItem.getContents(stack)));

                // Injection Gun chamber: 0.0 when nothing's loaded (base model with an empty
                // chamber slot), 0.1 when any vial is loaded (loaded model that adds a tinted
                // overlay in the chamber).
                ItemProperties.register(
                        DTItems.INJECTION_GUN.get(),
                        DyneTech.id("chamber"),
                        (stack, level, entity, seed) ->
                                InjectionGunItem.hasLoadedComponent(stack) ? 0.1F : 0.0F);
            });
        }

        private static float vialVariant(VialContents c) {
            return switch (c.state()) {
                case EMPTY -> 0.0F;
                case RAW -> 0.2F;
                case ISOLATED -> {
                    float q = c.maxQuality();
                    if (q >= 0.75F) yield 0.9F;
                    if (q >= 0.45F) yield 0.6F;
                    yield 0.3F;
                }
                // Serum + bound serum share the 3-layer model; the icy tint on the bound variant
                // is driven by the item color handler below, not by a separate predicate value.
                case SERUM, BOUND_SERUM -> 0.99F;
            };
        }

        /**
         * Layer0 (glass base) is never tinted so the vial shape always reads correctly.
         * Layer1 (helix) is tinted by the first-carried perk's declared color — this is
         * the vanilla-potion-style recolor path the user asked for.
         */
        @SubscribeEvent
        public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
            event.register((stack, tintIndex) -> {
                VialContents c = GeneVialItem.getContents(stack);
                if (tintIndex == 0) return 0xFFFFFFFF;
                if (tintIndex == 1) {
                    // Layer1 is either the isolated helix (isolated model) or the blood fill
                    // (raw / serum / bound). Bound serums render with a pale cyan cast so they
                    // read as frozen at a glance.
                    if (c.state() == VialState.BOUND_SERUM) return 0xFFAEE6F7;
                    if (c.state() == VialState.RAW || c.state() == VialState.SERUM) return 0xFFFFFFFF;
                    if (c.perks().isEmpty()) return 0xFFFFFFFF;
                    Perk perk = Perks.get(c.perks().get(0).perkId());
                    return perk != null ? (0xFF000000 | perk.color()) : 0xFFFFFFFF;
                }
                if (tintIndex == 2) {
                    // Layer2 is the helix overlay on the serum-shaped model. Bound serums use it
                    // too so the donor's gene color still shows through.
                    if (!c.state().isSerum() || c.perks().isEmpty()) return 0xFFFFFFFF;
                    Perk perk = Perks.get(c.perks().get(0).perkId());
                    return perk != null ? (0xFF000000 | perk.color()) : 0xFFFFFFFF;
                }
                return 0xFFFFFFFF;
            }, DTItems.GENE_VIAL.get());

            // Injection gun chamber overlay: layer1 gets tinted by the loaded vial's state.
            event.register((stack, tintIndex) -> {
                if (tintIndex != 1) return 0xFFFFFFFF;
                VialContents c = InjectionGunItem.getLoaded(stack);
                return switch (c.state()) {
                    case EMPTY -> 0xFFA8DBE8;                  // glassy blue for an empty vial
                    case RAW -> 0xFFB02020;                    // blood red
                    case BOUND_SERUM -> 0xFFAEE6F7;            // frozen cast
                    case ISOLATED, SERUM -> {
                        if (c.perks().isEmpty()) yield 0xFFCCCCCC;
                        Perk perk = Perks.get(c.perks().get(0).perkId());
                        yield perk != null ? (0xFF000000 | perk.color()) : 0xFFCCCCCC;
                    }
                };
            }, DTItems.INJECTION_GUN.get());
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

            event.registerFluidType(pymParticleFluidExtensions(0xFFC83D3D), DTFluids.SHRINK_PYM_PARTICLES_TYPE.get());
            event.registerFluidType(pymParticleFluidExtensions(0xFF3C99DB), DTFluids.ENLARGE_PYM_PARTICLES_TYPE.get());
        }

        // Reuses the vanilla water sprites; the tint gives each particle fluid its disc color.
        private static IClientFluidTypeExtensions pymParticleFluidExtensions(int tint) {
            return new IClientFluidTypeExtensions() {
                @Override
                public ResourceLocation getStillTexture() {
                    return ResourceLocation.withDefaultNamespace("block/water_still");
                }

                @Override
                public ResourceLocation getFlowingTexture() {
                    return ResourceLocation.withDefaultNamespace("block/water_flow");
                }

                @Override
                public ResourceLocation getOverlayTexture() {
                    return ResourceLocation.withDefaultNamespace("block/water_overlay");
                }

                @Override
                public int getTintColor() {
                    return tint;
                }
            };
        }
    }

    @EventBusSubscriber(modid = DyneTech.MODID, value = Dist.CLIENT)
    public static class GameBus {

        @SubscribeEvent
        public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut e) {
            ClientStructureCache.clear();
            // Drop the Shoal GPU buffers on the render thread; the periodic sweep only runs
            // while swarms are actively rendering, which they no longer are after a disconnect.
            net.minecraft.client.Minecraft.getInstance().execute(
                    com.confect1on.dynetech.client.renderer.shoal.ShoalSwarmManager::freeAll);
        }

        @SubscribeEvent
        public static void onLoggedIn(ClientPlayerNetworkEvent.LoggingIn e) {
            ClientStructureCache.clear();
        }

        /**
         * "Sneak + left-click on nothing" with a loaded Injection Gun sends a payload the server
         * handles by calling {@link InjectionGunItem#fireAtSelf}. Vanilla doesn't ship an
         * interact packet for empty-swings, so the client has to signal server intent explicitly.
         */
        @SubscribeEvent
        public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty e) {
            var player = e.getEntity();
            if (!player.isShiftKeyDown()) return;
            for (InteractionHand hand : InteractionHand.values()) {
                var held = player.getItemInHand(hand);
                if (held.getItem() instanceof InjectionGunItem && InjectionGunItem.isFireable(held)) {
                    PacketDistributor.sendToServer(new DTPayloads.InjectSelfWithGun());
                    return;
                }
            }
        }
    }
}
