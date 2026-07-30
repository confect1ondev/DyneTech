package com.confect1on.dynetech.gene;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Per-player state for the Godhood gene. Attachment persists across sessions and dimension
 * changes; not copied on death (a true death nukes the whole genome anyway).
 *
 * <p>Timestamps use world {@code getGameTime()} rather than entity {@code tickCount} so a
 * logout mid-regen or mid-vulnerability resumes correctly on login. {@code -1} means the
 * corresponding phase is inactive.
 */
public record GodhoodState(int regenCharges, long regenEndGameTime, long vulnerabilityEndGameTime) {

    public static final int MAX_CHARGES = 10;
    public static final GodhoodState EMPTY = new GodhoodState(0, -1L, -1L);

    public static final Codec<GodhoodState> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.INT.optionalFieldOf("regenCharges", 0).forGetter(GodhoodState::regenCharges),
            Codec.LONG.optionalFieldOf("regenEndGameTime", -1L).forGetter(GodhoodState::regenEndGameTime),
            Codec.LONG.optionalFieldOf("vulnerabilityEndGameTime", -1L).forGetter(GodhoodState::vulnerabilityEndGameTime)
    ).apply(inst, GodhoodState::new));

    public boolean isRegenerating(long now) {
        return regenEndGameTime > now;
    }

    public boolean isVulnerable(long now) {
        return vulnerabilityEndGameTime > now;
    }

    public GodhoodState withCharges(int charges) {
        int clamped = Math.max(0, Math.min(MAX_CHARGES, charges));
        return new GodhoodState(clamped, regenEndGameTime, vulnerabilityEndGameTime);
    }

    public GodhoodState withRegenEnd(long endGameTime) {
        return new GodhoodState(regenCharges, endGameTime, vulnerabilityEndGameTime);
    }

    public GodhoodState withVulnerabilityEnd(long endGameTime) {
        return new GodhoodState(regenCharges, regenEndGameTime, endGameTime);
    }
}
