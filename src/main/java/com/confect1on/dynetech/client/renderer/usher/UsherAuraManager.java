package com.confect1on.dynetech.client.renderer.usher;

import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.client.renderer.aura.AuraQuads;
import com.confect1on.dynetech.client.renderer.aura.AuraRenderTypes;
import com.confect1on.dynetech.client.renderer.aura.AuraShapes;
import com.confect1on.dynetech.entity.UsherEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Theatrical three-act Departure visual for the Usher, driven from the render-level stage.
 *
 * <ul>
 *   <li><b>Act I - Marking (0-30t):</b> a rotating ground hexagram and six candle-flame
 *       columns bloom into visibility at hex vertices around the Usher.</li>
 *   <li><b>Act II - Convergence (30-90t):</b> three tilted orbital rings materialise and
 *       tighten; soul tethers arc from every nearby capturable entity to the Usher's chest;
 *       purple lightning starts to jump between candles and the Usher.</li>
 *   <li><b>Act III - Pinch (90-120t):</b> rings collapse to a pinpoint, candle tips bend
 *       inward, lightning intensifies, and the nucleus swells into a brilliant core.</li>
 *   <li><b>Detonation:</b> a brief silent primer flash, then three concentric dome shells
 *       expand at different rates, six streaked petal arms launch outward, six long lightning
 *       bolts fork out, and the ground sigil lingers as a scorch mark for another second.</li>
 * </ul>
 *
 * <p>Drawing is organised in flat passes (core -> halo -> shell). Alternating different
 * {@link net.minecraft.client.renderer.RenderType} buffers inside a single loop crashes with
 * {@code Not building!} because {@link MultiBufferSource.BufferSource#getBuffer} closes the
 * previous type's buffer when a new type is requested.
 */
@EventBusSubscriber(modid = DyneTech.MODID, value = Dist.CLIENT)
public final class UsherAuraManager {

    private UsherAuraManager() {}

    // Palette. Deep royal purple for the ritual, cool violet for the halos, near-black for the
    // rift cracks, brilliant white-violet for the moment-of-blast nucleus.
    private static final int PEARL_R = 158, PEARL_G = 92, PEARL_B = 230;
    private static final int GLOW_R = 128, GLOW_G = 40, PEARL_HALO_A_MAX = 220;
    private static final int GLOW_B = 220;
    private static final int RIFT_R = 46, RIFT_G = 14, RIFT_B = 68;
    private static final int FLASH_R = 255, FLASH_G = 225, FLASH_B = 255;
    private static final int SIGIL_R = 200, SIGIL_G = 120, SIGIL_B = 255;

    // Act timing (client-side, matches the 120-tick server charge).
    private static final int ACT1_END = 30;
    private static final int ACT2_END = 90;
    private static final int ACT3_END = 120;

    // Detonation timeline.
    private static final int DETON_PRIMER_TICKS = 3;
    private static final int DETON_DOME_GROWTH_TICKS = 16;
    private static final int DETON_FADE_TICKS = 30;
    private static final int DETON_TOTAL_TICKS = 40;
    private static final int SIGIL_LINGER_TICKS = 30;

    private static final int RING_COUNT = 3;
    private static final int PEARLS_PER_RING = 24;
    private static final float START_RADIUS = 4.5F;
    private static final float END_RADIUS = 0.35F;
    private static final float PEARL_HALF = 0.13F;
    private static final float PEARL_HALO_HALF = 0.55F;

    private static final int CANDLE_COUNT = 6;
    private static final float CANDLE_RADIUS = 4.2F;
    private static final int CANDLE_HEIGHT_PEARLS = 12;
    private static final float CANDLE_HEIGHT_MAX = 3.6F;

    private static final float SIGIL_OUTER = 5.0F;
    private static final float SIGIL_INNER = 3.5F;

    private static final Map<Integer, ChargeState> CHARGES = new HashMap<>();
    private static final Map<Integer, BlastState> BLASTS = new HashMap<>();

    private static int clientTick;

    public static void startCharge(int entityId, int durationTicks) {
        CHARGES.put(entityId, new ChargeState(clientTick, durationTicks, entityId));
    }

    public static void triggerBlast(int entityId, float innerRadius, float outerRadius) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 origin = null;
        if (mc.level != null) {
            Entity e = mc.level.getEntity(entityId);
            if (e != null) origin = new Vec3(e.getX(), e.getY(), e.getZ());
        }
        BLASTS.put(entityId, new BlastState(clientTick, origin, innerRadius, outerRadius, entityId));
        CHARGES.remove(entityId);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        clientTick++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            CHARGES.clear();
            BLASTS.clear();
            return;
        }
        Iterator<Map.Entry<Integer, ChargeState>> ci = CHARGES.entrySet().iterator();
        while (ci.hasNext()) {
            Map.Entry<Integer, ChargeState> e = ci.next();
            if (clientTick - e.getValue().startTick > e.getValue().durationTicks + 10
                    || mc.level.getEntity(e.getKey()) == null) {
                ci.remove();
            }
        }
        Iterator<Map.Entry<Integer, BlastState>> bi = BLASTS.entrySet().iterator();
        while (bi.hasNext()) {
            Map.Entry<Integer, BlastState> e = bi.next();
            if (clientTick - e.getValue().startTick > DETON_TOTAL_TICKS + SIGIL_LINGER_TICKS + 4) {
                bi.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (CHARGES.isEmpty() && BLASTS.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        Vector3f camRight = new Vector3f();
        Vector3f camUp = new Vector3f();
        Vector3f camFwd = new Vector3f();
        camera.rotation().transform(1F, 0F, 0F, camRight);
        camera.rotation().transform(0F, 1F, 0F, camUp);
        camera.rotation().transform(0F, 0F, 1F, camFwd);

        // Resolve entities and precompute per-charge context once.
        List<ChargeCtx> charges = new ArrayList<>();
        for (Map.Entry<Integer, ChargeState> e : CHARGES.entrySet()) {
            Entity ent = mc.level.getEntity(e.getKey());
            if (!(ent instanceof UsherEntity usher)) continue;
            ChargeCtx ctx = buildChargeCtx(usher, e.getValue(), cam, partialTick);
            if (ctx != null) charges.add(ctx);
        }
        List<BlastCtx> blasts = new ArrayList<>();
        for (Map.Entry<Integer, BlastState> e : BLASTS.entrySet()) {
            BlastCtx ctx = buildBlastCtx(e.getValue(), cam, partialTick);
            if (ctx != null) blasts.add(ctx);
        }

        // Pass 1: alpha-blended core geometry.
        VertexConsumer core = buffers.getBuffer(AuraRenderTypes.pearlCore());
        for (ChargeCtx c : charges) emitChargeCore(c, pose, core, camRight, camUp, camFwd);
        for (BlastCtx b : blasts) emitBlastCore(b, pose, core, camRight, camUp, camFwd);
        buffers.endBatch(AuraRenderTypes.pearlCore());

        // Pass 2: additive halo (glow around each pearl).
        VertexConsumer halo = buffers.getBuffer(AuraRenderTypes.pearlHalo());
        for (ChargeCtx c : charges) emitChargeHalo(c, pose, halo, camRight, camUp, camFwd);
        for (BlastCtx b : blasts) emitBlastHalo(b, pose, halo, camRight, camUp, camFwd);
        buffers.endBatch(AuraRenderTypes.pearlHalo());

        // Pass 3: additive shell (domes, ground discs, god-rays, lingering sigil scorch).
        VertexConsumer shell = buffers.getBuffer(AuraRenderTypes.shell());
        for (ChargeCtx c : charges) emitChargeShell(c, pose, shell);
        for (BlastCtx b : blasts) emitBlastShell(b, pose, shell);
        buffers.endBatch(AuraRenderTypes.shell());
    }

    // ------------------------------------------------------------------
    //  Context: everything the emit methods need pre-computed once
    // ------------------------------------------------------------------

    private static ChargeCtx buildChargeCtx(UsherEntity usher, ChargeState state,
                                            Vec3 cam, float partialTick) {
        float elapsed = (clientTick - state.startTick) + partialTick;
        float total = state.durationTicks;
        if (elapsed < 0F || total <= 0F) return null;
        float t = Mth.clamp(elapsed / total, 0F, 1F);

        // Cubic ease-in so the rings loiter wide then snap inward in the final third.
        float easeIn = t * t * t;

        // Per-act blended factors so overlapping visuals ramp naturally.
        float actMark = Mth.clamp(elapsed / (float) ACT1_END, 0F, 1F);
        float actConv = Mth.clamp((elapsed - ACT1_END) / (float) (ACT2_END - ACT1_END), 0F, 1F);
        float actPinch = Mth.clamp((elapsed - ACT2_END) / (float) (ACT3_END - ACT2_END), 0F, 1F);

        float ringRadius = Mth.lerp(easeIn, START_RADIUS, END_RADIUS);
        float rotSpeed = 0.6F + easeIn * 5.0F;

        Vec3 entPos = usher.getPosition(partialTick);
        Vec3 chest = entPos.add(0.0, usher.getBbHeight() * 0.55, 0.0);

        // Gather soul tethers on entities within the catch radius. Filter matches the server-side
        // stageCatches predicate closely enough for the render to line up with actual catches.
        List<Vec3> tethers = new ArrayList<>();
        if (actConv > 0.05F) {
            AABB scan = usher.getBoundingBox().inflate(UsherEntity.CATCH_RADIUS);
            for (LivingEntity target : usher.level().getEntitiesOfClass(LivingEntity.class, scan)) {
                if (target == usher) continue;
                if (target instanceof UsherEntity) continue;
                if (target instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
                if (!target.isAlive()) continue;
                if (target.distanceToSqr(usher)
                        > UsherEntity.CATCH_RADIUS * UsherEntity.CATCH_RADIUS) continue;
                tethers.add(target.getPosition(partialTick)
                        .add(0.0, target.getBbHeight() * 0.5, 0.0));
            }
        }

        return new ChargeCtx(state.seed, elapsed, t, easeIn, actMark, actConv, actPinch,
                ringRadius, rotSpeed,
                entPos.x - cam.x, entPos.y - cam.y, entPos.z - cam.z,
                chest.x - cam.x, chest.y - cam.y, chest.z - cam.z,
                tethers, cam);
    }

    private static BlastCtx buildBlastCtx(BlastState state, Vec3 cam, float partialTick) {
        if (state.origin == null) return null;
        float elapsed = (clientTick - state.startTick) + partialTick;
        if (elapsed < 0F) return null;
        return new BlastCtx(state.seed, elapsed,
                state.origin.x - cam.x, state.origin.y - cam.y, state.origin.z - cam.z,
                state.innerRadius, state.outerRadius);
    }

    // ------------------------------------------------------------------
    //  Pass 1: core (alpha-blended, textured pearls)
    // ------------------------------------------------------------------

    private static void emitChargeCore(ChargeCtx c, PoseStack pose, VertexConsumer core,
                                       Vector3f camRight, Vector3f camUp, Vector3f camFwd) {
        pose.pushPose();
        pose.translate(c.entOx, c.entOy, c.entOz);
        Matrix4f m = pose.last().pose();

        // Ground hexagram sigil (act 1 anchor). Alpha is modulated by a two-beat heartbeat so
        // the ritual pulses like the summoning is drawing breath.
        float heartbeat = heartbeatPulse(c.elapsed);
        int sigilAlpha = Math.min(255,
                (int) (Mth.clamp(c.actMark + c.actConv * 0.5F, 0F, 1F) * 200F * heartbeat));
        if (sigilAlpha > 4) {
            AuraShapes.hexagram(core, m, SIGIL_OUTER, c.elapsed * 0.04F, 0.15F,
                    SIGIL_R, SIGIL_G, SIGIL_B, sigilAlpha);
            AuraShapes.sigilRing(core, m, SIGIL_INNER, 32, -c.elapsed * 0.07F, 0.10F,
                    SIGIL_R, SIGIL_G, SIGIL_B, sigilAlpha);
            AuraShapes.sigilSpokes(core, m, 6, 4, 1.4F, SIGIL_OUTER,
                    c.elapsed * 0.04F, 0.10F, SIGIL_R, SIGIL_G, SIGIL_B, (int) (sigilAlpha * 0.85F));
        }

        // Six candle-flame columns at hex vertices. Height ramps with act 1, tips lean inward
        // as the pinch begins.
        float candleT = Mth.clamp((c.elapsed - 4F) / 20F, 0F, 1F);
        if (candleT > 0.05F) {
            float lean = c.actPinch * 0.55F;
            for (int k = 0; k < CANDLE_COUNT; k++) {
                float ang = k * (Mth.TWO_PI / CANDLE_COUNT);
                float bx = Mth.cos(ang) * CANDLE_RADIUS;
                float bz = Mth.sin(ang) * CANDLE_RADIUS;
                for (int p = 0; p < CANDLE_HEIGHT_PEARLS; p++) {
                    float pt = (p + 0.5F) / CANDLE_HEIGHT_PEARLS;
                    float py = pt * CANDLE_HEIGHT_MAX * candleT;
                    // Sway per-pearl: subtle at the base, wider at the tip.
                    float sway = Mth.sin(c.elapsed * 0.20F + k * 1.7F + p * 0.4F) * 0.10F * pt;
                    // Tips lean inward toward the Usher chest during pinch.
                    float leanFactor = lean * pt;
                    float x = bx * (1F - leanFactor) + sway;
                    float z = bz * (1F - leanFactor) + sway * 0.5F;
                    float size = 0.18F * (1F - pt * 0.35F);
                    int alpha = (int) ((1F - pt * 0.55F) * 210F * candleT);
                    // Tip goes hotter (whiter) to read as flame.
                    int rr = (int) Mth.lerp(pt, PEARL_R, FLASH_R);
                    int gg = (int) Mth.lerp(pt, PEARL_G, FLASH_G);
                    int bb = (int) Mth.lerp(pt, PEARL_B, FLASH_B);
                    AuraQuads.billboard(core, m, camRight, camUp, x, py, z, size,
                            rr, gg, bb, alpha);
                }
            }
        }

        // Three orbital rings.
        float ringAlpha = Mth.clamp((c.elapsed - ACT1_END) / 10F, 0F, 1F);
        if (ringAlpha > 0.02F) {
            float ringChest = (float) (c.chestOy - c.entOy);
            forEachRingPearl(c, ringChest, (px, py, pz) -> {
                int alpha = (int) (ringAlpha * 230F);
                AuraQuads.billboard(core, m, camRight, camUp, px, py, pz,
                        PEARL_HALF + c.easeIn * 0.05F, PEARL_R, PEARL_G, PEARL_B, alpha);
            });
        }

        pose.popPose();

        // Nucleus at chest.
        pose.pushPose();
        pose.translate(c.chestOx, c.chestOy, c.chestOz);
        Matrix4f mc2 = pose.last().pose();
        int nucA = (int) (Mth.clamp(0.4F + c.easeIn * 0.6F, 0F, 1F) * 255F);
        AuraQuads.billboard(core, mc2, camRight, camUp, 0F, 0F, 0F,
                0.22F + c.easeIn * 0.45F, FLASH_R, FLASH_G, FLASH_B, nucA);
        pose.popPose();

        // Rift eye at chest - horizontal lens shape that opens as the charge progresses.
        emitRiftEye(c, pose, core, camRight, camUp, true);

        // Lightning arcs (world-space between candles and chest).
        emitLightning(c, pose, core, camFwd, true);

        // Soul tethers from every capturable entity to the Usher's chest.
        emitTethers(c, pose, core, camRight, camUp, camFwd, true);
    }

    private static void emitChargeHalo(ChargeCtx c, PoseStack pose, VertexConsumer halo,
                                       Vector3f camRight, Vector3f camUp, Vector3f camFwd) {
        pose.pushPose();
        pose.translate(c.entOx, c.entOy, c.entOz);
        Matrix4f m = pose.last().pose();

        // Sigil halo.
        float heartbeat = heartbeatPulse(c.elapsed);
        int sigilHalo = Math.min(255,
                (int) (Mth.clamp(c.actMark + c.actConv * 0.5F, 0F, 1F) * PEARL_HALO_A_MAX * heartbeat));
        if (sigilHalo > 4) {
            AuraShapes.hexagram(halo, m, SIGIL_OUTER, c.elapsed * 0.04F, 0.55F,
                    GLOW_R, GLOW_G, GLOW_B, sigilHalo);
            AuraShapes.sigilRing(halo, m, SIGIL_INNER, 32, -c.elapsed * 0.07F, 0.40F,
                    GLOW_R, GLOW_G, GLOW_B, sigilHalo);
        }

        // Candle halos.
        float candleT = Mth.clamp((c.elapsed - 4F) / 20F, 0F, 1F);
        if (candleT > 0.05F) {
            float lean = c.actPinch * 0.55F;
            for (int k = 0; k < CANDLE_COUNT; k++) {
                float ang = k * (Mth.TWO_PI / CANDLE_COUNT);
                float bx = Mth.cos(ang) * CANDLE_RADIUS;
                float bz = Mth.sin(ang) * CANDLE_RADIUS;
                for (int p = 0; p < CANDLE_HEIGHT_PEARLS; p++) {
                    float pt = (p + 0.5F) / CANDLE_HEIGHT_PEARLS;
                    float py = pt * CANDLE_HEIGHT_MAX * candleT;
                    float sway = Mth.sin(c.elapsed * 0.20F + k * 1.7F + p * 0.4F) * 0.10F * pt;
                    float leanFactor = lean * pt;
                    float x = bx * (1F - leanFactor) + sway;
                    float z = bz * (1F - leanFactor) + sway * 0.5F;
                    float size = 0.55F * (1F - pt * 0.25F);
                    int alpha = (int) ((1F - pt * 0.5F) * 150F * candleT);
                    AuraQuads.billboardHalo(halo, m, camRight, camUp, x, py, z, size,
                            GLOW_R, GLOW_G, GLOW_B, alpha);
                }
            }
        }

        // Ring halos.
        float ringAlpha = Mth.clamp((c.elapsed - ACT1_END) / 10F, 0F, 1F);
        if (ringAlpha > 0.02F) {
            float ringChest = (float) (c.chestOy - c.entOy);
            forEachRingPearl(c, ringChest, (px, py, pz) -> {
                int alpha = (int) (ringAlpha * 150F);
                AuraQuads.billboardHalo(halo, m, camRight, camUp, px, py, pz,
                        PEARL_HALO_HALF + c.easeIn * 0.15F, GLOW_R, GLOW_G, GLOW_B, alpha);
            });
        }

        // Rift cracks: floating dark-violet slashes around the Usher during acts 2 and 3.
        float crackT = Mth.clamp((c.elapsed - ACT1_END) / 12F, 0F, 1F);
        if (crackT > 0.05F) {
            int crackCount = 12;
            for (int i = 0; i < crackCount; i++) {
                float h = (float) hash01(c.seed * 7919L + i * 131L);
                float h2 = (float) hash01(c.seed * 104729L + i * 227L);
                float h3 = (float) hash01(c.seed * 15485867L + i * 337L);
                float rad = 1.5F + h * 3.5F;
                float ang = h2 * Mth.TWO_PI + c.elapsed * 0.02F;
                float x = Mth.cos(ang) * rad;
                float z = Mth.sin(ang) * rad;
                float y = 0.4F + h3 * 2.6F;
                // Flicker so cracks blink in and out.
                float flick = 0.5F + 0.5F * Mth.sin(c.elapsed * 0.6F + i * 1.7F);
                int alpha = (int) (crackT * flick * 180F);
                float size = 0.35F + h * 0.25F;
                AuraQuads.billboardHalo(halo, m, camRight, camUp, x, y, z, size,
                        RIFT_R, RIFT_G, RIFT_B, alpha);
            }
        }

        pose.popPose();

        // Nucleus halo.
        pose.pushPose();
        pose.translate(c.chestOx, c.chestOy, c.chestOz);
        Matrix4f mc2 = pose.last().pose();
        int nucAlpha = (int) (Mth.clamp(0.5F + c.easeIn * 0.5F, 0F, 1F) * 200F);
        AuraQuads.billboardHalo(halo, mc2, camRight, camUp, 0F, 0F, 0F,
                1.1F + c.easeIn * 1.8F, FLASH_R, FLASH_G, FLASH_B, nucAlpha);
        pose.popPose();

        // Rift eye halo.
        emitRiftEye(c, pose, halo, camRight, camUp, false);
        // Lightning halos.
        emitLightning(c, pose, halo, camFwd, false);
        // Soul tether halos.
        emitTethers(c, pose, halo, camRight, camUp, camFwd, false);
    }

    private static void emitChargeShell(ChargeCtx c, PoseStack pose, VertexConsumer shell) {
        pose.pushPose();
        pose.translate(c.entOx, c.entOy + 0.02, c.entOz);
        Matrix4f m = pose.last().pose();

        // Bright glow disc under the Usher, growing with the charge - reads as heat scorching
        // the ground before the blast.
        int discAlpha = (int) (Mth.clamp(c.actMark + c.actConv * 0.5F + c.actPinch * 0.5F, 0F, 1F)
                * 130F);
        if (discAlpha > 3) {
            AuraQuads.groundDisc(shell, m, SIGIL_OUTER * 1.2F, 48,
                    GLOW_R, GLOW_G, GLOW_B, discAlpha,
                    GLOW_R / 3, GLOW_G / 3, GLOW_B / 3, 0);
        }

        pose.popPose();
    }

    // ------------------------------------------------------------------
    //  Lightning + tether emission (shared between core + halo passes)
    // ------------------------------------------------------------------

    private static void emitLightning(ChargeCtx c, PoseStack pose, VertexConsumer vc,
                                      Vector3f camFwd, boolean isCore) {
        if (c.actConv < 0.15F && c.actPinch < 0.05F) return;
        // Bolt frequency ramps sharply during the pinch.
        int period = c.actPinch > 0.5F ? 3 : 8;
        int nowTick = (int) c.elapsed;
        // Two active bolts at a time - one long-holding, one fresh - so the sky between candles
        // reads busy without a flicker artifact.
        for (int slot = 0; slot < 3; slot++) {
            int boltId = nowTick / period - slot;
            if (boltId < 0) continue;
            int ageTicks = nowTick - boltId * period;
            if (ageTicks < 0 || ageTicks > 6) continue;
            float boltAlpha = 1F - ageTicks / 6F;
            long boltSeed = c.seed * 2654435761L + boltId * 1103515245L;

            int from = (int) (hash01(boltSeed) * CANDLE_COUNT);
            int to = (int) (hash01(boltSeed * 3L + 7L) * CANDLE_COUNT);
            boolean chestTarget = hash01(boltSeed * 5L + 11L) < 0.6F || from == to;

            float aa = from * (Mth.TWO_PI / CANDLE_COUNT);
            float ax = Mth.cos(aa) * CANDLE_RADIUS;
            float az = Mth.sin(aa) * CANDLE_RADIUS;
            float ay = 0.4F + (float) hash01(boltSeed * 7L + 3L) * 2.0F;

            float bx, by, bz;
            if (chestTarget) {
                bx = 0F; bz = 0F;
                by = (float) (c.chestOy - c.entOy);
            } else {
                float bang = to * (Mth.TWO_PI / CANDLE_COUNT);
                bx = Mth.cos(bang) * CANDLE_RADIUS;
                bz = Mth.sin(bang) * CANDLE_RADIUS;
                by = 0.4F + (float) hash01(boltSeed * 11L + 5L) * 2.0F;
            }

            pose.pushPose();
            pose.translate(c.entOx, c.entOy, c.entOz);
            Matrix4f m = pose.last().pose();

            if (isCore) {
                int alpha = (int) (boltAlpha * 240F);
                AuraShapes.lightning(vc, m, camFwd,
                        ax, ay, az, bx, by, bz,
                        10, 0.55F, 0.14F,
                        FLASH_R, FLASH_G, FLASH_B, alpha, boltSeed);
            } else {
                int alpha = (int) (boltAlpha * 200F);
                AuraShapes.lightning(vc, m, camFwd,
                        ax, ay, az, bx, by, bz,
                        10, 0.55F, 0.55F,
                        GLOW_R, GLOW_G, GLOW_B, alpha, boltSeed);
            }
            pose.popPose();
        }
    }

    private static void emitTethers(ChargeCtx c, PoseStack pose, VertexConsumer vc,
                                    Vector3f camRight, Vector3f camUp, Vector3f camFwd,
                                    boolean isCore) {
        if (c.tethers.isEmpty()) return;
        // Tethers fade in during act 2 and pull hard during act 3.
        float tetherIn = c.actConv;
        if (tetherIn < 0.05F) return;

        // Draw each tether as pearls flowing from target's chest to the Usher's chest. Positions
        // are already camera-relative so we render into the current (unmodified) pose.
        Matrix4f m = pose.last().pose();
        for (Vec3 target : c.tethers) {
            double ax = target.x - c.cam.x;
            double ay = target.y - c.cam.y;
            double az = target.z - c.cam.z;

            int pearls = 14;
            for (int i = 0; i < pearls; i++) {
                // Flow direction - pearls drift toward the Usher over time.
                float base = (i + 0.5F) / pearls;
                float phase = (c.elapsed * 0.10F) % 1F;
                float t = (base + phase) % 1F;
                // Bezier apex above the midpoint so tethers arc up nicely.
                float bez = 4F * t * (1F - t);
                float x = (float) Mth.lerp(t, ax, c.chestOx);
                float y = (float) (Mth.lerp(t, ay, c.chestOy) + 1.2F * bez);
                float z = (float) Mth.lerp(t, az, c.chestOz);
                float size = isCore ? 0.11F : 0.45F;
                int alpha;
                if (isCore) {
                    alpha = (int) (tetherIn * 210F * (0.6F + 0.4F * bez));
                    AuraQuads.billboard(vc, m, camRight, camUp, x, y, z, size,
                            PEARL_R, PEARL_G, PEARL_B, alpha);
                } else {
                    alpha = (int) (tetherIn * 140F * (0.6F + 0.4F * bez));
                    AuraQuads.billboardHalo(vc, m, camRight, camUp, x, y, z, size,
                            GLOW_R, GLOW_G, GLOW_B, alpha);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  Pass helpers - blast
    // ------------------------------------------------------------------

    private static void emitBlastCore(BlastCtx c, PoseStack pose, VertexConsumer core,
                                      Vector3f camRight, Vector3f camUp, Vector3f camFwd) {
        pose.pushPose();
        pose.translate(c.ox, c.oy + 0.05, c.oz);
        Matrix4f m = pose.last().pose();

        // Primer flash: a very bright nucleus that pinpoints the moment of blast, then fades.
        if (c.elapsed < DETON_PRIMER_TICKS + 4F) {
            float pt = Mth.clamp(c.elapsed / DETON_PRIMER_TICKS, 0F, 1F);
            float primerAlpha = 1F - Mth.clamp((c.elapsed - DETON_PRIMER_TICKS) / 4F, 0F, 1F);
            AuraQuads.billboard(core, m, camRight, camUp, 0F, 1.0F, 0F,
                    0.5F + pt * 2.0F, FLASH_R, FLASH_G, FLASH_B,
                    (int) (primerAlpha * 255F));
        }

        // Six streaked petals launching outward-upward.
        float petalT = Mth.clamp((c.elapsed - DETON_PRIMER_TICKS) / (float) DETON_FADE_TICKS,
                0F, 1F);
        if (petalT > 0F && petalT < 1F) {
            float petalAlpha = 1F - petalT;
            for (int i = 0; i < 8; i++) {
                float ang = i * (Mth.TWO_PI / 8);
                float cang = Mth.cos(ang), sang = Mth.sin(ang);
                float dist = c.outerRadius * (0.15F
                        + 0.85F * (1F - (1F - petalT) * (1F - petalT)));
                float lift = 1.4F * petalT * 1.5F;
                float px = cang * dist;
                float py = lift;
                float pz = sang * dist;
                float vx = cang * 0.6F;
                float vy = 0.35F * (1F - petalT);
                float vz = sang * 0.6F;
                int alpha = (int) (petalAlpha * 240F);
                AuraQuads.streak(core, m, camFwd, px, py, pz, 0.22F,
                        vx, vy, vz, FLASH_R, FLASH_G, FLASH_B, alpha);
            }
        }

        // Six lightning bolts forking outward from the origin.
        if (petalT > 0.02F && petalT < 0.7F) {
            float lightAlpha = 1F - petalT / 0.7F;
            int bolts = 6;
            for (int i = 0; i < bolts; i++) {
                float ang = i * (Mth.TWO_PI / bolts) + c.elapsed * 0.02F;
                float cang = Mth.cos(ang), sang = Mth.sin(ang);
                float len = c.outerRadius * (0.4F + petalT * 0.6F);
                float bx = cang * len;
                float bz = sang * len;
                float by = 1.5F + (float) hash01(c.seed * 3L + i * 17L) * 2.5F;
                AuraShapes.lightning(core, m, camFwd,
                        0F, 1.2F, 0F, bx, by, bz,
                        12, 0.7F, 0.16F, FLASH_R, FLASH_G, FLASH_B,
                        (int) (lightAlpha * 230F), c.seed * 5L + i);
            }
        }

        // Lingering sigil scorch on the ground - fades over SIGIL_LINGER_TICKS.
        float sigilT = Mth.clamp((c.elapsed - DETON_PRIMER_TICKS)
                / (float) (DETON_FADE_TICKS + SIGIL_LINGER_TICKS), 0F, 1F);
        if (sigilT < 1F) {
            float sigilAlpha = (1F - sigilT) * 0.7F;
            int alpha = (int) (sigilAlpha * 220F);
            if (alpha > 4) {
                AuraShapes.hexagram(core, m, SIGIL_OUTER, c.elapsed * 0.04F, 0.15F,
                        SIGIL_R, SIGIL_G, SIGIL_B, alpha);
                AuraShapes.sigilRing(core, m, SIGIL_INNER, 32, -c.elapsed * 0.07F, 0.10F,
                        SIGIL_R, SIGIL_G, SIGIL_B, alpha);
            }
        }

        pose.popPose();
    }

    private static void emitBlastHalo(BlastCtx c, PoseStack pose, VertexConsumer halo,
                                      Vector3f camRight, Vector3f camUp, Vector3f camFwd) {
        pose.pushPose();
        pose.translate(c.ox, c.oy + 0.05, c.oz);
        Matrix4f m = pose.last().pose();

        // Primer flash halo.
        if (c.elapsed < DETON_PRIMER_TICKS + 4F) {
            float pt = Mth.clamp(c.elapsed / DETON_PRIMER_TICKS, 0F, 1F);
            float primerAlpha = 1F - Mth.clamp((c.elapsed - DETON_PRIMER_TICKS) / 4F, 0F, 1F);
            AuraQuads.billboardHalo(halo, m, camRight, camUp, 0F, 1.0F, 0F,
                    2.0F + pt * 4F, FLASH_R, FLASH_G, FLASH_B,
                    (int) (primerAlpha * 220F));
        }

        float petalT = Mth.clamp((c.elapsed - DETON_PRIMER_TICKS) / (float) DETON_FADE_TICKS,
                0F, 1F);
        if (petalT > 0F && petalT < 1F) {
            float petalAlpha = 1F - petalT;
            for (int i = 0; i < 8; i++) {
                float ang = i * (Mth.TWO_PI / 8);
                float cang = Mth.cos(ang), sang = Mth.sin(ang);
                float dist = c.outerRadius * (0.15F
                        + 0.85F * (1F - (1F - petalT) * (1F - petalT)));
                float lift = 1.4F * petalT * 1.5F;
                float px = cang * dist;
                float py = lift;
                float pz = sang * dist;
                AuraQuads.billboardHalo(halo, m, camRight, camUp, px, py, pz,
                        0.7F + petalT * 0.6F, GLOW_R, GLOW_G, GLOW_B,
                        (int) (petalAlpha * 200F));
            }
        }

        if (petalT > 0.02F && petalT < 0.7F) {
            float lightAlpha = 1F - petalT / 0.7F;
            int bolts = 6;
            for (int i = 0; i < bolts; i++) {
                float ang = i * (Mth.TWO_PI / bolts) + c.elapsed * 0.02F;
                float cang = Mth.cos(ang), sang = Mth.sin(ang);
                float len = c.outerRadius * (0.4F + petalT * 0.6F);
                float bx = cang * len;
                float bz = sang * len;
                float by = 1.5F + (float) hash01(c.seed * 3L + i * 17L) * 2.5F;
                AuraShapes.lightning(halo, m, camFwd,
                        0F, 1.2F, 0F, bx, by, bz,
                        12, 0.7F, 0.55F, GLOW_R, GLOW_G, GLOW_B,
                        (int) (lightAlpha * 180F), c.seed * 5L + i);
            }
        }

        float sigilT = Mth.clamp((c.elapsed - DETON_PRIMER_TICKS)
                / (float) (DETON_FADE_TICKS + SIGIL_LINGER_TICKS), 0F, 1F);
        if (sigilT < 1F) {
            float sigilAlpha = (1F - sigilT) * 0.7F;
            int alpha = (int) (sigilAlpha * 180F);
            if (alpha > 4) {
                AuraShapes.hexagram(halo, m, SIGIL_OUTER, c.elapsed * 0.04F, 0.55F,
                        GLOW_R, GLOW_G, GLOW_B, alpha);
            }
        }

        pose.popPose();
    }

    private static void emitBlastShell(BlastCtx c, PoseStack pose, VertexConsumer shell) {
        pose.pushPose();
        pose.translate(c.ox, c.oy + 0.05, c.oz);
        Matrix4f m = pose.last().pose();

        // Silent primer: nothing exploding yet, just a dark pinpoint.
        if (c.elapsed < DETON_PRIMER_TICKS) {
            pose.popPose();
            return;
        }

        // Three concentric dome shells expanding at different speeds. Inner peaks first, outer
        // rolls slower so the eye reads the layered wavefront.
        float t = (c.elapsed - DETON_PRIMER_TICKS) / (float) DETON_DOME_GROWTH_TICKS;
        float ct = Mth.clamp(t, 0F, 1F);
        float easedT = 1F - (float) Math.pow(1F - ct, 3);

        float outerR = Mth.lerp(easedT, 0.1F, c.outerRadius);
        float midR = Mth.lerp(Mth.clamp(easedT * 1.3F, 0F, 1F), 0.1F, c.outerRadius * 0.75F);
        float innerR = Mth.lerp(Mth.clamp(easedT * 1.8F, 0F, 1F), 0.1F, c.innerRadius);

        float fade = 1F - Mth.clamp((c.elapsed - DETON_PRIMER_TICKS) / (float) DETON_FADE_TICKS,
                0F, 1F);

        if (fade > 0.01F) {
            AuraQuads.domeShell(shell, m, outerR, 6, 42,
                    GLOW_R, GLOW_G, GLOW_B, (int) (fade * 200F));
            AuraQuads.domeShell(shell, m, midR, 5, 36,
                    PEARL_R, PEARL_G, PEARL_B, (int) (fade * 180F));
            AuraQuads.domeShell(shell, m, innerR, 4, 30,
                    FLASH_R, FLASH_G, FLASH_B, (int) (fade * 220F));

            // Scorch disc at ground.
            AuraQuads.groundDisc(shell, m, c.outerRadius, 48,
                    FLASH_R, FLASH_G, FLASH_B, (int) (fade * 200F),
                    GLOW_R / 3, GLOW_G / 3, GLOW_B / 3, 0);
        }

        // Ground fracture cracks radiating outward, fading with the shell.
        if (fade > 0.05F) {
            int cracks = 12;
            for (int i = 0; i < cracks; i++) {
                float ang = i * (Mth.TWO_PI / cracks) + (float) hash01(c.seed + i) * 0.5F;
                float rr = c.outerRadius * (0.6F + (float) hash01(c.seed * 2L + i) * 0.4F);
                AuraShapes.groundCrack(shell, m, ang, 0.5F, rr, 0.6F,
                        FLASH_R, FLASH_G, FLASH_B, (int) (fade * 200F));
            }
        }

        pose.popPose();
    }

    // ------------------------------------------------------------------
    //  Ring geometry (shared between core and halo passes)
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface PearlSink {
        void accept(float px, float py, float pz);
    }

    private static void forEachRingPearl(ChargeCtx c, float chestY, PearlSink sink) {
        for (int ring = 0; ring < RING_COUNT; ring++) {
            float tilt = ring * (float) (Math.PI / RING_COUNT);
            float ringPhase = c.elapsed * c.rotSpeed + ring * 2.0944F;
            float ct = Mth.cos(tilt), st = Mth.sin(tilt);
            for (int i = 0; i < PEARLS_PER_RING; i++) {
                float ang = ringPhase + i * (Mth.TWO_PI / PEARLS_PER_RING);
                float c0 = Mth.cos(ang), s0 = Mth.sin(ang);
                float px = c0 * c.ringRadius;
                float pz = s0 * c.ringRadius;
                float py = chestY - pz * st;
                float pzTilted = pz * ct;
                sink.accept(px, py, pzTilted);
            }
        }
    }

    private static double hash01(long v) {
        long z = (v + 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    /**
     * Two-beat heartbeat multiplier for sigil alpha - a strong pulse followed by a weaker
     * echo, repeating roughly once per second. Sits in [1.0, 1.5] so it always brightens, never
     * dims. The rate ramps up over the course of the charge so the ritual reads as accelerating.
     */
    private static float heartbeatPulse(float elapsed) {
        if (elapsed < 4F) return 1F;
        float rate = 0.4F + Mth.clamp(elapsed / (float) ACT3_END, 0F, 1F) * 0.6F;
        float phase = elapsed * rate;
        float s1 = Math.max(0F, Mth.sin(phase));
        float s2 = Math.max(0F, Mth.sin(phase * 2F + 1.2F));
        return 1F + 0.30F * s1 + 0.20F * s2;
    }

    /**
     * Rift eye at the Usher's chest - a horizontal lens-shaped ellipse of pearls that opens
     * during the charge, with a brilliant pupil at the centre. Framed in the camera basis so it
     * always reads as a wide horizontal eye regardless of viewing angle.
     */
    private static void emitRiftEye(ChargeCtx c, PoseStack pose, VertexConsumer vc,
                                    Vector3f camRight, Vector3f camUp, boolean isCore) {
        float openT = Mth.clamp(c.elapsed / (float) ACT3_END, 0F, 1F);
        if (openT < 0.04F) return;
        // Eye lids peel apart cubically so the opening accelerates as the pinch approaches.
        float ease = openT * openT * (3F - 2F * openT);
        float rx = Mth.lerp(ease, 0.18F, 1.15F);
        float ry = Mth.lerp(ease, 0.04F, 0.32F);

        pose.pushPose();
        pose.translate(c.chestOx, c.chestOy, c.chestOz);
        Matrix4f m = pose.last().pose();

        int rimPearls = 26;
        for (int i = 0; i < rimPearls; i++) {
            float ang = i * (Mth.TWO_PI / rimPearls);
            float ex = Mth.cos(ang) * rx;
            float ey = Mth.sin(ang) * ry;
            // Place each rim pearl in world by projecting the 2D ellipse onto the camera basis
            // vectors, so the eye always reads horizontal from the observer's POV.
            float wx = camRight.x * ex + camUp.x * ey;
            float wy = camRight.y * ex + camUp.y * ey;
            float wz = camRight.z * ex + camUp.z * ey;
            if (isCore) {
                int alpha = (int) (ease * 240F);
                AuraQuads.billboard(vc, m, camRight, camUp, wx, wy, wz, 0.10F,
                        RIFT_R, RIFT_G, RIFT_B, alpha);
            } else {
                int alpha = (int) (ease * 140F);
                AuraQuads.billboardHalo(vc, m, camRight, camUp, wx, wy, wz, 0.35F,
                        GLOW_R, GLOW_G, GLOW_B, alpha);
            }
        }

        // Pupil - bright, small, growing to a proper glare during pinch.
        float pupilSize = 0.06F + ease * 0.20F;
        if (isCore) {
            AuraQuads.billboard(vc, m, camRight, camUp, 0F, 0F, 0F, pupilSize,
                    FLASH_R, FLASH_G, FLASH_B, (int) (ease * 255F));
        } else {
            AuraQuads.billboardHalo(vc, m, camRight, camUp, 0F, 0F, 0F, pupilSize * 4F,
                    GLOW_R, GLOW_G, GLOW_B, (int) (ease * 210F));
        }

        pose.popPose();
    }

    private record ChargeState(int startTick, int durationTicks, long seed) {}

    private record BlastState(int startTick, Vec3 origin,
                              float innerRadius, float outerRadius, long seed) {}

    private record ChargeCtx(long seed, float elapsed, float t, float easeIn,
                             float actMark, float actConv, float actPinch,
                             float ringRadius, float rotSpeed,
                             double entOx, double entOy, double entOz,
                             double chestOx, double chestOy, double chestOz,
                             List<Vec3> tethers, Vec3 cam) {}

    private record BlastCtx(long seed, float elapsed,
                            double ox, double oy, double oz,
                            float innerRadius, float outerRadius) {}
}
