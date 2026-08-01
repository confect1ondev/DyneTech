package com.confect1on.dynetech.gene;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Per-player bookkeeping for Shoal infection. Only carriers need this record; players who have
 * never been touched by a Shoal skip the attachment via the fast path in the tick loop.
 *
 * @param incubationStart tick the incubation gene was applied. -1 while unset.
 * @param lastSeepTick    last game tick a seep conversion fired for this carrier.
 * @param seepBudgetUsed  seep conversions performed within the current in-game day.
 * @param seepDay         in-game day the running budget was accumulated against.
 */
public record ShoalHostState(long incubationStart, long lastSeepTick,
                             int seepBudgetUsed, long seepDay) {

    public static final ShoalHostState EMPTY = new ShoalHostState(-1L, -1L, 0, -1L);

    public static final Codec<ShoalHostState> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.LONG.fieldOf("incubation_start").forGetter(ShoalHostState::incubationStart),
            Codec.LONG.fieldOf("last_seep_tick").forGetter(ShoalHostState::lastSeepTick),
            Codec.INT.fieldOf("seep_budget_used").forGetter(ShoalHostState::seepBudgetUsed),
            Codec.LONG.fieldOf("seep_day").forGetter(ShoalHostState::seepDay)
    ).apply(inst, ShoalHostState::new));

    public ShoalHostState withIncubationStart(long tick) {
        return new ShoalHostState(tick, this.lastSeepTick, this.seepBudgetUsed, this.seepDay);
    }

    public ShoalHostState withSeepTick(long tick, int budgetUsed, long day) {
        return new ShoalHostState(this.incubationStart, tick, budgetUsed, day);
    }
}
