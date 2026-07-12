package com.confect1on.dynetech.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.storage.ShrunkenEntityRef;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Lazily materializes a client-only {@link LivingEntity} instance from a {@link ShrunkenEntityRef}
 * for rendering. Same ref → same cached preview, so the item icon and the world carrier share the
 * expensive NBT deserialization.
 *
 * <p>Not thread-safe; call from the client render/main thread only.
 */
@EventBusSubscriber(modid = DyneTech.MODID, value = Dist.CLIENT)
public final class ClientEntityCache {

    private static final int MAX_ENTRIES = 128;

    private static final Map<ShrunkenEntityRef, LivingEntity> CACHE =
            new LinkedHashMap<>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ShrunkenEntityRef, LivingEntity> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private ClientEntityCache() {}

    public static LivingEntity get(ShrunkenEntityRef ref) {
        LivingEntity cached = CACHE.get(ref);
        if (cached != null) return cached;

        Level level = Minecraft.getInstance().level;
        if (level == null) return null;

        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ref.entityType());
        if (type == null) return null;

        // Copy so we can safely mutate (strip UUID, id) without touching the source ref.
        CompoundTag tag = ref.data().copy();
        tag.remove("UUID");
        tag.putString("id", ref.entityType().toString());

        Optional<Entity> opt;
        try {
            opt = EntityType.create(tag, level);
        } catch (Exception e) {
            // Some entities (esp. mods) may explode on client-side deserialization if they touch
            // server-only registries during load. Cache a null miss below to avoid retrying.
            System.err.println("[DyneTech] Failed to build preview for " + ref.entityType() + ": " + e);
            return null;
        }
        if (opt.isEmpty() || !(opt.get() instanceof LivingEntity living)) return null;

        // Force onto ground pose; loaded pose might have swim/fall/etc flags making the preview look wrong.
        living.setYRot(0F);
        living.setXRot(0F);
        living.yBodyRot = 0F;
        living.yHeadRot = 0F;

        CACHE.put(ref, living);
        return living;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        CACHE.clear();
    }

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn e) {
        CACHE.clear();
    }
}
