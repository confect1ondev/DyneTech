package com.confect1on.dynetech.client;

import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.entity.VigilEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Client-side motion-wake for the Vigil. Tracks each loaded Vigil's position per client tick;
 * whenever a Vigil moved more than a small threshold since the last tick, spawns a subtle dust
 * puff at the previous location and a deepslate chip at the current one.
 *
 * <p>Design intent: the Vigil is a look-away enemy - the player never actually catches it
 * moving. This wake plays whether or not anyone is watching, so when the player turns back they
 * see the evidence hanging in the air. It reads as investigation reward rather than a telegraph.
 * Frozen Vigils don't move, so no particles - only active ones leave traces.
 */
@EventBusSubscriber(modid = DyneTech.MODID, value = Dist.CLIENT)
public final class VigilTracesManager {

    private VigilTracesManager() {}

    // A Vigil at full speed moves ~0.5 blocks/tick. Anything under this threshold is treated as
    // idle jitter (network correction, tiny walk-goal drift) and doesn't spawn particles.
    private static final double MOTION_THRESHOLD = 0.15;
    private static final double MOTION_THRESHOLD_SQ = MOTION_THRESHOLD * MOTION_THRESHOLD;

    private static final Map<Integer, Vec3> LAST_POS = new HashMap<>();

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            LAST_POS.clear();
            return;
        }
        RandomSource rng = mc.level.getRandom();

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof VigilEntity vigil)) continue;
            Vec3 cur = vigil.position();
            Vec3 last = LAST_POS.put(vigil.getId(), cur);
            if (last == null) continue;

            double dx = cur.x - last.x;
            double dz = cur.z - last.z;
            if (dx * dx + dz * dz < MOTION_THRESHOLD_SQ) continue;

            // Wake puff at the previous position - three small smoke motes drifting downward,
            // like disturbed dust settling. Camera sees these when it swings back.
            for (int i = 0; i < 3; i++) {
                double px = last.x + (rng.nextDouble() - 0.5) * 0.45;
                double py = last.y + rng.nextDouble() * 1.7;
                double pz = last.z + (rng.nextDouble() - 0.5) * 0.45;
                mc.level.addParticle(ParticleTypes.SMOKE, px, py, pz,
                        0.0, -0.005, 0.0);
            }
            // A single cracked-deepslate chip at the Vigil's current feet - the block particle
            // says "stone body straining as it moves." One per tick keeps it a rare tell.
            double chipX = cur.x + (rng.nextDouble() - 0.5) * 0.35;
            double chipZ = cur.z + (rng.nextDouble() - 0.5) * 0.35;
            mc.level.addParticle(
                    new BlockParticleOption(ParticleTypes.BLOCK,
                            Blocks.COBBLED_DEEPSLATE.defaultBlockState()),
                    chipX, cur.y + 0.15, chipZ, 0.0, 0.03, 0.0);
        }

        // Sweep entries whose entity has despawned. LAST_POS.put above never accidentally leaks
        // - unloaded entities just stop reporting - but we still want to drop the memory.
        Iterator<Map.Entry<Integer, Vec3>> it = LAST_POS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Vec3> entry = it.next();
            if (mc.level.getEntity(entry.getKey()) == null) it.remove();
        }
    }
}
