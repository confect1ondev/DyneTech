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
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SHRINK_BLACKLIST;
    public static final ModConfigSpec.IntValue SHRINK_MAX_VOLUME;

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
        SHRINK_BLACKLIST = pair.getLeft().shrinkBlacklist;
        SHRINK_MAX_VOLUME = pair.getLeft().shrinkMaxVolume;
        SPEC = pair.getRight();
    }

    private DTConfig() {}

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
        final ModConfigSpec.ConfigValue<List<? extends String>> shrinkBlacklist;
        final ModConfigSpec.IntValue shrinkMaxVolume;

        Data(ModConfigSpec.Builder b) {
            b.comment("Server-side settings for DyneTech.").push("server");

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

            b.pop();
        }
    }
}
