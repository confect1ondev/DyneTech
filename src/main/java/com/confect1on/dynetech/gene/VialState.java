package com.confect1on.dynetech.gene;

import com.mojang.serialization.Codec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

public enum VialState implements StringRepresentable {
    /** No contents. Stackable, used as feedstock for the sequencer and splicer. */
    EMPTY("empty"),
    /** A raw draw off a mob - carries the whole species pool, spent by the sequencer. */
    RAW("raw"),
    /** A single perk isolated out of a raw draw. */
    ISOLATED("isolated"),
    /** A splicer-produced injectable. Right-click self or a mob to apply. */
    SERUM("serum"),
    /** Cryo Preservator output. Locked to a single player donor; wrong donor is punished. */
    BOUND_SERUM("bound_serum");

    /** True for anything the injection path should treat as a ready-to-use serum. */
    public boolean isSerum() { return this == SERUM || this == BOUND_SERUM; }

    public static final Codec<VialState> CODEC = StringRepresentable.fromEnum(VialState::values);
    // String-based rather than ordinal - a future reorder of the enum would silently break wire
    // compatibility with existing clients if we keyed on ordinal.
    public static final StreamCodec<RegistryFriendlyByteBuf, VialState> STREAM_CODEC =
            ByteBufCodecs.STRING_UTF8.map(VialState::byName, VialState::getSerializedName).cast();

    private static final java.util.Map<String, VialState> BY_NAME;
    static {
        java.util.Map<String, VialState> map = new java.util.HashMap<>();
        for (VialState s : values()) map.put(s.name, s);
        BY_NAME = java.util.Map.copyOf(map);
    }

    public static VialState byName(String name) {
        VialState s = BY_NAME.get(name);
        if (s == null) throw new IllegalArgumentException("Unknown VialState: " + name);
        return s;
    }

    private final String name;
    VialState(String name) { this.name = name; }
    @Override public String getSerializedName() { return name; }
    public String langKey() { return "dynetech.vial_state." + name; }
}
