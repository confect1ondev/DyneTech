package com.confect1on.dynetech.client.renderer.godhood;

import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.client.GodhoodBurnupManager;
import com.confect1on.dynetech.client.renderer.aura.AuraQuads;
import com.confect1on.dynetech.client.renderer.aura.AuraRenderTypes;
import com.confect1on.dynetech.client.renderer.aura.AuraShapes;
import com.confect1on.dynetech.pehkui.PehkuiCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Celestial ascension visual for the Godhood regeneration. Runs in phases keyed off
 * {@link GodhoodBurnupManager}'s active elapsed clock:
 *
 * <ul>
 *   <li><b>Ignition (0-10t):</b> ground hexagram + inner runic sigil bloom in, embers spark
 *       around the feet, body starts to glow.</li>
 *   <li><b>Burn-up (10-80t):</b> golden pillar of light shoots up, angelic wings unfurl behind
 *       the god, a halo hovers above the head, two floating runic rings orbit around the god's
 *       chest and head at a tilt, twin fire-embers spiral up past the god's shoulders, and six
 *       god-rays radiate outward from the chest.</li>
 *   <li><b>Collapse (80-95t):</b> wings fold in, halo shrinks, everything gathers to a bright
 *       pinpoint at the chest.</li>
 *   <li><b>Detonation (95-115t):</b> three concentric dome shells, twelve outward sunburst
 *       spokes as light beams, a crown ring of streaked petal pearls, a ground scorch disc,
 *       and a slow feather cascade drifting down over the next second.</li>
 * </ul>
 *
 * <p>Drawing is organised in flat passes (core -> halo -> shell). Alternating different
 * {@link net.minecraft.client.renderer.RenderType} buffers inside a single loop crashes with
 * {@code Not building!} because {@link MultiBufferSource.BufferSource#getBuffer} closes the
 * previous type's buffer when a new type is requested.
 */
@EventBusSubscriber(modid = DyneTech.MODID, value = Dist.CLIENT)
public final class GodhoodAuraFx {

    private GodhoodAuraFx() {}

    // Warm palette: molten gold (rings + pearls), pale sun-white (nucleus), amber-red (embers).
    private static final int GOLD_R = 255, GOLD_G = 195, GOLD_B = 70;
    private static final int SUN_R = 255, SUN_G = 245, SUN_B = 210;
    private static final int EMBER_R = 255, EMBER_G = 120, EMBER_B = 40;
    private static final int HALO_R = 255, HALO_G = 175, HALO_B = 40;

    private static final int MAX_PEARLS_PER_GOD = 240;
    private static final int SPAWN_PER_TICK_BURNUP = 8;
    private static final int SPAWN_PER_TICK_IGNITE = 3;

    // How long feathers linger after detonation.
    private static final int FEATHER_LIFETIME_TICKS = 40;

    private static final Map<Integer, List<Pearl>> POOLS = new HashMap<>();
    private static final Map<Integer, List<Feather>> FEATHERS = new HashMap<>();
    private static final RandomSource RNG = RandomSource.create();
    private static long lastSimTick = Long.MIN_VALUE;

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
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

        long gameTick = mc.level.getGameTime();
        boolean tickBoundary = gameTick != lastSimTick;
        if (tickBoundary) lastSimTick = gameTick;

        var burning = GodhoodBurnupManager.burningIds();

        // Age/spawn/drop CPU pearl pools.
        var it = POOLS.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (!burning.contains(e.getKey())) {
                stepPool(e.getValue(), tickBoundary);
                if (e.getValue().isEmpty()) it.remove();
                continue;
            }
            Entity ent = mc.level.getEntity(e.getKey());
            if (!(ent instanceof LivingEntity le)) continue;
            float elapsed = GodhoodBurnupManager.elapsedTicks(e.getKey(), partialTick);
            spawnPearls(e.getValue(), le, elapsed, tickBoundary);
            stepPool(e.getValue(), tickBoundary);
        }
        for (int id : burning) {
            if (POOLS.containsKey(id)) continue;
            Entity ent = mc.level.getEntity(id);
            if (!(ent instanceof LivingEntity le)) continue;
            List<Pearl> pool = new ArrayList<>();
            POOLS.put(id, pool);
            float elapsed = GodhoodBurnupManager.elapsedTicks(id, partialTick);
            spawnPearls(pool, le, elapsed, tickBoundary);
        }

        // Age/spawn/drop feather pool - separate lifecycle from pearls because feathers persist
        // past the burnup ending.
        var fit = FEATHERS.entrySet().iterator();
        while (fit.hasNext()) {
            var e = fit.next();
            stepFeathers(e.getValue(), tickBoundary);
            if (e.getValue().isEmpty()) fit.remove();
        }
        for (int id : burning) {
            Entity ent = mc.level.getEntity(id);
            if (!(ent instanceof LivingEntity le)) continue;
            float elapsed = GodhoodBurnupManager.elapsedTicks(id, partialTick);
            // Seed feather cascade at the moment of detonation, exactly once.
            if (elapsed >= GodhoodBurnupManager.COLLAPSE_END_TICKS
                    && elapsed < GodhoodBurnupManager.COLLAPSE_END_TICKS + 2
                    && !FEATHERS.containsKey(id) && tickBoundary) {
                FEATHERS.put(id, seedFeathers(le));
            }
        }

        // Precompute per-god context.
        List<Ctx> ctxs = new ArrayList<>();
        for (int id : burning) {
            Entity ent = mc.level.getEntity(id);
            if (!(ent instanceof LivingEntity le)) continue;
            float elapsed = GodhoodBurnupManager.elapsedTicks(id, partialTick);
            ctxs.add(buildCtx(id, le, elapsed, cam, partialTick));
        }
        // Also add ctxs for pools that still have pearls but the god isn't burning anymore.
        for (var e : POOLS.entrySet()) {
            if (burning.contains(e.getKey())) continue;
            Entity ent = mc.level.getEntity(e.getKey());
            if (!(ent instanceof LivingEntity le)) continue;
            ctxs.add(buildCtx(e.getKey(), le, -1F, cam, partialTick));
        }

        // Pass 1: alpha core.
        VertexConsumer core = buffers.getBuffer(AuraRenderTypes.pearlCore());
        for (Ctx c : ctxs) emitCore(c, pose, core, camRight, camUp, camFwd, cam);
        buffers.endBatch(AuraRenderTypes.pearlCore());

        // Pass 2: additive halo.
        VertexConsumer halo = buffers.getBuffer(AuraRenderTypes.pearlHalo());
        for (Ctx c : ctxs) emitHalo(c, pose, halo, camRight, camUp, camFwd, cam);
        buffers.endBatch(AuraRenderTypes.pearlHalo());

        // Pass 3: shell (pillar, domes, god-rays, scorch, ground fractures).
        VertexConsumer shell = buffers.getBuffer(AuraRenderTypes.shell());
        for (Ctx c : ctxs) emitShell(c, pose, shell, cam);
        buffers.endBatch(AuraRenderTypes.shell());
    }

    // ------------------------------------------------------------------
    //  Context
    // ------------------------------------------------------------------

    private static Ctx buildCtx(int id, LivingEntity le, float elapsed, Vec3 cam, float partialTick) {
        float scale = PehkuiCompat.getScale(le);
        float h = le.getBbHeight() * scale;
        float w = le.getBbWidth() * scale;
        double px = le.xo + (le.getX() - le.xo) * partialTick;
        double py = le.yo + (le.getY() - le.yo) * partialTick;
        double pz = le.zo + (le.getZ() - le.zo) * partialTick;
        float yawRad = (float) Math.toRadians(le.yBodyRot);
        // Right vector (world XZ) of the god's facing.
        float bodyRightX = -Mth.cos(yawRad);
        float bodyRightZ = -Mth.sin(yawRad);
        // Forward vector.
        float bodyFwdX = -Mth.sin(yawRad);
        float bodyFwdZ = Mth.cos(yawRad);
        List<Pearl> pool = POOLS.getOrDefault(id, java.util.Collections.emptyList());
        List<Feather> feathers = FEATHERS.getOrDefault(id, java.util.Collections.emptyList());
        return new Ctx(id, elapsed, h, w,
                px - cam.x, py - cam.y, pz - cam.z,
                bodyRightX, bodyRightZ, bodyFwdX, bodyFwdZ,
                pool, feathers);
    }

    // ------------------------------------------------------------------
    //  Pass 1: core
    // ------------------------------------------------------------------

    private static void emitCore(Ctx c, PoseStack pose, VertexConsumer core,
                                 Vector3f camRight, Vector3f camUp, Vector3f camFwd, Vec3 cam) {
        // Free-floating pearls.
        pose.pushPose();
        Matrix4f m = pose.last().pose();
        for (Pearl p : c.pool) {
            float alpha = pearlAlpha(p);
            if (alpha <= 0.01F) continue;
            float cx = (float) (p.x - cam.x);
            float cy = (float) (p.y - cam.y);
            float cz = (float) (p.z - cam.z);
            int r = p.ember ? EMBER_R : GOLD_R;
            int g = p.ember ? EMBER_G : GOLD_G;
            int b = p.ember ? EMBER_B : GOLD_B;
            int a = (int) (alpha * 230F);
            AuraQuads.billboard(core, m, camRight, camUp, cx, cy, cz, p.size, r, g, b, a);
        }
        // Falling feathers.
        for (Feather f : c.feathers) {
            float alpha = feathAlpha(f);
            if (alpha <= 0.01F) continue;
            float fx = (float) (f.x - cam.x);
            float fy = (float) (f.y - cam.y);
            float fz = (float) (f.z - cam.z);
            int a = (int) (alpha * 240F);
            AuraQuads.streak(core, m, camFwd, fx, fy, fz, 0.28F,
                    f.vx * 3F, f.vy * 3F, f.vz * 3F, GOLD_R, GOLD_G, GOLD_B, a);
        }
        pose.popPose();

        if (c.elapsed < 0F) return;

        // Ground sigil under the god (ignition + burnup).
        float sigilT = sigilProgress(c.elapsed);
        if (sigilT > 0.05F) {
            pose.pushPose();
            pose.translate(c.ox, c.oy + 0.06, c.oz);
            Matrix4f gm = pose.last().pose();
            float rOuter = Math.max(2.0F, c.w * 2.2F);
            float rInner = Math.max(1.2F, c.w * 1.5F);
            int alpha = (int) (sigilT * 220F);
            AuraShapes.hexagram(core, gm, rOuter, c.elapsed * 0.05F, 0.14F,
                    GOLD_R, GOLD_G, GOLD_B, alpha);
            AuraShapes.sigilRing(core, gm, rInner, 36, -c.elapsed * 0.08F, 0.10F,
                    GOLD_R, GOLD_G, GOLD_B, alpha);
            AuraShapes.sigilSpokes(core, gm, 12, 3, rInner * 0.4F, rOuter,
                    c.elapsed * 0.05F, 0.09F, GOLD_R, GOLD_G, GOLD_B, (int) (alpha * 0.85F));
            pose.popPose();
        }

        // Body pearls: halo around head, wings, floating runic rings.
        pose.pushPose();
        pose.translate(c.ox, c.oy, c.oz);
        Matrix4f bm = pose.last().pose();

        float bodyT = pillarProgress(c.elapsed);
        if (bodyT > 0.05F) {
            // Halo above head.
            float headY = c.h * 1.15F;
            int haloSegs = 28;
            float haloRadius = c.w * 0.9F;
            for (int i = 0; i < haloSegs; i++) {
                float ang = c.elapsed * 0.08F + i * (Mth.TWO_PI / haloSegs);
                float x = Mth.cos(ang) * haloRadius;
                float z = Mth.sin(ang) * haloRadius;
                int a = (int) (bodyT * 235F);
                AuraQuads.billboard(core, bm, camRight, camUp, x, headY, z, 0.11F,
                        HALO_R, HALO_G, HALO_B, a);
            }

            // Angelic wings behind the god - two pearl arcs sweeping outward and up.
            float shoulderY = c.h * 0.78F;
            float sX = c.bodyRightX * (c.w * 0.4F);
            float sZ = c.bodyRightZ * (c.w * 0.4F);
            // Wings extend backward + outward + upward. Backward direction = -body forward.
            float backX = -c.bodyFwdX;
            float backZ = -c.bodyFwdZ;
            float wingSpan = 2.8F * bodyT;
            float wingBack = 1.8F * bodyT;
            float wingRise = 1.6F * bodyT;
            int wingPearls = 24;
            int wA = (int) (bodyT * 210F);

            // Right wing (outer edge).
            AuraShapes.pearlArc3d(core, bm, camRight, camUp,
                    sX, shoulderY, sZ,
                    sX + c.bodyRightX * wingSpan + backX * wingBack, shoulderY + wingRise,
                    sZ + c.bodyRightZ * wingSpan + backZ * wingBack,
                    0F, 1.0F, 0F,
                    wingPearls, 0.14F, GOLD_R, GOLD_G, GOLD_B, wA);
            // Right wing (inner edge, slightly higher apex for a wing silhouette).
            AuraShapes.pearlArc3d(core, bm, camRight, camUp,
                    sX, shoulderY, sZ,
                    sX + c.bodyRightX * wingSpan * 0.65F + backX * wingBack * 0.65F,
                    shoulderY + wingRise * 0.4F,
                    sZ + c.bodyRightZ * wingSpan * 0.65F + backZ * wingBack * 0.65F,
                    0F, 0.5F, 0F,
                    wingPearls / 2, 0.12F, GOLD_R, GOLD_G, GOLD_B, (int) (wA * 0.85F));
            // Left wing (mirror).
            AuraShapes.pearlArc3d(core, bm, camRight, camUp,
                    -sX, shoulderY, -sZ,
                    -sX - c.bodyRightX * wingSpan + backX * wingBack, shoulderY + wingRise,
                    -sZ - c.bodyRightZ * wingSpan + backZ * wingBack,
                    0F, 1.0F, 0F,
                    wingPearls, 0.14F, GOLD_R, GOLD_G, GOLD_B, wA);
            AuraShapes.pearlArc3d(core, bm, camRight, camUp,
                    -sX, shoulderY, -sZ,
                    -sX - c.bodyRightX * wingSpan * 0.65F + backX * wingBack * 0.65F,
                    shoulderY + wingRise * 0.4F,
                    -sZ - c.bodyRightZ * wingSpan * 0.65F + backZ * wingBack * 0.65F,
                    0F, 0.5F, 0F,
                    wingPearls / 2, 0.12F, GOLD_R, GOLD_G, GOLD_B, (int) (wA * 0.85F));

            // Twin ember spirals: two vertical helices climbing past the god's shoulders.
            emitEmberSpiral(core, bm, camRight, camUp,
                    c.elapsed, c.h, c.w, 1, bodyT);
            emitEmberSpiral(core, bm, camRight, camUp,
                    c.elapsed, c.h, c.w, -1, bodyT);
        }
        pose.popPose();
    }

    private static void emitEmberSpiral(VertexConsumer vc, Matrix4f m,
                                        Vector3f camRight, Vector3f camUp,
                                        float elapsed, float h, float w, int dir, float intensity) {
        int steps = 26;
        float baseY = h * 0.55F;
        float topY = h * 2.4F;
        float baseR = w * 1.1F;
        for (int i = 0; i < steps; i++) {
            float t = i / (float) steps;
            float y = Mth.lerp(t, baseY, topY);
            // Spiral rotates with time and offsets between the two mirrored spirals by pi.
            float ang = dir * (elapsed * 0.15F + t * 6.0F) + (dir < 0 ? Mth.PI : 0F);
            float r = baseR * (1F - t * 0.55F);
            float x = Mth.cos(ang) * r;
            float z = Mth.sin(ang) * r;
            int a = (int) (intensity * (1F - t * 0.35F) * 220F);
            // Emit both hot embers and cooler gold along the spiral for variety.
            int rr = (i & 1) == 0 ? EMBER_R : GOLD_R;
            int gg = (i & 1) == 0 ? EMBER_G : GOLD_G;
            int bb = (i & 1) == 0 ? EMBER_B : GOLD_B;
            AuraQuads.billboard(vc, m, camRight, camUp, x, y, z, 0.13F, rr, gg, bb, a);
        }
    }

    // ------------------------------------------------------------------
    //  Pass 2: halo
    // ------------------------------------------------------------------

    private static void emitHalo(Ctx c, PoseStack pose, VertexConsumer halo,
                                 Vector3f camRight, Vector3f camUp, Vector3f camFwd, Vec3 cam) {
        // Pearl halos.
        pose.pushPose();
        Matrix4f m = pose.last().pose();
        for (Pearl p : c.pool) {
            float alpha = pearlAlpha(p);
            if (alpha <= 0.01F) continue;
            float cx = (float) (p.x - cam.x);
            float cy = (float) (p.y - cam.y);
            float cz = (float) (p.z - cam.z);
            int r = p.ember ? EMBER_R : GOLD_R;
            int g = p.ember ? EMBER_G : GOLD_G;
            int b = p.ember ? EMBER_B : GOLD_B;
            int a = (int) (alpha * 170F);
            AuraQuads.billboardHalo(halo, m, camRight, camUp, cx, cy, cz, p.size * 3.5F,
                    r, g, b, a);
        }
        for (Feather f : c.feathers) {
            float alpha = feathAlpha(f);
            if (alpha <= 0.01F) continue;
            float fx = (float) (f.x - cam.x);
            float fy = (float) (f.y - cam.y);
            float fz = (float) (f.z - cam.z);
            int a = (int) (alpha * 130F);
            AuraQuads.billboardHalo(halo, m, camRight, camUp, fx, fy, fz, 1.0F,
                    GOLD_R, GOLD_G, GOLD_B, a);
        }
        pose.popPose();

        if (c.elapsed < 0F) return;

        // Ground sigil halo.
        float sigilT = sigilProgress(c.elapsed);
        if (sigilT > 0.05F) {
            pose.pushPose();
            pose.translate(c.ox, c.oy + 0.06, c.oz);
            Matrix4f gm = pose.last().pose();
            float rOuter = Math.max(2.0F, c.w * 2.2F);
            float rInner = Math.max(1.2F, c.w * 1.5F);
            int alpha = (int) (sigilT * 160F);
            AuraShapes.hexagram(halo, gm, rOuter, c.elapsed * 0.05F, 0.55F,
                    GOLD_R, GOLD_G, GOLD_B, alpha);
            AuraShapes.sigilRing(halo, gm, rInner, 36, -c.elapsed * 0.08F, 0.40F,
                    GOLD_R, GOLD_G, GOLD_B, alpha);
            pose.popPose();
        }

        // Body halos: halo above head, wings, floating tilted runic rings.
        pose.pushPose();
        pose.translate(c.ox, c.oy, c.oz);
        Matrix4f bm = pose.last().pose();

        float bodyT = pillarProgress(c.elapsed);
        if (bodyT > 0.05F) {
            float headY = c.h * 1.15F;
            int haloSegs = 28;
            float haloRadius = c.w * 0.9F;
            for (int i = 0; i < haloSegs; i++) {
                float ang = c.elapsed * 0.08F + i * (Mth.TWO_PI / haloSegs);
                float x = Mth.cos(ang) * haloRadius;
                float z = Mth.sin(ang) * haloRadius;
                int a = (int) (bodyT * 150F);
                AuraQuads.billboardHalo(halo, bm, camRight, camUp, x, headY, z, 0.50F,
                        HALO_R, HALO_G, HALO_B, a);
            }

            // Wing halos - same arcs, larger halo pearls.
            float shoulderY = c.h * 0.78F;
            float sX = c.bodyRightX * (c.w * 0.4F);
            float sZ = c.bodyRightZ * (c.w * 0.4F);
            float backX = -c.bodyFwdX;
            float backZ = -c.bodyFwdZ;
            float wingSpan = 2.8F * bodyT;
            float wingBack = 1.8F * bodyT;
            float wingRise = 1.6F * bodyT;
            int wingPearls = 24;
            int wA = (int) (bodyT * 140F);
            AuraShapes.pearlArc3d(halo, bm, camRight, camUp,
                    sX, shoulderY, sZ,
                    sX + c.bodyRightX * wingSpan + backX * wingBack, shoulderY + wingRise,
                    sZ + c.bodyRightZ * wingSpan + backZ * wingBack,
                    0F, 1.0F, 0F,
                    wingPearls, 0.55F, GOLD_R, GOLD_G, GOLD_B, wA);
            AuraShapes.pearlArc3d(halo, bm, camRight, camUp,
                    -sX, shoulderY, -sZ,
                    -sX - c.bodyRightX * wingSpan + backX * wingBack, shoulderY + wingRise,
                    -sZ - c.bodyRightZ * wingSpan + backZ * wingBack,
                    0F, 1.0F, 0F,
                    wingPearls, 0.55F, GOLD_R, GOLD_G, GOLD_B, wA);

            // Floating runic rings: two tilted rings orbiting the god at two heights.
            emitFloatingRing(halo, bm, c.elapsed * 0.10F, c.h * 0.55F, c.w * 1.5F, 0.35F,
                    HALO_R, HALO_G, HALO_B, (int) (bodyT * 150F));
            emitFloatingRing(halo, bm, -c.elapsed * 0.07F + 1.57F, c.h * 1.05F, c.w * 1.2F, -0.4F,
                    HALO_R, HALO_G, HALO_B, (int) (bodyT * 150F));
        }
        pose.popPose();
    }

    private static void emitFloatingRing(VertexConsumer halo, Matrix4f m,
                                         float phase, float y, float radius, float tilt,
                                         int r, int g, int b, int alpha) {
        int segs = 22;
        float ct = Mth.cos(tilt), st = Mth.sin(tilt);
        for (int i = 0; i < segs; i++) {
            float ang = phase + i * (Mth.TWO_PI / segs);
            float x = Mth.cos(ang) * radius;
            float zPlanar = Mth.sin(ang) * radius;
            float py = y + zPlanar * -st;
            float z = zPlanar * ct;
            addPointGlow(halo, m, x, py, z, 0.35F, r, g, b, alpha);
        }
    }

    // Two crossed quads (XY + XZ) so the point reads roughly round from any camera angle without
    // needing the caller to supply a billboard basis.
    private static void addPointGlow(VertexConsumer vc, Matrix4f m,
                                     float x, float y, float z, float half,
                                     int r, int g, int b, int alpha) {
        int light = LightTexture.FULL_BRIGHT;
        int overlay = OverlayTexture.NO_OVERLAY;
        vc.addVertex(m, x - half, y - half, z).setColor(r, g, b, alpha).setUv(0F, 0F)
                .setOverlay(overlay).setLight(light);
        vc.addVertex(m, x + half, y - half, z).setColor(r, g, b, alpha).setUv(1F, 0F)
                .setOverlay(overlay).setLight(light);
        vc.addVertex(m, x + half, y + half, z).setColor(r, g, b, alpha).setUv(1F, 1F)
                .setOverlay(overlay).setLight(light);
        vc.addVertex(m, x - half, y + half, z).setColor(r, g, b, alpha).setUv(0F, 1F)
                .setOverlay(overlay).setLight(light);
        vc.addVertex(m, x - half, y, z - half).setColor(r, g, b, alpha).setUv(0F, 0F)
                .setOverlay(overlay).setLight(light);
        vc.addVertex(m, x + half, y, z - half).setColor(r, g, b, alpha).setUv(1F, 0F)
                .setOverlay(overlay).setLight(light);
        vc.addVertex(m, x + half, y, z + half).setColor(r, g, b, alpha).setUv(1F, 1F)
                .setOverlay(overlay).setLight(light);
        vc.addVertex(m, x - half, y, z + half).setColor(r, g, b, alpha).setUv(0F, 1F)
                .setOverlay(overlay).setLight(light);
    }

    // ------------------------------------------------------------------
    //  Pass 3: shell (pillar, domes, god-rays, ground scorch)
    // ------------------------------------------------------------------

    private static void emitShell(Ctx c, PoseStack pose, VertexConsumer shell, Vec3 cam) {
        if (c.elapsed < 0F) return;

        pose.pushPose();
        pose.translate(c.ox, c.oy, c.oz);
        Matrix4f m = pose.last().pose();

        float pillarT = pillarProgress(c.elapsed);
        if (pillarT > 0.01F) {
            // Pillar of light.
            float pillarHalfWidth = c.w * 0.42F;
            float pillarHeight = c.h * 5.5F;
            int segments = 10;
            for (int s = 0; s < segments; s++) {
                float t0 = s / (float) segments;
                float t1 = (s + 1) / (float) segments;
                float y0 = t0 * pillarHeight;
                float y1 = t1 * pillarHeight;
                int a0 = (int) ((1F - t0) * pillarT * 220F);
                int a1 = (int) ((1F - t1) * pillarT * 220F);
                float half0 = pillarHalfWidth * (1F - t0 * 0.4F);
                float half1 = pillarHalfWidth * (1F - t1 * 0.4F);
                AuraQuads.verticalPillar(shell, m, y0, y1, half0, half1,
                        GOLD_R, GOLD_G, GOLD_B, a0, a1);
            }
            // Central column - extra opaque core so the beam reads solid, not just a haze.
            for (int s = 0; s < segments; s++) {
                float t0 = s / (float) segments;
                float t1 = (s + 1) / (float) segments;
                float y0 = t0 * pillarHeight;
                float y1 = t1 * pillarHeight;
                int a0 = (int) ((1F - t0) * pillarT * 255F);
                int a1 = (int) ((1F - t1) * pillarT * 255F);
                AuraQuads.verticalPillar(shell, m, y0, y1,
                        pillarHalfWidth * 0.3F, pillarHalfWidth * 0.2F,
                        SUN_R, SUN_G, SUN_B, a0, a1);
            }

            // Six god-rays shooting outward from the chest, slowly rotating.
            float chestY = c.h * 0.55F;
            int rays = 6;
            for (int i = 0; i < rays; i++) {
                float ang = c.elapsed * 0.03F + i * (Mth.TWO_PI / rays);
                Vector3f dir = new Vector3f(Mth.cos(ang), 0.15F, Mth.sin(ang)).normalize();
                Vector3f cross = new Vector3f(0F, 1F, 0F).cross(dir).normalize();
                AuraShapes.lightBeam(shell, m, 0F, chestY, 0F,
                        dir, cross, 6.0F, 0.9F, 0.05F,
                        GOLD_R, GOLD_G, GOLD_B, (int) (pillarT * 140F));
            }

            // Ground scorch disc under god - warms the floor even without a sigil.
            pose.pushPose();
            pose.translate(0F, 0.03F, 0F);
            Matrix4f gm = pose.last().pose();
            AuraQuads.groundDisc(shell, gm, c.w * 3.5F, 48,
                    GOLD_R, GOLD_G, GOLD_B, (int) (pillarT * 150F),
                    GOLD_R / 4, GOLD_G / 4, GOLD_B / 4, 0);
            pose.popPose();

            // Ground fracture cracks radiating outward, brightening with the burnup.
            int fractures = 10;
            for (int i = 0; i < fractures; i++) {
                float ang = i * (Mth.TWO_PI / fractures) + c.elapsed * 0.005F;
                float rr = c.w * 3.0F + (float) hash01(c.id * 11L + i) * 1.5F;
                AuraShapes.groundCrack(shell, m, ang, 0.4F, rr, 0.35F,
                        GOLD_R, GOLD_G, GOLD_B, (int) (pillarT * 200F));
            }
        }

        // Detonation shell.
        float t0 = GodhoodBurnupManager.COLLAPSE_END_TICKS;
        float t1 = GodhoodBurnupManager.EXPLOSION_END_TICKS;
        if (c.elapsed >= t0 && c.elapsed <= t1 + 4) {
            float dt = Mth.clamp((c.elapsed - t0) / (t1 - t0), 0F, 1F);
            float easedT = 1F - (float) Math.pow(1F - dt, 3);
            float outerR = Mth.lerp(easedT, 0.2F, 16F);
            float midR = Mth.lerp(Mth.clamp(easedT * 1.3F, 0F, 1F), 0.15F, 12F);
            float innerR = Mth.lerp(Mth.clamp(easedT * 1.8F, 0F, 1F), 0.1F, 7F);
            float shellAlpha = 1F - dt;

            AuraQuads.domeShell(shell, m, outerR, 6, 44,
                    GOLD_R, GOLD_G, GOLD_B, (int) (shellAlpha * 200F));
            AuraQuads.domeShell(shell, m, midR, 5, 36,
                    HALO_R, HALO_G, HALO_B, (int) (shellAlpha * 180F));
            AuraQuads.domeShell(shell, m, innerR, 4, 30,
                    SUN_R, SUN_G, SUN_B, (int) (shellAlpha * 240F));

            AuraQuads.groundDisc(shell, m, 16F, 48,
                    SUN_R, SUN_G, SUN_B, (int) (shellAlpha * 220F),
                    GOLD_R / 4, GOLD_G / 4, GOLD_B / 4, 0);

            // Twelve sunburst spokes - long triangular light beams outward.
            int spokes = 12;
            for (int i = 0; i < spokes; i++) {
                float ang = i * (Mth.TWO_PI / spokes);
                Vector3f dir = new Vector3f(Mth.cos(ang), 0.3F, Mth.sin(ang)).normalize();
                Vector3f cross = new Vector3f(0F, 1F, 0F).cross(dir).normalize();
                float len = Mth.lerp(easedT, 2F, 18F);
                AuraShapes.lightBeam(shell, m, 0F, 1.2F, 0F,
                        dir, cross, len, 1.4F, 0.1F,
                        SUN_R, SUN_G, SUN_B, (int) (shellAlpha * 220F));
            }
        }

        pose.popPose();
    }

    // ------------------------------------------------------------------
    //  Simulation
    // ------------------------------------------------------------------

    private static void spawnPearls(List<Pearl> pool, LivingEntity le, float elapsed,
                                    boolean tickBoundary) {
        if (!tickBoundary) return;
        if (elapsed < 0F || elapsed > GodhoodBurnupManager.COLLAPSE_END_TICKS) return;
        boolean ignition = elapsed < GodhoodBurnupManager.IGNITION_END_TICKS;
        boolean burnup = !ignition && elapsed < GodhoodBurnupManager.BURNUP_END_TICKS;
        int budget = pool.size() >= MAX_PEARLS_PER_GOD ? 0
                : ignition ? SPAWN_PER_TICK_IGNITE
                : burnup ? SPAWN_PER_TICK_BURNUP : 1;
        if (budget <= 0) return;

        float scale = PehkuiCompat.getScale(le);
        float h = le.getBbHeight() * scale;
        float w = le.getBbWidth() * scale;
        float yawRad = (float) Math.toRadians(le.yBodyRot);
        double rightX = -Math.cos(yawRad);
        double rightZ = -Math.sin(yawRad);

        for (int i = 0; i < budget; i++) {
            int emitter = pool.size() % 4;
            double baseX = le.getX();
            double baseY = le.getY();
            double baseZ = le.getZ();
            double sx = baseX, sy = baseY + h * 0.5, sz = baseZ;
            switch (emitter) {
                case 0 -> sy = baseY + h + 0.1;
                case 1 -> {
                    sx = baseX + rightX * (w * 0.4 + 0.35);
                    sz = baseZ + rightZ * (w * 0.4 + 0.35);
                    sy = baseY + h * 0.78 + 0.55;
                }
                case 2 -> {
                    sx = baseX - rightX * (w * 0.4 + 0.35);
                    sz = baseZ - rightZ * (w * 0.4 + 0.35);
                    sy = baseY + h * 0.78 + 0.55;
                }
                default -> sy = baseY + h * 0.5;
            }
            double jx = (RNG.nextDouble() - 0.5) * 0.12;
            double jy = (RNG.nextDouble() - 0.5) * 0.10;
            double jz = (RNG.nextDouble() - 0.5) * 0.12;
            float vx = (float) ((RNG.nextDouble() - 0.5) * 0.03);
            float vy = 0.06F + RNG.nextFloat() * 0.10F;
            float vz = (float) ((RNG.nextDouble() - 0.5) * 0.03);
            float life = 18F + RNG.nextFloat() * 22F;
            float size = 0.11F + RNG.nextFloat() * 0.10F;
            boolean ember = RNG.nextInt(5) == 0;
            pool.add(new Pearl(sx + jx, sy + jy, sz + jz, vx, vy, vz, 0F, life, size, ember));
        }
    }

    private static void stepPool(List<Pearl> pool, boolean tickBoundary) {
        if (!tickBoundary) return;
        for (int i = pool.size() - 1; i >= 0; i--) {
            Pearl p = pool.get(i);
            p.age += 1F;
            if (p.age >= p.life) { pool.remove(i); continue; }
            p.x += p.vx;
            p.y += p.vy;
            p.z += p.vz;
            p.vx *= 0.95F;
            p.vz *= 0.95F;
            p.vy *= 0.98F;
        }
    }

    private static float pearlAlpha(Pearl p) {
        float t = p.age / p.life;
        return t < 0.15F ? t / 0.15F : (t > 0.7F ? (1F - t) / 0.30F : 1F);
    }

    private static List<Feather> seedFeathers(LivingEntity le) {
        List<Feather> out = new ArrayList<>();
        float scale = PehkuiCompat.getScale(le);
        float h = le.getBbHeight() * scale;
        int count = 22;
        for (int i = 0; i < count; i++) {
            double ang = RNG.nextDouble() * Math.PI * 2;
            double r = 1.5 + RNG.nextDouble() * 3.5;
            double sx = le.getX() + Math.cos(ang) * r;
            double sz = le.getZ() + Math.sin(ang) * r;
            double sy = le.getY() + h * (0.6 + RNG.nextDouble() * 1.5);
            float vx = (float) ((RNG.nextDouble() - 0.5) * 0.06);
            float vz = (float) ((RNG.nextDouble() - 0.5) * 0.06);
            float vy = -0.03F - RNG.nextFloat() * 0.05F;
            out.add(new Feather(sx, sy, sz, vx, vy, vz, 0F, FEATHER_LIFETIME_TICKS));
        }
        return out;
    }

    private static void stepFeathers(List<Feather> pool, boolean tickBoundary) {
        if (!tickBoundary) return;
        for (int i = pool.size() - 1; i >= 0; i--) {
            Feather f = pool.get(i);
            f.age += 1F;
            if (f.age >= f.life) { pool.remove(i); continue; }
            // Gentle sway from a per-feather sine so feathers drift laterally as they fall.
            float sway = Mth.sin(f.age * 0.15F + (float) f.x * 0.4F) * 0.02F;
            f.x += f.vx + sway;
            f.z += f.vz + sway * 0.5F;
            f.y += f.vy;
            f.vx *= 0.98F;
            f.vz *= 0.98F;
        }
    }

    private static float feathAlpha(Feather f) {
        float t = f.age / f.life;
        return t < 0.1F ? t / 0.1F : (t > 0.6F ? (1F - t) / 0.40F : 1F);
    }

    private static float sigilProgress(float elapsed) {
        if (elapsed < 0F) return 0F;
        if (elapsed < GodhoodBurnupManager.IGNITION_END_TICKS) {
            return elapsed / (float) GodhoodBurnupManager.IGNITION_END_TICKS;
        }
        if (elapsed < GodhoodBurnupManager.BURNUP_END_TICKS) return 1F;
        if (elapsed < GodhoodBurnupManager.COLLAPSE_END_TICKS) {
            float t = (elapsed - GodhoodBurnupManager.BURNUP_END_TICKS)
                    / (float) (GodhoodBurnupManager.COLLAPSE_END_TICKS
                            - GodhoodBurnupManager.BURNUP_END_TICKS);
            return 1F - t;
        }
        return 0F;
    }

    private static float pillarProgress(float elapsed) {
        if (elapsed < GodhoodBurnupManager.IGNITION_END_TICKS) {
            return elapsed / (float) GodhoodBurnupManager.IGNITION_END_TICKS;
        }
        if (elapsed < GodhoodBurnupManager.BURNUP_END_TICKS) {
            return 1F + 0.08F * Mth.sin(elapsed * 0.75F);
        }
        if (elapsed < GodhoodBurnupManager.COLLAPSE_END_TICKS) {
            float t = (elapsed - GodhoodBurnupManager.BURNUP_END_TICKS)
                    / (float) (GodhoodBurnupManager.COLLAPSE_END_TICKS
                            - GodhoodBurnupManager.BURNUP_END_TICKS);
            return Mth.lerp(t, 1F, 0F);
        }
        return 0F;
    }

    private static double hash01(long v) {
        long z = (v + 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    private static final class Pearl {
        double x, y, z;
        float vx, vy, vz;
        float age, life, size;
        boolean ember;

        Pearl(double x, double y, double z, float vx, float vy, float vz,
              float age, float life, float size, boolean ember) {
            this.x = x; this.y = y; this.z = z;
            this.vx = vx; this.vy = vy; this.vz = vz;
            this.age = age; this.life = life; this.size = size; this.ember = ember;
        }
    }

    private static final class Feather {
        double x, y, z;
        float vx, vy, vz;
        float age, life;

        Feather(double x, double y, double z, float vx, float vy, float vz,
                float age, float life) {
            this.x = x; this.y = y; this.z = z;
            this.vx = vx; this.vy = vy; this.vz = vz;
            this.age = age; this.life = life;
        }
    }

    private record Ctx(int id, float elapsed, float h, float w,
                       double ox, double oy, double oz,
                       float bodyRightX, float bodyRightZ,
                       float bodyFwdX, float bodyFwdZ,
                       List<Pearl> pool, List<Feather> feathers) {}
}
