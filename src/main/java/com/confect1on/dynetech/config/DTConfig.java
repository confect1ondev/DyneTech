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
    public static final ModConfigSpec.IntValue CRYO_TICKS;
    public static final ModConfigSpec.IntValue PLAYER_BLOOD_TTL_TICKS;
    public static final ModConfigSpec.IntValue CRYO_ICE_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue WHISPER_TICK_INTERVAL;
    public static final ModConfigSpec.DoubleValue WHISPER_BASE_RADIUS;
    public static final ModConfigSpec.DoubleValue WHISPER_RADIUS_PER_CHARGE;
    public static final ModConfigSpec.DoubleValue WHISPER_MAX_VOLUME;
    public static final ModConfigSpec.DoubleValue WHISPER_POSITION_JITTER;

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
        CRYO_TICKS = pair.getLeft().cryoTicks;
        PLAYER_BLOOD_TTL_TICKS = pair.getLeft().playerBloodTtlTicks;
        CRYO_ICE_INTERVAL_TICKS = pair.getLeft().cryoIceIntervalTicks;
        WHISPER_TICK_INTERVAL = pair.getLeft().whisperTickInterval;
        WHISPER_BASE_RADIUS = pair.getLeft().whisperBaseRadius;
        WHISPER_RADIUS_PER_CHARGE = pair.getLeft().whisperRadiusPerCharge;
        WHISPER_MAX_VOLUME = pair.getLeft().whisperMaxVolume;
        WHISPER_POSITION_JITTER = pair.getLeft().whisperPositionJitter;
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
        final ModConfigSpec.IntValue cryoTicks;
        final ModConfigSpec.IntValue playerBloodTtlTicks;
        final ModConfigSpec.IntValue cryoIceIntervalTicks;
        final ModConfigSpec.IntValue whisperTickInterval;
        final ModConfigSpec.DoubleValue whisperBaseRadius;
        final ModConfigSpec.DoubleValue whisperRadiusPerCharge;
        final ModConfigSpec.DoubleValue whisperMaxVolume;
        final ModConfigSpec.DoubleValue whisperPositionJitter;

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

            cryoTicks = b
                    .comment("Ticks the Cryo Preservator needs to press one Bound Serum from the",
                            "template vial. Ice is not spent per dose; the same coolant that keeps",
                            "the sample frozen powers extraction. Default 100 = 5 seconds per dose.")
                    .defineInRange("cryo_ticks", 100, 20, 24_000);

            playerBloodTtlTicks = b
                    .comment("Ticks a freshly-drawn player-blood vial stays viable outside the Cryo",
                            "Preservator before it degrades. Default 6000 = 5 minutes.")
                    .defineInRange("player_blood_ttl_ticks", 6000, 200, 24_000_000);

            cryoIceIntervalTicks = b
                    .comment("Ticks of active preservation the Cryo Preservator gets from one ice.",
                            "Every time the counter hits this value, one ice is consumed. Default",
                            "144000 = 2 hours of preservation per ice.")
                    .defineInRange("cryo_ice_interval_ticks", 144_000, 20, 24_000_000);

            b.comment("Godhood whisper detection.").push("godhood_whispers");

            whisperTickInterval = b
                    .comment("How often the whisper broadcast runs (ticks). 40 = 2 seconds.",
                            "Longer intervals reduce overlapping voices; shorter give a denser choir.",
                            "Also determines how often the positional jitter is re-rolled.")
                    .defineInRange("tick_interval", 40, 20, 400);

            whisperBaseRadius = b
                    .comment("Base detection radius (blocks) at 1 charge. Actual radius per emission =",
                            "base * (0.5 + charges * per_charge). Zero charges emit nothing.")
                    .defineInRange("base_radius", 64.0, 8.0, 512.0);

            whisperRadiusPerCharge = b
                    .comment("Per-charge multiplier added to the radius scale. Default 0.1: at 10 charges",
                            "the audible radius is base * 1.5.")
                    .defineInRange("radius_per_charge", 0.1, 0.0, 1.0);

            whisperMaxVolume = b
                    .comment("Source volume at 10 charges. Volume scales linearly from 0 (silent) to this",
                            "at max charges. Vanilla treats volume>1 as an extended audible range.")
                    .defineInRange("max_volume", 6.0, 0.1, 16.0);

            whisperPositionJitter = b
                    .comment("Maximum random offset (blocks) applied to the reported emitter position on",
                            "each broadcast, in each axis. Prevents pinpointing by turning in place.")
                    .defineInRange("position_jitter", 3.0, 0.0, 32.0);

            b.pop();

            b.pop();
        }
    }
}
