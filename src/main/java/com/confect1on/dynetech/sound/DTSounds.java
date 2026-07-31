package com.confect1on.dynetech.sound;

import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.confect1on.dynetech.DyneTech;

public class DTSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, DyneTech.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> PYM_PARTICLE_SHRINKING =
            make("misc.pym_particle_shrinking");

    public static final DeferredHolder<SoundEvent, SoundEvent> PYM_PARTICLE_SHRINKING_UNDERWATER =
            make("misc.pym_particle_shrinking_underwater");

    public static final DeferredHolder<SoundEvent, SoundEvent> PYM_PARTICLE_ENLARGING =
            make("misc.pym_particle_enlarging");

    public static final DeferredHolder<SoundEvent, SoundEvent> PYM_PARTICLE_ENLARGING_UNDERWATER =
            make("misc.pym_particle_enlarging_underwater");

    /** 65-second cue: 5s burn-up, moment of regeneration, then 60s of vulnerability. */
    public static final DeferredHolder<SoundEvent, SoundEvent> GODHOOD_REGEN =
            make("godhood.regen");

    /** Short loopable ambience. Emitted by every godhood-carrying entity with charges. */
    public static final DeferredHolder<SoundEvent, SoundEvent> GODHOOD_WHISPERS =
            make("godhood.whispers");

    /** 15s buildup the Usher plays while charging its Departure. */
    public static final DeferredHolder<SoundEvent, SoundEvent> USHER_CHARGE =
            make("usher.charge");

    /** Default Departure payoff, plays at the moment of the catch blast. */
    public static final DeferredHolder<SoundEvent, SoundEvent> USHER_BLAST =
            make("usher.blast");

    /** 30% easter-egg substitute for USHER_BLAST at Departure time. */
    public static final DeferredHolder<SoundEvent, SoundEvent> USHER_EASTEREGG =
            make("usher.easteregg");

    private static DeferredHolder<SoundEvent, SoundEvent> make(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(DyneTech.id(name)));
    }
}
