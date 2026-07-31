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
import com.confect1on.dynetech.command.DTCommands;
import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.config.DTConfig;
import com.confect1on.dynetech.entity.DTEntityTypes;
import com.confect1on.dynetech.entity.UsherEntity;
import com.confect1on.dynetech.entity.VigilEntity;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import com.confect1on.dynetech.fluid.DTFluids;
import com.confect1on.dynetech.effect.DTEffects;
import com.confect1on.dynetech.gene.DTAttachments;
import com.confect1on.dynetech.gene.GodhoodEvents;
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
        modBus.addListener(DTConfig::onConfigEvent);
        modBus.addListener(DyneTech::onCommonSetup);
        modBus.addListener(DyneTech::onEntityAttributeCreation);

        // Game-bus listeners for gene tick + perk reapply.
        NeoForge.EVENT_BUS.addListener(DyneTech::onEntityTick);
        // Dimension change and login: the attachment survives on disk but attribute modifiers
        // and mob effects on the entity get cleared or drift. Re-run onEquip so modifier-backed
        // perks reattach and refresh timers restart cleanly.
        NeoForge.EVENT_BUS.addListener(DyneTech::onPlayerChangedDimension);
        NeoForge.EVENT_BUS.addListener(DyneTech::onPlayerLoggedIn);
        // Damage/death dispatch for event-driven perks (Bloodthirst, Bane, Cactus, Explosive
        // Death, Ender Blink, Venom Touch). PerkEvents.onDeath also wipes the attachment at the
        // end so a respawning player, or a mob revived by a totem/other mod, starts from a clean
        // genome.
        NeoForge.EVENT_BUS.addListener(PerkEvents::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(PerkEvents::onPostDamage);
        NeoForge.EVENT_BUS.addListener(PerkEvents::onDeath);
        // AbsorptionPerk timer: stamp the last-damaged tick whenever a live entity takes damage.
        NeoForge.EVENT_BUS.addListener(PerkEvents::onDamageStampTimer);
        // Slime Bounce: negates fall damage and launches the host back up on landing.
        NeoForge.EVENT_BUS.addListener(PerkEvents::onLivingFall);
        // Godhood gene: intercept death to trigger regeneration, count essence kills, drive the
        // 5s burn-up + 60s vulnerability timers. Kept as its own listener bundle because Godhood
        // is the only stateful perk and folding it into PerkEvents would double the file's size.
        //
        // HIGHEST priority on the death intercept so it beats PerkEvents.onDeath, which
        // otherwise strips the Godhood perk (via clearAllPerks) before we get to check for it.
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,
                GodhoodEvents::onGodhoodDeath);
        NeoForge.EVENT_BUS.addListener(GodhoodEvents::onEssenceKill);
        NeoForge.EVENT_BUS.addListener(GodhoodEvents::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(GodhoodEvents::onHeal);
        NeoForge.EVENT_BUS.addListener(GodhoodEvents::onEntityTick);
        NeoForge.EVENT_BUS.addListener(GodhoodEvents::onLogin);
        NeoForge.EVENT_BUS.addListener(GodhoodEvents::onDimensionChange);
        NeoForge.EVENT_BUS.addListener(GodhoodEvents::onLogout);
        // HIGHEST priority, registered AFTER onGodhoodDeath so the ordering is
        // onGodhoodDeath -> onGodhoodDeathStopWhispers -> PerkEvents.onDeath. That last step
        // strips the perks via clearAllPerks, so we must read hasGodhood BEFORE it runs.
        // Cancelled events are skipped by default, so a cheated death (onGodhoodDeath sets
        // canceled) also skips the stop, which is what we want.
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,
                GodhoodEvents::onGodhoodDeathStopWhispers);
        // Species pool is a datapack reload listener so pack authors can override any mob's pool.
        NeoForge.EVENT_BUS.addListener(DyneTech::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(DyneTech::onRegisterCommands);

        container.registerConfig(ModConfig.Type.SERVER, DTConfig.SPEC);
    }

    /**
     * No-op today. Perks self-register via DeferredRegister and species pools load from datapack
     * JSON. Kept as a hook in case future setup steps need enqueued work on the main thread.
     */
    private static void onCommonSetup(FMLCommonSetupEvent event) {
    }

    private static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(DTEntityTypes.VIGIL.get(), VigilEntity.createAttributes().build());
        event.put(DTEntityTypes.USHER.get(), UsherEntity.createAttributes().build());
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

    private static void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        DTCommands.register(event.getDispatcher(), event.getBuildContext());
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
