package com.confect1on.dynetech;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.fluids.capability.wrappers.FluidBucketWrapper;
import com.confect1on.dynetech.block.DTBlocks;
import com.confect1on.dynetech.blockentity.DTBlockEntities;
import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.config.DTConfig;
import com.confect1on.dynetech.entity.DTEntityTypes;
import com.confect1on.dynetech.fluid.DTFluids;
import com.confect1on.dynetech.effect.DTEffects;
import com.confect1on.dynetech.gene.DTAttachments;
import com.confect1on.dynetech.gene.PerkLifecycle;
import com.confect1on.dynetech.gene.PerkEvents;
import com.confect1on.dynetech.gene.Perks;
import com.confect1on.dynetech.gene.SpeciesPool;
import com.confect1on.dynetech.item.DTItems;
import com.confect1on.dynetech.menu.DTMenus;
import com.confect1on.dynetech.network.DTPayloads;
import com.confect1on.dynetech.sound.DTSounds;
import net.minecraft.world.entity.LivingEntity;

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
        DTAttachments.ATTACHMENT_TYPES.register(modBus);
        DTEffects.EFFECTS.register(modBus);
        Perks.REGISTRAR.register(modBus);

        modBus.addListener(DTPayloads::register);
        modBus.addListener(DyneTech::registerCapabilities);
        modBus.addListener(DyneTech::onCommonSetup);

        // Game-bus listeners for gene tick + perk reapply.
        NeoForge.EVENT_BUS.addListener(DyneTech::onEntityTick);
        // Dimension change and login: the attachment survives on disk but attribute modifiers
        // and mob effects on the entity get cleared or drift. Re-run onEquip so modifier-backed
        // perks reattach and refresh timers restart cleanly.
        NeoForge.EVENT_BUS.addListener(DyneTech::onPlayerChangedDimension);
        NeoForge.EVENT_BUS.addListener(DyneTech::onPlayerLoggedIn);
        // Damage/death dispatch for event-driven perks (Bloodthirst, Bane, Cactus, Explosive Death).
        // PerkEvents.onDeath also wipes the attachment at the end so a respawning player, or a
        // mob revived by a totem/other mod, starts from a clean genome.
        NeoForge.EVENT_BUS.addListener(PerkEvents::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(PerkEvents::onPostDamage);
        NeoForge.EVENT_BUS.addListener(PerkEvents::onDeath);
        // AbsorptionPerk timer: stamp the last-damaged tick whenever a live entity takes damage.
        NeoForge.EVENT_BUS.addListener(PerkEvents::onDamageStampTimer);
        // Species pool is a datapack reload listener so pack authors can override any mob's pool.
        NeoForge.EVENT_BUS.addListener(DyneTech::onAddReloadListeners);

        container.registerConfig(ModConfig.Type.SERVER, DTConfig.SPEC);
    }

    /**
     * No-op today. Perks self-register via DeferredRegister and species pools load from datapack
     * JSON. Kept as a hook in case future setup steps need enqueued work on the main thread.
     */
    private static void onCommonSetup(FMLCommonSetupEvent event) {
    }

    private static void onEntityTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof LivingEntity living) {
            PerkLifecycle.tickPerks(living);
        }
    }

    private static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        PerkLifecycle.reapplyAll(event.getEntity());
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        PerkLifecycle.reapplyAll(event.getEntity());
    }

    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(SpeciesPool.instance());
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
