package com.confect1on.dynetech.storage;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.config.DTConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps {@link ShrunkenStructureStorage#lastSeen} refreshed for every extant reference we can
 * observe, and periodically sweeps blobs whose refs have gone stale (item destroyed, entity
 * despawned, chunk long-unloaded).
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@link PlayerEvent.PlayerLoggedInEvent} — main inventory, offhand, armor, ender chest.
 *   <li>{@link net.neoforged.neoforge.event.tick.ServerTickEvent.Post} — periodic {@code sweep}.
 * </ul>
 *
 * <p>ShrunkenStructureEntity refreshes itself in its load hook (see the entity class).
 * Client render requests refresh via {@link ShrunkenStructureStorage#get(UUID)}.
 */
@EventBusSubscriber(modid = DyneTech.MODID)
public final class ShrunkenStorageMaintenance {

    private static int tickCounter = 0;

    private ShrunkenStorageMaintenance() {}

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        List<UUID> found = new ArrayList<>();
        Inventory inv = sp.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) collectRefs(inv.getItem(i), found);
        Container ender = sp.getEnderChestInventory();
        for (int i = 0; i < ender.getContainerSize(); i++) collectRefs(ender.getItem(i), found);
        if (!found.isEmpty()) {
            ShrunkenStructureStorage.get(sp.getServer()).touchAll(found);
        }
    }

    // NOTE: ChunkEvent.Load handler removed — even with server.execute deferral it appeared to
    // interact badly with the integrated-server chunk promotion pipeline and froze world loads.
    // Coverage for items in unloaded chunks past TTL is now handled via TTL config tuning and
    // (future) a periodic full-world scan running well after startup. Player-inventory items
    // and shrunken-structure entities in loaded chunks are still covered by login-scan and
    // client-request touches.

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int interval = DTConfig.GC_INTERVAL_SECONDS.get();
        // 20 ticks/second; guard against pathological configs.
        int intervalTicks = Math.max(20, interval * 20);
        if (++tickCounter < intervalTicks) return;
        tickCounter = 0;
        MinecraftServer server = event.getServer();
        ShrunkenStructureStorage.get(server).sweep(DTConfig.ORPHAN_TTL_SECONDS.get());
    }

    /**
     * Descends into the stack and any nested container/bundle components, appending every
     * shrunken-structure UUID it encounters. Bundles and shulkers frequently hold our items so
     * we can't stop at the top level.
     */
    private static void collectRefs(ItemStack stack, List<UUID> out) {
        collectRefs(stack, out, new HashSet<>(), 0);
    }

    private static void collectRefs(ItemStack stack, List<UUID> out, Set<Object> guard, int depth) {
        if (stack.isEmpty() || depth > 8) return;
        ShrunkenStructureRef ref = stack.get(DTDataComponents.SHRUNKEN_STRUCTURE.get());
        if (ref != null) out.add(ref.id());
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null && guard.add(container)) {
            for (ItemStack nested : container.nonEmptyItems()) {
                collectRefs(nested, out, guard, depth + 1);
            }
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null && guard.add(bundle)) {
            bundle.itemCopyStream().forEach(n -> collectRefs(n, out, guard, depth + 1));
        }
    }
}
