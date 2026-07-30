package com.confect1on.dynetech.gene;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.gene.perks.AbsorptionPerk;
import com.confect1on.dynetech.gene.perks.AttributePerk;
import com.confect1on.dynetech.gene.perks.BaneOfUndeadPerk;
import com.confect1on.dynetech.gene.perks.BloodthirstPerk;
import com.confect1on.dynetech.gene.perks.CactusSkinPerk;
import com.confect1on.dynetech.gene.perks.CamouflagePerk;
import com.confect1on.dynetech.gene.perks.EnderBlinkPerk;
import com.confect1on.dynetech.gene.perks.ExplosiveDeathPerk;
import com.confect1on.dynetech.gene.perks.GluttonyDefect;
import com.confect1on.dynetech.gene.perks.GodhoodPerk;
import com.confect1on.dynetech.gene.perks.IgnitionDefect;
import com.confect1on.dynetech.gene.perks.ImmunityPerk;
import com.confect1on.dynetech.gene.perks.MobEffectPerk;
import com.confect1on.dynetech.gene.perks.NoOpPerk;
import com.confect1on.dynetech.gene.perks.PhotophobiaDefect;
import com.confect1on.dynetech.gene.perks.PhotosynthesisPerk;
import com.confect1on.dynetech.gene.perks.ScalePerk;
import com.confect1on.dynetech.gene.perks.ScreamerDefect;
import com.confect1on.dynetech.gene.perks.SlimeBouncePerk;
import com.confect1on.dynetech.gene.perks.StaticDefect;
import com.confect1on.dynetech.gene.perks.VenomTouchPerk;
import com.confect1on.dynetech.gene.perks.VertigoDefect;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Built-in perk registry. Backed by a proper NeoForge {@link Registry} so third-party mods can
 * define their own perks via their own {@link DeferredRegister} against {@link #REGISTRY_KEY}
 * and have them picked up by the sequencer, splicer, injection gun, and the lifecycle events.
 *
 * <p>Each registration includes a curated set of allowed conditions. The sequencer only ever
 * rolls conditions from this set, so we cannot produce useless combinations like "Gills only
 * while swimming" or "Fireproof only when not on fire".
 */
public final class Perks {

    public static final ResourceKey<Registry<Perk>> REGISTRY_KEY =
            ResourceKey.createRegistryKey(DyneTech.id("perk"));

    public static final DeferredRegister<Perk> REGISTRAR =
            DeferredRegister.create(REGISTRY_KEY, DyneTech.MODID);

    /**
     * Non-synced. Perks are declared statically in Java on both sides of the connection, so the
     * client's registry is already populated from mod init. No point paying the sync cost.
     */
    public static final Registry<Perk> REGISTRY =
            REGISTRAR.makeRegistry(builder -> builder.sync(false));

    private Perks() {}

    // ============================================================================
    //  Attribute perks
    // ============================================================================

    public static final DeferredHolder<Perk, AttributePerk> VITALITY = attribute("vitality",
            ChatFormatting.GREEN, Attributes.MAX_HEALTH, 6.0D, AttributeModifier.Operation.ADD_VALUE, 0xFF4444,
            Set.of(PerkCondition.ALWAYS, PerkCondition.NIGHT, PerkCondition.SNEAKING, PerkCondition.ALONE));

    public static final DeferredHolder<Perk, AttributePerk> BRAWN = attribute("brawn",
            ChatFormatting.RED, Attributes.ATTACK_DAMAGE, 3.0D, AttributeModifier.Operation.ADD_VALUE, 0xAA2222,
            Set.of(PerkCondition.ALWAYS, PerkCondition.SPRINTING, PerkCondition.LOW_HEALTH, PerkCondition.USING_ITEM));

    public static final DeferredHolder<Perk, AttributePerk> FLEETFOOT = attribute("fleetfoot",
            ChatFormatting.AQUA, Attributes.MOVEMENT_SPEED, 0.15D, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, 0x66DDFF,
            Set.of(PerkCondition.ALWAYS, PerkCondition.SPRINTING, PerkCondition.DAY, PerkCondition.NIGHT));

    public static final DeferredHolder<Perk, AttributePerk> LEAP = attribute("leap",
            ChatFormatting.YELLOW, Attributes.JUMP_STRENGTH, 0.30D, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, 0xFFEE55,
            Set.of(PerkCondition.ALWAYS, PerkCondition.FALLING, PerkCondition.SPRINTING, PerkCondition.ALONE));

    public static final DeferredHolder<Perk, AttributePerk> STONESKIN = attribute("stoneskin",
            ChatFormatting.GRAY, Attributes.KNOCKBACK_RESISTANCE, 0.8D, AttributeModifier.Operation.ADD_VALUE, 0x999999,
            Set.of(PerkCondition.ALWAYS, PerkCondition.SNEAKING, PerkCondition.NOT_MOVING, PerkCondition.HIGH_HEALTH));

    public static final DeferredHolder<Perk, AttributePerk> BULWARK = attribute("bulwark",
            ChatFormatting.DARK_GRAY, Attributes.ARMOR, 4.0D, AttributeModifier.Operation.ADD_VALUE, 0x778899,
            Set.of(PerkCondition.ALWAYS, PerkCondition.HIGH_HEALTH, PerkCondition.NOT_MOVING, PerkCondition.USING_ITEM));

    public static final DeferredHolder<Perk, AttributePerk> CUSHION = attribute("cushion",
            ChatFormatting.WHITE, Attributes.SAFE_FALL_DISTANCE, 3.0D, AttributeModifier.Operation.ADD_VALUE, 0xEEEEDD,
            Set.of(PerkCondition.ALWAYS, PerkCondition.FALLING, PerkCondition.SNEAKING, PerkCondition.ALONE));

    // Full-quality magnitudes are +2.5 (up from +1.5). At avg quality ~0.5 that reads as +1.25
    // blocks of reach; the older +0.75 was easy to miss during play. Caps around 7 blocks at
    // max quality, comparable to enchanted grappling reach in other mods.
    public static final DeferredHolder<Perk, AttributePerk> LONGARM = attribute("longarm",
            ChatFormatting.LIGHT_PURPLE, Attributes.ENTITY_INTERACTION_RANGE, 2.5D, AttributeModifier.Operation.ADD_VALUE, 0xCC66DD,
            Set.of(PerkCondition.ALWAYS, PerkCondition.SNEAKING, PerkCondition.NIGHT, PerkCondition.USING_ITEM));

    public static final DeferredHolder<Perk, AttributePerk> REACHMINER = attribute("reachminer",
            ChatFormatting.DARK_AQUA, Attributes.BLOCK_INTERACTION_RANGE, 2.5D, AttributeModifier.Operation.ADD_VALUE, 0x338866,
            Set.of(PerkCondition.ALWAYS, PerkCondition.SNEAKING, PerkCondition.NOT_MOVING, PerkCondition.USING_ITEM));

    public static final DeferredHolder<Perk, AttributePerk> DEEPBREATH = attribute("deepbreath",
            ChatFormatting.BLUE, Attributes.OXYGEN_BONUS, 2.0D, AttributeModifier.Operation.ADD_VALUE, 0x3388CC,
            Set.of(PerkCondition.ALWAYS, PerkCondition.SNEAKING, PerkCondition.NOT_MOVING, PerkCondition.LOW_HEALTH));

    public static final DeferredHolder<Perk, AttributePerk> HOLLOWBONES = attribute("hollowbones",
            ChatFormatting.WHITE, Attributes.GRAVITY, -0.04D, AttributeModifier.Operation.ADD_VALUE, 0xDDDDDD,
            Set.of(PerkCondition.ALWAYS, PerkCondition.FALLING, PerkCondition.NIGHT, PerkCondition.ALONE));

    public static final DeferredHolder<Perk, AttributePerk> MINER = attribute("miner",
            ChatFormatting.DARK_GREEN, Attributes.BLOCK_BREAK_SPEED, 0.30D, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, 0x556633,
            Set.of(PerkCondition.ALWAYS, PerkCondition.NOT_MOVING, PerkCondition.USING_ITEM, PerkCondition.SNEAKING, PerkCondition.DAY));

    // Vanilla player step height is 0.6; +0.4 lands at exactly 1.0 so climbers can walk up a
    // full block without jumping. Anything higher starts to look glitchy on stairs.
    public static final DeferredHolder<Perk, AttributePerk> CLIMBER = attribute("climber",
            ChatFormatting.GOLD, Attributes.STEP_HEIGHT, 0.40D, AttributeModifier.Operation.ADD_VALUE, 0xAA8844,
            Set.of(PerkCondition.ALWAYS, PerkCondition.SPRINTING, PerkCondition.SNEAKING, PerkCondition.USING_ITEM));

    // +1.5 knockback at full quality sends light mobs flying. Reads as a distinct-from-Brawn
    // pressure tool rather than a damage upgrade.
    public static final DeferredHolder<Perk, AttributePerk> RAM = attribute("ram",
            ChatFormatting.RED, Attributes.ATTACK_KNOCKBACK, 1.5D, AttributeModifier.Operation.ADD_VALUE, 0xDD6633,
            Set.of(PerkCondition.ALWAYS, PerkCondition.SPRINTING, PerkCondition.HIGH_HEALTH, PerkCondition.USING_ITEM));

    // ADD_MULTIPLIED_BASE because attack speed's base value is 4.0 and a flat +0.3 would be a
    // rounding error. +30% base is one extra swing every four seconds and reads on the cooldown bar.
    public static final DeferredHolder<Perk, AttributePerk> SWIFT_STRIKE = attribute("swift_strike",
            ChatFormatting.YELLOW, Attributes.ATTACK_SPEED, 0.30D, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, 0xEEDD44,
            Set.of(PerkCondition.ALWAYS, PerkCondition.SPRINTING, PerkCondition.LOW_HEALTH, PerkCondition.HIGH_HEALTH));

    // ============================================================================
    //  Mob-effect perks
    // ============================================================================

    public static final DeferredHolder<Perk, MobEffectPerk> GILLS = mobEffect("gills",
            ChatFormatting.BLUE, MobEffects.WATER_BREATHING, 0, false, 0x3388FF,
            Set.of(PerkCondition.ALWAYS, PerkCondition.SNEAKING, PerkCondition.NOT_MOVING, PerkCondition.LOW_HEALTH));

    public static final DeferredHolder<Perk, MobEffectPerk> FIREPROOF = mobEffect("fireproof",
            ChatFormatting.GOLD, MobEffects.FIRE_RESISTANCE, 0, false, 0xFFAA22,
            Set.of(PerkCondition.ALWAYS, PerkCondition.ON_FIRE, PerkCondition.SNEAKING, PerkCondition.LOW_HEALTH));

    public static final DeferredHolder<Perk, MobEffectPerk> NOCTURNAL = mobEffect("nocturnal",
            ChatFormatting.DARK_PURPLE, MobEffects.NIGHT_VISION, 0, false, 0x442288,
            Set.of(PerkCondition.ALWAYS, PerkCondition.NIGHT, PerkCondition.SNEAKING, PerkCondition.ALONE));

    public static final DeferredHolder<Perk, MobEffectPerk> REGEN = mobEffect("regen",
            ChatFormatting.LIGHT_PURPLE, MobEffects.REGENERATION, 0, false, 0xFF88BB,
            Set.of(PerkCondition.ALWAYS, PerkCondition.LOW_HEALTH, PerkCondition.NOT_MOVING, PerkCondition.ALONE));

    public static final DeferredHolder<Perk, MobEffectPerk> GRACE = mobEffect("grace",
            ChatFormatting.AQUA, MobEffects.DOLPHINS_GRACE, 0, false, 0x66DDDD,
            Set.of(PerkCondition.ALWAYS, PerkCondition.NOT_MOVING, PerkCondition.LOW_HEALTH, PerkCondition.NIGHT));

    public static final DeferredHolder<Perk, MobEffectPerk> LUCK = mobEffect("luck",
            ChatFormatting.GREEN, MobEffects.LUCK, 1, false, 0x66DD66,
            Set.of(PerkCondition.ALWAYS, PerkCondition.SNEAKING, PerkCondition.DAY, PerkCondition.USING_ITEM));

    // Amplifier capped at 1 so a pristine roll can push to Resistance II. Higher tiers make the
    // host nearly untouchable in combination with Bulwark/Stoneskin.
    public static final DeferredHolder<Perk, MobEffectPerk> COMPOSURE = mobEffect("composure",
            ChatFormatting.WHITE, MobEffects.DAMAGE_RESISTANCE, 1, false, 0xC8CCE0,
            Set.of(PerkCondition.ALWAYS, PerkCondition.LOW_HEALTH, PerkCondition.HIGH_HEALTH, PerkCondition.NOT_MOVING));

    // FALLING included so a sequenced serum can express only mid-air. Amplifier capped at 0:
    // Slow Falling II is not a thing vanilla exposes and higher values would be no-ops.
    public static final DeferredHolder<Perk, MobEffectPerk> SLOW_FALL = mobEffect("slow_fall",
            ChatFormatting.WHITE, MobEffects.SLOW_FALLING, 0, false, 0xEEEEFF,
            Set.of(PerkCondition.ALWAYS, PerkCondition.FALLING, PerkCondition.SNEAKING, PerkCondition.LOW_HEALTH));

    // Chameleon uses our camouflage marker effect so the client-side render hook can draw the
    // entity translucent, rather than fully invisible like vanilla INVISIBILITY.
    public static final DeferredHolder<Perk, CamouflagePerk> INVISIBILITY = REGISTRAR.register("invisibility",
            id -> new CamouflagePerk(id, 0x554488,
                    Set.of(PerkCondition.ALWAYS, PerkCondition.SNEAKING, PerkCondition.NIGHT, PerkCondition.NOT_MOVING, PerkCondition.ALONE)));

    // ============================================================================
    //  Immunity perks
    //   Each guards one specific mob effect. Effect strips happen on tick, so a hostile source
    //   can still apply the icon for up to one tick before the perk clears it.
    // ============================================================================

    public static final DeferredHolder<Perk, ImmunityPerk> POISON_IMMUNE = immunity("poison_immune",
            ChatFormatting.DARK_GREEN, MobEffects.POISON, 0x55AA55,
            Set.of(PerkCondition.ALWAYS, PerkCondition.SNEAKING, PerkCondition.LOW_HEALTH, PerkCondition.ALONE));

    public static final DeferredHolder<Perk, ImmunityPerk> WITHER_IMMUNE = immunity("wither_immune",
            ChatFormatting.DARK_PURPLE, MobEffects.WITHER, 0x554455,
            Set.of(PerkCondition.ALWAYS, PerkCondition.SNEAKING, PerkCondition.LOW_HEALTH, PerkCondition.NIGHT));

    public static final DeferredHolder<Perk, ImmunityPerk> LEVITATION_IMMUNE = immunity("levitation_immune",
            ChatFormatting.DARK_AQUA, MobEffects.LEVITATION, 0x336677,
            Set.of(PerkCondition.ALWAYS, PerkCondition.FALLING, PerkCondition.SNEAKING, PerkCondition.NIGHT));

    // ============================================================================
    //  Scale perks
    // ============================================================================

    public static final DeferredHolder<Perk, ScalePerk> MICRO = REGISTRAR.register("micro",
            id -> new ScalePerk(id, name("micro", ChatFormatting.LIGHT_PURPLE), -0.5F, 0xFF66FF,
                    Set.of(PerkCondition.ALWAYS, PerkCondition.SNEAKING, PerkCondition.NIGHT, PerkCondition.LOW_HEALTH)));

    public static final DeferredHolder<Perk, ScalePerk> MACRO = REGISTRAR.register("macro",
            id -> new ScalePerk(id, name("macro", ChatFormatting.DARK_RED), +1.0F, 0x883333,
                    Set.of(PerkCondition.ALWAYS, PerkCondition.SPRINTING, PerkCondition.DAY, PerkCondition.HIGH_HEALTH)));

    // ============================================================================
    //  Bespoke perks
    // ============================================================================

    public static final DeferredHolder<Perk, AbsorptionPerk> ABSORPTION = REGISTRAR.register("absorption",
            id -> new AbsorptionPerk(id, 0xFFCC22, 1,
                    Set.of(PerkCondition.ALWAYS, PerkCondition.HIGH_HEALTH, PerkCondition.DAY, PerkCondition.NOT_MOVING)));

    public static final DeferredHolder<Perk, PhotosynthesisPerk> PHOTOSYNTHESIS =
            REGISTRAR.register("photosynthesis", PhotosynthesisPerk::new);

    public static final DeferredHolder<Perk, BloodthirstPerk> BLOODTHIRST =
            REGISTRAR.register("bloodthirst", BloodthirstPerk::new);
    public static final DeferredHolder<Perk, BaneOfUndeadPerk> BANE_OF_UNDEAD =
            REGISTRAR.register("bane_of_undead", BaneOfUndeadPerk::new);
    public static final DeferredHolder<Perk, CactusSkinPerk> CACTUS_SKIN =
            REGISTRAR.register("cactus_skin", CactusSkinPerk::new);
    public static final DeferredHolder<Perk, ExplosiveDeathPerk> EXPLOSIVE_DEATH =
            REGISTRAR.register("explosive_death", ExplosiveDeathPerk::new);
    public static final DeferredHolder<Perk, SlimeBouncePerk> SLIME_BOUNCE =
            REGISTRAR.register("slime_bounce", SlimeBouncePerk::new);
    public static final DeferredHolder<Perk, EnderBlinkPerk> ENDER_BLINK =
            REGISTRAR.register("ender_blink", EnderBlinkPerk::new);
    public static final DeferredHolder<Perk, VenomTouchPerk> VENOM_TOUCH =
            REGISTRAR.register("venom_touch", VenomTouchPerk::new);

    public static final DeferredHolder<Perk, GodhoodPerk> GODHOOD =
            REGISTRAR.register("godhood", GodhoodPerk::new);

    // ============================================================================
    //  Defects
    // ============================================================================

    public static final DeferredHolder<Perk, IgnitionDefect> IGNITION_DEFECT =
            REGISTRAR.register("ignition_defect", IgnitionDefect::new);

    public static final DeferredHolder<Perk, MobEffectPerk> FRAILTY_DEFECT = mobEffect("frailty_defect",
            ChatFormatting.DARK_GRAY, MobEffects.WEAKNESS, 1, true, 0x555555, Set.of(PerkCondition.ALWAYS));

    public static final DeferredHolder<Perk, MobEffectPerk> STARVATION_DEFECT = mobEffect("starvation_defect",
            ChatFormatting.DARK_GREEN, MobEffects.HUNGER, 0, true, 0x225522, Set.of(PerkCondition.ALWAYS));

    public static final DeferredHolder<Perk, MobEffectPerk> WITHERING_DEFECT = mobEffect("withering_defect",
            ChatFormatting.BLACK, MobEffects.WITHER, 0, true, 0x222222, Set.of(PerkCondition.ALWAYS));

    public static final DeferredHolder<Perk, PhotophobiaDefect> PHOTOPHOBIA_DEFECT =
            REGISTRAR.register("photophobia_defect", PhotophobiaDefect::new);
    public static final DeferredHolder<Perk, GluttonyDefect> GLUTTONY_DEFECT =
            REGISTRAR.register("gluttony_defect", GluttonyDefect::new);
    public static final DeferredHolder<Perk, ScreamerDefect> SCREAMER_DEFECT =
            REGISTRAR.register("screamer_defect", ScreamerDefect::new);
    public static final DeferredHolder<Perk, StaticDefect> STATIC_DEFECT =
            REGISTRAR.register("static_defect", StaticDefect::new);
    public static final DeferredHolder<Perk, VertigoDefect> VERTIGO_DEFECT =
            REGISTRAR.register("vertigo_defect", VertigoDefect::new);

    // Placeholder trait used by self-samples so player blood always sequences into inert vials.
    public static final DeferredHolder<Perk, NoOpPerk> DUD = REGISTRAR.register("dud",
            id -> new NoOpPerk(id,
                    Component.translatable("dynetech.perk.dud").withStyle(ChatFormatting.DARK_GRAY),
                    0x808080));

    // ============================================================================
    //  Lookup API (used by the pipeline and by external mods)
    // ============================================================================

    /** Resolve a perk by id. Returns null if no perk with that id is registered. */
    @Nullable
    public static Perk get(ResourceLocation id) { return REGISTRY.get(id); }

    /** Snapshot of every registered perk. Iteration order matches registration order. */
    public static java.util.List<Perk> all() { return REGISTRY.stream().toList(); }

    // ============================================================================
    //  Registration helpers
    // ============================================================================

    private static DeferredHolder<Perk, AttributePerk> attribute(String path, ChatFormatting color,
                                                                 net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                                                 double amountAtFullQuality,
                                                                 AttributeModifier.Operation operation,
                                                                 int tint,
                                                                 Set<PerkCondition> allowedConditions) {
        return REGISTRAR.register(path,
                id -> new AttributePerk(id, name(path, color), attribute, amountAtFullQuality, operation, tint, allowedConditions));
    }

    private static DeferredHolder<Perk, MobEffectPerk> mobEffect(String path, ChatFormatting color,
                                                                 net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect,
                                                                 int maxAmplifier, boolean defect, int tint,
                                                                 Set<PerkCondition> allowedConditions) {
        return REGISTRAR.register(path,
                id -> new MobEffectPerk(id, name(path, color), effect, maxAmplifier, defect, tint, allowedConditions));
    }

    private static DeferredHolder<Perk, ImmunityPerk> immunity(String path, ChatFormatting color,
                                                                net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> guarded,
                                                                int tint, Set<PerkCondition> allowedConditions) {
        return REGISTRAR.register(path,
                id -> new ImmunityPerk(id, name(path, color), tint, guarded, allowedConditions));
    }

    private static Component name(String key, ChatFormatting color) {
        return Component.translatable("dynetech.perk." + key).withStyle(color);
    }
}
