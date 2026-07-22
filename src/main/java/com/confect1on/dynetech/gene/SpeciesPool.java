package com.confect1on.dynetech.gene;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import com.confect1on.dynetech.DyneTech;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Datapack-driven species pool. Reads {@code data/&lt;namespace&gt;/gene/species_pool/&lt;path&gt;.json}
 * where {@code &lt;namespace&gt;:&lt;path&gt;} is the entity type id. A file at
 * {@code data/minecraft/gene/species_pool/cow.json} defines the pool for {@code minecraft:cow}.
 *
 * <p>The file body:
 * <pre>{
 *   "entries": [
 *     {"perk": "dynetech:vitality", "weight": 6},
 *     {"perk": "dynetech:brawn", "weight": 2}
 *   ]
 * }</pre>
 *
 * <p>Modpacks can add or override any entry. Third-party mods can ship their own JSON files
 * under their own namespace. Anything not listed falls back to {@link #DEFAULT_POOL}.
 */
public final class SpeciesPool extends SimpleJsonResourceReloadListener {

    public static final String DATAPACK_FOLDER = "gene/species_pool";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setLenient().create();
    private static final SpeciesPool INSTANCE = new SpeciesPool();

    /** Fallback for entity types with no bespoke pool. A trickle of vitality, nothing exotic. */
    private static final List<Weighted> DEFAULT_POOL =
            List.of(new Weighted(DyneTech.id("vitality"), 3));

    private static volatile Map<EntityType<?>, List<Weighted>> POOLS = Map.of();

    public record Weighted(ResourceLocation perkId, int weight) {}

    private SpeciesPool() { super(GSON, DATAPACK_FOLDER); }

    public static SpeciesPool instance() { return INSTANCE; }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller profiler) {
        Map<EntityType<?>, List<Weighted>> next = new HashMap<>();
        for (var e : map.entrySet()) {
            ResourceLocation typeId = e.getKey();
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(typeId);
            if (type == null) {
                LOGGER.warn("species_pool references unknown entity type {}", typeId);
                continue;
            }
            List<Weighted> pool = parsePool(typeId, e.getValue());
            if (!pool.isEmpty()) next.put(type, pool);
        }
        POOLS = Map.copyOf(next);
    }

    private static List<Weighted> parsePool(ResourceLocation source, JsonElement el) {
        if (!(el instanceof JsonObject obj) || !obj.has("entries")) {
            LOGGER.warn("species_pool {} missing 'entries' array", source);
            return List.of();
        }
        List<Weighted> out = new ArrayList<>();
        for (JsonElement entry : obj.getAsJsonArray("entries")) {
            if (!(entry instanceof JsonObject o)) continue;
            String perk = o.get("perk").getAsString();
            int weight = o.has("weight") ? o.get("weight").getAsInt() : 1;
            ResourceLocation perkId = ResourceLocation.tryParse(perk);
            if (perkId == null || weight <= 0) {
                LOGGER.warn("species_pool {} skipped invalid entry perk={} weight={}", source, perk, weight);
                continue;
            }
            out.add(new Weighted(perkId, weight));
        }
        return List.copyOf(out);
    }

    /**
     * Pool used to sample this entity. Falls back to {@link #DEFAULT_POOL} if the entity type
     * has no bespoke JSON file.
     */
    public static List<Weighted> forEntity(LivingEntity entity) {
        return POOLS.getOrDefault(entity.getType(), DEFAULT_POOL);
    }

    public static List<Weighted> forType(EntityType<?> type) {
        return POOLS.getOrDefault(type, DEFAULT_POOL);
    }

    /** Perk ids for this species. Convenience for external code that wants to inspect a pool. */
    public static Set<ResourceLocation> perkIdsFor(EntityType<?> type) {
        List<Weighted> pool = POOLS.getOrDefault(type, DEFAULT_POOL);
        java.util.LinkedHashSet<ResourceLocation> ids = new java.util.LinkedHashSet<>();
        for (Weighted w : pool) ids.add(w.perkId());
        return Collections.unmodifiableSet(ids);
    }

    /** All entity types with a bespoke pool. Useful for JEI / dev tooling. */
    public static Set<EntityType<?>> registeredSpecies() {
        return Collections.unmodifiableSet(POOLS.keySet());
    }

    /**
     * Test / bootstrap seam: install pools directly without a resource reload. Intended for
     * gametests that need a known pool before the resource pipeline has run.
     */
    public static void installForTests(Map<EntityType<?>, List<Weighted>> pools) {
        POOLS = Map.copyOf(pools);
    }
}
