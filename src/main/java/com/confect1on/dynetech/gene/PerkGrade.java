package com.confect1on.dynetech.gene;

import net.minecraft.ChatFormatting;

/**
 * Discrete display tier a perk's continuous quality float lands in.
 * Renamed and reordered vs. TFW's QualityRating so the two systems can't be confused at a glance.
 *
 * <p>Also owns the single source of truth for how quality maps to magnitude. Denatured entries
 * express nothing; everything at CORRUPTED or above applies at least {@link #MIN_MULTIPLIER}
 * of the perk's full-quality value. Consumers should call {@link #effectiveMultiplier(float)}
 * rather than hand-rolling their own floor.
 */
public enum PerkGrade {
    PRISTINE (1.00F, 0xFFFFFF, ChatFormatting.WHITE),
    EXCELLENT(0.80F, 0x55FF55, ChatFormatting.GREEN),
    STABLE   (0.60F, 0x55FFFF, ChatFormatting.AQUA),
    VIABLE   (0.40F, 0xFFFF55, ChatFormatting.YELLOW),
    DEGRADED (0.20F, 0xFFAA00, ChatFormatting.GOLD),
    CORRUPTED(0.05F, 0xFF5555, ChatFormatting.RED),
    DENATURED(0.00F, 0x555555, ChatFormatting.DARK_GRAY);

    /** Effect strength floor for anything above DENATURED. Quality 0.1 still applies at 0.4x. */
    public static final float MIN_MULTIPLIER = 0.4F;

    private final float minQuality;
    private final int color;
    private final ChatFormatting formatting;

    PerkGrade(float minQuality, int color, ChatFormatting formatting) {
        this.minQuality = minQuality;
        this.color = color;
        this.formatting = formatting;
    }

    public float minQuality() { return minQuality; }
    public int color() { return color; }
    public ChatFormatting formatting() { return formatting; }
    public String langKey() { return "dynetech.perk_grade." + name().toLowerCase(); }

    /** Highest tier whose {@link #minQuality} is <= q. Never null (DENATURED at 0.0 catches all). */
    public static PerkGrade fromQuality(float q) {
        PerkGrade best = DENATURED;
        for (PerkGrade g : values()) {
            if (q >= g.minQuality && g.minQuality >= best.minQuality) best = g;
        }
        return best;
    }

    /** True if a quality falls in the DENATURED band (below CORRUPTED's floor). */
    public static boolean isDenatured(float quality) {
        return quality < CORRUPTED.minQuality;
    }

    /**
     * Multiplier applied to a perk's full-quality magnitude. Zero for denatured entries; for
     * anything above the CORRUPTED floor, at least {@link #MIN_MULTIPLIER} so low-quality perks
     * still land in a way the player can feel.
     */
    public static float effectiveMultiplier(float quality) {
        if (isDenatured(quality)) return 0.0F;
        return Math.max(MIN_MULTIPLIER, quality);
    }
}
