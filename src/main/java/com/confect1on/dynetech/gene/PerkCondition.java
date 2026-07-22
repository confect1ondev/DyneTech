package com.confect1on.dynetech.gene;

import com.mojang.serialization.Codec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Contextual gates that decide whether a perk is currently expressing.
 *
 * <p>A perk with a condition is toggled on and off by the tick loop as the entity moves through
 * matching / non-matching state. Concrete perks declare their own <em>allowed</em> subset via
 * {@link Perk#allowedConditions()} so that curated conditions can never negate the perk's core
 * purpose - e.g. Gills never gets a "swim only" condition because that would be redundant, and
 * Fireproof never gets "not on fire" because that would negate it.
 */
public enum PerkCondition implements StringRepresentable {
    ALWAYS("always"),
    SNEAKING("sneaking"),
    SPRINTING("sprinting"),
    NOT_MOVING("not_moving"),
    FALLING("falling"),
    ON_FIRE("on_fire"),
    LOW_HEALTH("low_health"),
    HIGH_HEALTH("high_health"),
    DAY("day"),
    NIGHT("night"),
    USING_ITEM("using_item"),
    ALONE("alone");

    public static final Codec<PerkCondition> CODEC = StringRepresentable.fromEnum(PerkCondition::values);

    // String-based rather than ordinal - a future reorder of the enum would silently break wire
    // compatibility with existing clients if we keyed on ordinal.
    public static final StreamCodec<RegistryFriendlyByteBuf, PerkCondition> STREAM_CODEC =
            ByteBufCodecs.STRING_UTF8.map(PerkCondition::byName, PerkCondition::getSerializedName).cast();

    private static final java.util.Map<String, PerkCondition> BY_NAME;
    static {
        java.util.Map<String, PerkCondition> map = new java.util.HashMap<>();
        for (PerkCondition c : values()) map.put(c.name, c);
        BY_NAME = java.util.Map.copyOf(map);
    }

    public static PerkCondition byName(String name) {
        PerkCondition c = BY_NAME.get(name);
        if (c == null) throw new IllegalArgumentException("Unknown PerkCondition: " + name);
        return c;
    }

    private final String name;

    PerkCondition(String name) { this.name = name; }

    @Override
    public String getSerializedName() { return name; }

    public String langKey() { return "dynetech.condition." + name; }
    public String descriptionKey() { return "dynetech.condition.desc." + name; }

    public net.minecraft.network.chat.Component description() {
        return net.minecraft.network.chat.Component.translatable(descriptionKey());
    }

    /**
     * True when the perk should currently be expressing. Cheap enough to call every tick for
     * most conditions; the one expensive branch - {@link #ALONE} - is memoized in
     * {@link #ALONE_CACHE} so its 32³ box scan runs at most every {@link #ALONE_CACHE_INTERVAL}
     * ticks per entity.
     */
    public boolean check(LivingEntity entity) {
        return switch (this) {
            case ALWAYS -> true;
            // Both pose-based crouching AND the raw shift-key press count as "sneaking". This
            // catches the case where the player is holding shift but the pose hasn't fully
            // transitioned to CROUCHING yet (or is being suppressed by a low ceiling / vehicle).
            case SNEAKING -> entity.isCrouching() || entity.isShiftKeyDown();
            case SPRINTING -> entity.isSprinting();
            case NOT_MOVING -> entity.getDeltaMovement().horizontalDistanceSqr() < 0.005;
            case FALLING -> entity.fallDistance > 0.5F && !entity.onGround();
            case ON_FIRE -> entity.isOnFire();
            case LOW_HEALTH -> entity.getHealth() < entity.getMaxHealth() * 0.5F;
            case HIGH_HEALTH -> entity.getHealth() >= entity.getMaxHealth() * 0.9F;
            case DAY -> entity.level().isDay();
            case NIGHT -> entity.level().isNight();
            case USING_ITEM -> entity.isUsingItem();
            case ALONE -> checkAloneCached(entity);
        };
    }

    // ============================================================================
    //  ALONE cache
    //  Scanning a 32³ box every tick per equipped mob is a server hot spot. Cache the answer
    //  for a 5-second window keyed on the entity itself - WeakHashMap so discarded entities
    //  fall out on GC without any bookkeeping.
    // ============================================================================

    private static final int ALONE_CACHE_INTERVAL = 100;
    private static final Map<LivingEntity, long[]> ALONE_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static boolean checkAloneCached(LivingEntity entity) {
        long tick = entity.tickCount;
        long[] cached = ALONE_CACHE.get(entity);
        if (cached != null && tick - cached[0] < ALONE_CACHE_INTERVAL) {
            return cached[1] != 0;
        }
        boolean alone = computeAlone(entity);
        ALONE_CACHE.put(entity, new long[]{tick, alone ? 1L : 0L});
        return alone;
    }

    private static boolean computeAlone(LivingEntity entity) {
        Level level = entity.level();
        var box = entity.getBoundingBox().inflate(16.0);
        return level.getEntitiesOfClass(Player.class, box, p -> p != entity).isEmpty();
    }
}
