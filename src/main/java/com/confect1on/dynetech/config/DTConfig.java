package com.confect1on.dynetech.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DTConfig {

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<String> PARTICLE_BRAND;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SHRINK_BLACKLIST;

    private static final String DEFAULT_PARTICLE_BRAND = "Pym";
    public static final ModConfigSpec.IntValue SHRINK_MAX_VOLUME;
    public static final ModConfigSpec.IntValue SHRINK_MAX_BLOB_BYTES;
    public static final ModConfigSpec.IntValue SYNC_CHUNK_BYTES;
    public static final ModConfigSpec.IntValue ORPHAN_TTL_SECONDS;
    public static final ModConfigSpec.IntValue GC_INTERVAL_SECONDS;
    public static final ModConfigSpec.IntValue SPLICER_TICKS;
    public static final ModConfigSpec.IntValue SEQUENCER_TICKS;

    private static final List<String> DEFAULT_BLACKLIST = List.of(
            "minecraft:bedrock",
            "minecraft:barrier",
            "minecraft:command_block",
            "minecraft:chain_command_block",
            "minecraft:repeating_command_block",
            "minecraft:end_portal_frame",
            "minecraft:end_portal",
            "minecraft:end_gateway",
            "minecraft:nether_portal",
            "minecraft:structure_block",
            "minecraft:structure_void",
            "minecraft:jigsaw",
            "minecraft:light",
            "minecraft:reinforced_deepslate"
    );

    static {
        Pair<Data, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Data::new);
        PARTICLE_BRAND = pair.getLeft().particleBrand;
        SHRINK_BLACKLIST = pair.getLeft().shrinkBlacklist;
        SHRINK_MAX_VOLUME = pair.getLeft().shrinkMaxVolume;
        SHRINK_MAX_BLOB_BYTES = pair.getLeft().shrinkMaxBlobBytes;
        SYNC_CHUNK_BYTES = pair.getLeft().syncChunkBytes;
        ORPHAN_TTL_SECONDS = pair.getLeft().orphanTtlSeconds;
        GC_INTERVAL_SECONDS = pair.getLeft().gcIntervalSeconds;
        SPLICER_TICKS = pair.getLeft().splicerTicks;
        SEQUENCER_TICKS = pair.getLeft().sequencerTicks;
        SPEC = pair.getRight();
    }

    private DTConfig() {}

    /**
     * The brand name substituted into "... Particle ..." display names (discs, fluids, buckets).
     * Falls back to the default before the server config is loaded/synced (e.g. main menu).
     */
    public static String particleBrand() {
        return SPEC.isLoaded() ? PARTICLE_BRAND.get() : DEFAULT_PARTICLE_BRAND;
    }

    private static volatile Set<Block> cachedBlacklist;

    /** Resolves the current blacklist string list into a Set of Block instances. */
    public static Set<Block> resolveBlacklist() {
        Set<Block> cache = cachedBlacklist;
        if (cache == null) {
            cache = doResolveBlacklist();
            cachedBlacklist = cache;
        }
        return cache;
    }

    private static Set<Block> doResolveBlacklist() {
        Set<Block> blocks = new HashSet<>();
        for (String id : SHRINK_BLACKLIST.get()) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) continue;
            Block block = BuiltInRegistries.BLOCK.get(rl);
            if (block != null) blocks.add(block);
        }
        return blocks;
    }

    private static final class Data {
        final ModConfigSpec.ConfigValue<String> particleBrand;
        final ModConfigSpec.ConfigValue<List<? extends String>> shrinkBlacklist;
        final ModConfigSpec.IntValue shrinkMaxVolume;
        final ModConfigSpec.IntValue shrinkMaxBlobBytes;
        final ModConfigSpec.IntValue syncChunkBytes;
        final ModConfigSpec.IntValue orphanTtlSeconds;
        final ModConfigSpec.IntValue gcIntervalSeconds;
        final ModConfigSpec.IntValue splicerTicks;
        final ModConfigSpec.IntValue sequencerTicks;

        Data(ModConfigSpec.Builder b) {
            b.comment("Server-side settings for DyneTech.").push("server");

            particleBrand = b
                    .comment("Brand name shown in place of \"Pym\" in particle-related display names.",
                            "E.g. \"Custom Name\" turns \"Pym Particle Disc\" into \"Custom Name Particle Disc\".")
                    .define("particle_brand", DEFAULT_PARTICLE_BRAND,
                            o -> o instanceof String s && !s.isBlank());

            shrinkBlacklist = b
                    .comment("Block IDs (e.g. \"minecraft:bedrock\") that the Structure Shrinker refuses to capture.",
                            "If any block in the selection matches, the entire shrink is refused.")
                    .defineListAllowEmpty("shrink_blacklist",
                            DEFAULT_BLACKLIST,
                            () -> "minecraft:bedrock",
                            o -> o instanceof String);

            shrinkMaxVolume = b
                    .comment("Maximum selection volume (blocks = width * height * depth) the Structure Shrinker will capture.",
                            "Selections exceeding this are refused. Default 32768 = a 32x32x32 cube.")
                    .defineInRange("shrink_max_volume", 32768, 1, Integer.MAX_VALUE);

            shrinkMaxBlobBytes = b
                    .comment("Maximum serialized NBT size (bytes) for a single captured structure blob.",
                            "Refuses shrinks whose block-entity payload (chest contents, etc.) exceed this.",
                            "Default 10 MiB. Guards against pathological cases like many double chests full of shulker boxes.")
                    .defineInRange("shrink_max_blob_bytes", 10 * 1024 * 1024, 1024, Integer.MAX_VALUE);

            syncChunkBytes = b
                    .comment("Chunk size (bytes) for streaming a structure blob to the client.",
                            "Kept below Minecraft's ~1 MiB single-packet limit. Larger blobs are split across multiple packets.")
                    .defineInRange("sync_chunk_bytes", 900_000, 4096, 1_000_000);

            orphanTtlSeconds = b
                    .comment("Grace period (seconds) before a server-side structure blob whose item cannot be found is purged.",
                            "Blobs are 'seen' when: their item passes through a player inventory, an open chest inventory, a loaded ShrunkenStructureEntity, or a client render request.",
                            "Default 2592000 = 30 days. Set high enough that items in unloaded chunks get revisited via chunk loads.")
                    .defineInRange("orphan_ttl_seconds", 2_592_000, 60, Integer.MAX_VALUE);

            gcIntervalSeconds = b
                    .comment("How often the orphan sweep runs (seconds). Sweep is cheap (map iteration).")
                    .defineInRange("gc_interval_seconds", 3_600, 60, Integer.MAX_VALUE);

            splicerTicks = b
                    .comment("Ticks the Gene Splicer needs to combine two loaded vials into a result.",
                            "Default 200 = 10 seconds.")
                    .defineInRange("splicer_ticks", 200, 20, 24_000);

            sequencerTicks = b
                    .comment("Ticks the Gene Sequencer needs to isolate one perk from a Raw sample.",
                            "Default 80 = 4 seconds per extraction.")
                    .defineInRange("sequencer_ticks", 80, 20, 24_000);

            b.pop();
        }
    }
}
