package com.confect1on.dynetech.client.renderer.usher;

import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.client.renderer.aura.AuraQuads;
import com.confect1on.dynetech.client.renderer.aura.AuraRenderTypes;
import com.confect1on.dynetech.dimension.UsherInterior;
import com.confect1on.dynetech.dimension.UsherSectionAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Ambience for the inside of an Usher cell. Layered, purely client-side, and active only
 * while the local player is in {@link UsherInterior#DIMENSION}:
 *
 * <ul>
 *   <li><b>Void motes:</b> sparse violet dust drifting through the dark, world-anchored and
 *       wrapping around the camera. The whole field breathes on a slow cycle.</li>
 *   <li><b>Murk veils:</b> large, near-black translucent sheets sliding through the void.
 *       They read as depth - the darkness has folds in it - and briefly swallow motes and
 *       eyes that pass behind them.</li>
 *   <li><b>Wisps:</b> a few slow violet lights tracing closed wandering paths through the
 *       cell, each dragging a fading trail. Stateless: the paths are closed-form curves, so
 *       a trail is just the same curve sampled a few ticks into the past.</li>
 *   <li><b>Curtains:</b> small aurora ribbons drifting through the cell air, echoing the
 *       big banners on the horizon shader. Bright along the lower hem, dissolving upward,
 *       rippling as they wander.</li>
 *   <li><b>Watchers:</b> pairs of lens eyes that peel open in the dark, blink, and fade.
 *       Rarely one is much larger and further out. Walking toward any of them snuffs it,
 *       and something skitters away from where it was.</li>
 *   <li><b>Skitters:</b> faint pale streaks that dart across peripheral vision and are gone
 *       before they can be looked at.</li>
 *   <li><b>Void tears:</b> thin vertical cracks of violet light that flicker far off against
 *       the walls, gutter, and seal again.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DyneTech.MODID, value = Dist.CLIENT)
public final class UsherInteriorAmbienceManager {

    private UsherInteriorAmbienceManager() {}

    // Palette: violet dust, near-black murk, cold rims, white-violet glare, deep tear light.
    private static final int MOTE_R = 96, MOTE_G = 52, MOTE_B = 150;
    private static final int MURK_R = 10, MURK_G = 5, MURK_B = 18;
    private static final int RIM_R = 120, RIM_G = 58, RIM_B = 190;
    private static final int PUPIL_R = 235, PUPIL_G = 210, PUPIL_B = 255;
    private static final int SKITTER_R = 175, SKITTER_G = 155, SKITTER_B = 215;
    private static final int TEAR_R = 150, TEAR_G = 70, TEAR_B = 230;

    private static final int MOTE_COUNT = 72;
    private static final float MOTE_WRAP = 26.0F;
    private static final float MOTE_FADE_DIST = 12.0F;

    private static final int MURK_COUNT = 12;
    private static final float MURK_WRAP = 30.0F;

    private static final int WISP_COUNT = 5;
    private static final int WISP_TRAIL = 16;
    private static final float WISP_TRAIL_STEP = 2.4F;
    private static final int WISP_R = 160, WISP_G = 90, WISP_B = 230;

    private static final int CURTAIN_COUNT = 3;
    private static final int CURTAIN_COLUMNS = 12;
    private static final int CURTAIN_R = 130, CURTAIN_G = 55, CURTAIN_B = 215;

    private static final int MAX_EYES = 3;
    private static final int EYE_SPAWN_CHANCE = 240;
    private static final int EYE_FADE_IN = 30;
    private static final int EYE_FADE_OUT = 40;
    private static final float EYE_SNUFF_DIST = 3.5F;
    private static final float FLAME_CLEARANCE = 9.0F;

    private static final int MAX_SKITTERS = 2;
    private static final int SKITTER_SPAWN_CHANCE = 420;
    private static final float SKITTER_SPEED = 0.9F;

    private static final int MAX_TEARS = 2;
    private static final int TEAR_SPAWN_CHANCE = 600;

    private static final Random RANDOM = new Random();
    private static final List<Eye> EYES = new ArrayList<>();
    private static final List<Skitter> SKITTERS = new ArrayList<>();
    private static final List<Tear> TEARS = new ArrayList<>();
    private static int clientTick;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        clientTick++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null
                || !mc.level.dimension().equals(UsherInterior.DIMENSION)) {
            EYES.clear();
            SKITTERS.clear();
            TEARS.clear();
            return;
        }

        Vec3 player = mc.player.position();

        Iterator<Eye> it = EYES.iterator();
        while (it.hasNext()) {
            Eye eye = it.next();
            boolean snuffed = eye.closeTick < 0
                    && eye.pos.distanceToSqr(player) < EYE_SNUFF_DIST * EYE_SNUFF_DIST;
            if (snuffed) {
                eye.closeTick = clientTick;
                spawnFleeSkitter(eye, player);
            } else if (eye.closeTick < 0 && clientTick - eye.birthTick > eye.lifeTicks) {
                eye.closeTick = clientTick;
            }
            if (eye.closeTick >= 0 && clientTick - eye.closeTick > EYE_FADE_OUT) {
                it.remove();
            }
        }
        SKITTERS.removeIf(s -> clientTick - s.startTick > s.lifeTicks);
        TEARS.removeIf(t -> clientTick - t.startTick > t.lifeTicks);

        if (EYES.size() < MAX_EYES && RANDOM.nextInt(EYE_SPAWN_CHANCE) == 0) {
            Eye eye = trySpawnEye(player);
            if (eye != null) EYES.add(eye);
        }
        if (SKITTERS.size() < MAX_SKITTERS && RANDOM.nextInt(SKITTER_SPAWN_CHANCE) == 0) {
            Skitter s = trySpawnSkitter(player, mc.player.getLookAngle());
            if (s != null) SKITTERS.add(s);
        }
        if (TEARS.size() < MAX_TEARS && RANDOM.nextInt(TEAR_SPAWN_CHANCE) == 0) {
            Tear t = trySpawnTear(player);
            if (t != null) TEARS.add(t);
        }
    }

    // ------------------------------------------------------------------
    //  Spawning
    // ------------------------------------------------------------------

    private static CellBounds boundsFor(Vec3 player) {
        int index = Math.round((float) (player.x / UsherSectionAllocator.SECTION_SPACING_X));
        double cx = UsherSectionAllocator.sectionCenterX(index);
        return new CellBounds(cx, UsherSectionAllocator.CENTER_Z,
                UsherSectionAllocator.floorY(),
                cx + 5.0, UsherSectionAllocator.CENTER_Z + 0.5);
    }

    /**
     * A watcher position 6-13 blocks out (9-14 for the rare large one), inside the cell air
     * volume and away from the preset's flame. A cramped or lit corner just means no eye
     * this time.
     */
    private static Eye trySpawnEye(Vec3 player) {
        CellBounds cell = boundsFor(player);
        boolean large = RANDOM.nextFloat() < 0.10F;

        for (int attempt = 0; attempt < 8; attempt++) {
            double ang = RANDOM.nextDouble() * Math.PI * 2.0;
            double dist = large ? 9.0 + RANDOM.nextDouble() * 5.0
                    : 6.0 + RANDOM.nextDouble() * 7.0;
            double x = player.x + Math.cos(ang) * dist;
            double z = player.z + Math.sin(ang) * dist;
            double y = player.y + 0.5 + RANDOM.nextDouble() * (large ? 12.0 : 8.0);

            x = Mth.clamp(x, cell.cx - 13.5, cell.cx + 13.5);
            z = Mth.clamp(z, cell.cz - 13.5, cell.cz + 13.5);
            y = Mth.clamp(y, cell.floor + 2.5, cell.floor + 28.0);

            double dFlameSq = (x - cell.flameX) * (x - cell.flameX)
                    + (z - cell.flameZ) * (z - cell.flameZ);
            boolean nearFlame = dFlameSq < FLAME_CLEARANCE * FLAME_CLEARANCE
                    && y < cell.floor + 7.0;
            double dPlayerSq = (x - player.x) * (x - player.x)
                    + (z - player.z) * (z - player.z);
            if (nearFlame || dPlayerSq < 5.0 * 5.0) continue;

            Eye eye = new Eye();
            eye.pos = new Vec3(x, y, z);
            eye.birthTick = clientTick;
            eye.lifeTicks = large ? 300 + RANDOM.nextInt(300) : 100 + RANDOM.nextInt(200);
            eye.seed = RANDOM.nextLong();
            eye.scale = large ? 0.85F + RANDOM.nextFloat() * 0.45F
                    : 0.35F + RANDOM.nextFloat() * 0.35F;
            return eye;
        }
        return null;
    }

    /** A streak that starts outside the player's focus and darts roughly sideways. */
    private static Skitter trySpawnSkitter(Vec3 player, Vec3 look) {
        for (int attempt = 0; attempt < 8; attempt++) {
            double ang = RANDOM.nextDouble() * Math.PI * 2.0;
            double dist = 7.0 + RANDOM.nextDouble() * 6.0;
            Vec3 pos = player.add(Math.cos(ang) * dist,
                    0.5 + RANDOM.nextDouble() * 3.0, Math.sin(ang) * dist);
            Vec3 toPos = pos.subtract(player).normalize();
            // Peripheral only: never in the middle of the view.
            if (toPos.dot(look) > 0.35) continue;

            double dirAng = RANDOM.nextDouble() * Math.PI * 2.0;
            Vec3 dir = new Vec3(Math.cos(dirAng), (RANDOM.nextDouble() - 0.5) * 0.3,
                    Math.sin(dirAng)).normalize();

            Skitter s = new Skitter();
            s.start = pos;
            s.dir = dir;
            s.startTick = clientTick;
            s.lifeTicks = 8 + RANDOM.nextInt(7);
            return s;
        }
        return null;
    }

    private static void spawnFleeSkitter(Eye eye, Vec3 player) {
        Vec3 away = new Vec3(eye.pos.x - player.x, 0.0, eye.pos.z - player.z);
        if (away.lengthSqr() < 1.0E-4) away = new Vec3(1.0, 0.0, 0.0);
        Skitter s = new Skitter();
        s.start = eye.pos;
        s.dir = away.normalize().add(0.0, 0.15, 0.0).normalize();
        s.startTick = clientTick;
        s.lifeTicks = 9;
        SKITTERS.add(s);
    }

    /** A thin crack of light far off in the dark, biased toward the walls. */
    private static Tear trySpawnTear(Vec3 player) {
        CellBounds cell = boundsFor(player);
        for (int attempt = 0; attempt < 8; attempt++) {
            double ang = RANDOM.nextDouble() * Math.PI * 2.0;
            double dist = 9.0 + RANDOM.nextDouble() * 5.0;
            double x = Mth.clamp(player.x + Math.cos(ang) * dist,
                    cell.cx - 14.0, cell.cx + 14.0);
            double z = Mth.clamp(player.z + Math.sin(ang) * dist,
                    cell.cz - 14.0, cell.cz + 14.0);
            double dSq = (x - player.x) * (x - player.x) + (z - player.z) * (z - player.z);
            if (dSq < 8.0 * 8.0) continue;

            Tear t = new Tear();
            t.pos = new Vec3(x, cell.floor + 2.0 + RANDOM.nextDouble() * 16.0, z);
            t.startTick = clientTick;
            t.lifeTicks = 80 + RANDOM.nextInt(80);
            t.height = 2.0F + RANDOM.nextFloat() * 3.0F;
            t.seed = RANDOM.nextLong();
            return t;
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  Rendering
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(UsherInterior.DIMENSION)) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        float time = clientTick + event.getPartialTick().getGameTimeDeltaPartialTick(false);

        Vector3f camRight = new Vector3f();
        Vector3f camUp = new Vector3f();
        Vector3f camFwd = new Vector3f();
        camera.rotation().transform(1F, 0F, 0F, camRight);
        camera.rotation().transform(0F, 1F, 0F, camUp);
        camera.rotation().transform(0F, 0F, 1F, camFwd);

        // The whole void inhales and exhales on a ~40 second cycle.
        float breath = 0.75F + 0.25F * Mth.sin(time * 0.008F);

        Matrix4f m = pose.last().pose();

        // Core pass: motes and eyes first, then the murk on top so anything behind a veil
        // gets dimmed by its blend (and anything drawn later is culled by its depth).
        VertexConsumer core = buffers.getBuffer(AuraRenderTypes.pearlCore());
        emitMotes(core, m, camRight, camUp, cam, time, breath, true);
        emitWisps(core, m, camRight, camUp, cam, time, breath, true);
        for (Eye eye : EYES) emitEye(eye, core, m, camRight, camUp, cam, time, true);
        for (Skitter s : SKITTERS) emitSkitter(s, core, m, camFwd, cam, time, true);
        emitMurk(core, m, camRight, camUp, cam, time, breath);
        buffers.endBatch(AuraRenderTypes.pearlCore());

        VertexConsumer halo = buffers.getBuffer(AuraRenderTypes.pearlHalo());
        emitMotes(halo, m, camRight, camUp, cam, time, breath, false);
        emitWisps(halo, m, camRight, camUp, cam, time, breath, false);
        for (Eye eye : EYES) emitEye(eye, halo, m, camRight, camUp, cam, time, false);
        for (Skitter s : SKITTERS) emitSkitter(s, halo, m, camFwd, cam, time, false);
        buffers.endBatch(AuraRenderTypes.pearlHalo());

        VertexConsumer shell = buffers.getBuffer(AuraRenderTypes.shell());
        emitCurtains(shell, m, cam, time, breath);
        for (Tear t : TEARS) emitTear(t, pose, shell, cam, time);
        buffers.endBatch(AuraRenderTypes.shell());
    }

    /**
     * World-anchored dust field. Each mote lives at a hashed base position plus a slow drift,
     * wrapped into a {@link #MOTE_WRAP}-sized cube around the camera, so walking through the
     * void moves through the dust rather than dragging it along.
     */
    private static void emitMotes(VertexConsumer vc, Matrix4f m,
                                  Vector3f camRight, Vector3f camUp,
                                  Vec3 cam, float time, float breath, boolean isCore) {
        for (int i = 0; i < MOTE_COUNT; i++) {
            double bx = hash01(i * 3L + 1L) * MOTE_WRAP;
            double by = hash01(i * 3L + 2L) * MOTE_WRAP;
            double bz = hash01(i * 3L + 3L) * MOTE_WRAP;
            double vx = (hash01(i * 7L + 11L) - 0.5) * 0.010;
            double vz = (hash01(i * 7L + 13L) - 0.5) * 0.010;
            double sway = Mth.sin(time * 0.013F + i * 2.7F) * 0.6;

            float rx = wrapRel(bx + vx * time + sway - cam.x, MOTE_WRAP);
            float ry = wrapRel(by - 0.006 * time - cam.y, MOTE_WRAP);
            float rz = wrapRel(bz + vz * time - cam.z, MOTE_WRAP);

            float distSq = rx * rx + ry * ry + rz * rz;
            float fade = 1F - Mth.clamp(distSq / (MOTE_FADE_DIST * MOTE_FADE_DIST), 0F, 1F);
            if (fade < 0.03F) continue;
            float flicker = 0.55F + 0.45F * Mth.sin(time * 0.045F + i * 1.31F);

            if (isCore) {
                int alpha = (int) (fade * flicker * breath * 150F);
                AuraQuads.billboard(vc, m, camRight, camUp, rx, ry, rz, 0.045F,
                        MOTE_R, MOTE_G, MOTE_B, alpha);
            } else {
                int alpha = (int) (fade * flicker * breath * 70F);
                AuraQuads.billboardHalo(vc, m, camRight, camUp, rx, ry, rz, 0.16F,
                        MOTE_R, MOTE_G, MOTE_B, alpha);
            }
        }
    }

    /**
     * Violet lights wandering the cell on slow closed curves, each dragging a trail. The
     * trail is the head's own path a few ticks ago, so it whips around corners naturally.
     */
    private static void emitWisps(VertexConsumer vc, Matrix4f m,
                                  Vector3f camRight, Vector3f camUp,
                                  Vec3 cam, float time, float breath, boolean isCore) {
        CellBounds cell = boundsFor(cam);
        for (int i = 0; i < WISP_COUNT; i++) {
            float pulse = 0.7F + 0.3F * Mth.sin(time * 0.021F + i * 2.3F);
            for (int k = 0; k < WISP_TRAIL; k++) {
                Vec3 p = wispPos(cell, i, time - k * WISP_TRAIL_STEP);
                float rx = (float) (p.x - cam.x);
                float ry = (float) (p.y - cam.y);
                float rz = (float) (p.z - cam.z);

                float distSq = rx * rx + ry * ry + rz * rz;
                // Dissolve right at the camera instead of slapping quads across the view.
                float nearFade = Mth.clamp((distSq - 0.6F) / 2.5F, 0F, 1F);
                if (nearFade < 0.03F) continue;

                float along = 1F - (float) k / WISP_TRAIL;
                float fall = along * along;
                // Faint shimmer running down the trail.
                float shimmer = 0.75F + 0.25F * Mth.sin(time * 0.11F - k * 0.9F + i * 3.1F);

                if (isCore) {
                    int alpha = (int) (nearFade * fall * shimmer * pulse * breath * 165F);
                    if (alpha < 3) continue;
                    AuraQuads.billboard(vc, m, camRight, camUp, rx, ry, rz,
                            0.030F + 0.055F * along, WISP_R, WISP_G, WISP_B, alpha);
                } else {
                    int alpha = (int) (nearFade * fall * shimmer * pulse * breath * 80F);
                    if (alpha < 3) continue;
                    AuraQuads.billboardHalo(vc, m, camRight, camUp, rx, ry, rz,
                            0.10F + 0.20F * along, WISP_R, WISP_G, WISP_B, alpha);
                }
            }
        }
    }

    /**
     * Small aurora ribbons drifting through the cell air: a strip of vertical quads along a
     * slowly rotating, rippling line, bright along the lower hem and dissolving upward.
     * Additive, so two curtains crossing flare where they overlap.
     */
    private static void emitCurtains(VertexConsumer vc, Matrix4f m,
                                     Vec3 cam, float time, float breath) {
        CellBounds cell = boundsFor(cam);
        float[] colX = new float[CURTAIN_COLUMNS];
        float[] colY = new float[CURTAIN_COLUMNS];
        float[] colZ = new float[CURTAIN_COLUMNS];
        float[] colH = new float[CURTAIN_COLUMNS];
        float[] colA = new float[CURTAIN_COLUMNS];

        for (int i = 0; i < CURTAIN_COUNT; i++) {
            long s0 = 8100L + i * 19L;
            double anchorX = cell.cx + Math.sin(time * (0.0021 + hash01(s0) * 0.0018)
                    + hash01(s0 + 2L) * Math.PI * 2.0) * (6.0 + hash01(s0 + 4L) * 5.0);
            double anchorZ = cell.cz + Math.sin(time * (0.0017 + hash01(s0 + 1L) * 0.0021)
                    + hash01(s0 + 3L) * Math.PI * 2.0) * (6.0 + hash01(s0 + 5L) * 5.0);
            double yBase = cell.floor + 3.5 + hash01(s0 + 6L) * 13.0
                    + Math.sin(time * 0.0031F + i * 2.1F) * 1.5;

            float theta = time * (0.0016F + (float) hash01(s0 + 7L) * 0.0014F)
                    + (float) (hash01(s0 + 8L) * Math.PI * 2.0);
            float cosT = Mth.cos(theta), sinT = Mth.sin(theta);
            float length = 3.5F + (float) hash01(s0 + 9L) * 2.5F;
            float height = 2.2F + (float) hash01(s0 + 10L) * 1.6F;
            float pulse = 0.65F + 0.35F * Mth.sin(time * 0.017F + i * 2.6F);

            for (int j = 0; j < CURTAIN_COLUMNS; j++) {
                float s = (j / (float) (CURTAIN_COLUMNS - 1) - 0.5F) * length;
                float ripple = Mth.sin(s * 1.6F + time * 0.055F + i * 1.7F) * 0.45F
                        + Mth.sin(s * 3.1F - time * 0.037F) * 0.20F;
                colX[j] = (float) (anchorX - cam.x) + cosT * s - sinT * ripple;
                colZ[j] = (float) (anchorZ - cam.z) + sinT * s + cosT * ripple;
                colY[j] = (float) (yBase - cam.y)
                        + Mth.sin(s * 1.2F + time * 0.045F + i) * 0.35F;
                colH[j] = height * (0.75F + 0.25F * Mth.sin(s * 2.2F - time * 0.03F + i * 3.3F));

                float edge = Mth.sin((j / (float) (CURTAIN_COLUMNS - 1)) * Mth.PI);
                float shimmer = 0.7F + 0.3F * Mth.sin(time * 0.09F - s * 1.9F + i);
                float distSq = colX[j] * colX[j] + colY[j] * colY[j] + colZ[j] * colZ[j];
                float nearFade = Mth.clamp((distSq - 1F) / 4F, 0F, 1F);
                colA[j] = edge * shimmer * nearFade;
            }

            int baseA = (int) (pulse * breath * 48F);
            for (int j = 0; j < CURTAIN_COLUMNS - 1; j++) {
                int a0 = AuraQuads.clampByte((int) (baseA * colA[j]));
                int a1 = AuraQuads.clampByte((int) (baseA * colA[j + 1]));
                if (a0 < 2 && a1 < 2) continue;
                vc.addVertex(m, colX[j], colY[j], colZ[j])
                        .setColor(CURTAIN_R, CURTAIN_G, CURTAIN_B, a0);
                vc.addVertex(m, colX[j + 1], colY[j + 1], colZ[j + 1])
                        .setColor(CURTAIN_R, CURTAIN_G, CURTAIN_B, a1);
                vc.addVertex(m, colX[j + 1], colY[j + 1] + colH[j + 1], colZ[j + 1])
                        .setColor(CURTAIN_R, CURTAIN_G, CURTAIN_B, (int) (a1 * 0.12F));
                vc.addVertex(m, colX[j], colY[j] + colH[j], colZ[j])
                        .setColor(CURTAIN_R, CURTAIN_G, CURTAIN_B, (int) (a0 * 0.12F));
            }
        }
    }

    /**
     * Closed-form wandering path for wisp {@code i}: three incommensurate sines per axis,
     * amplitudes clamped well inside the walls. No state, so any time can be sampled.
     */
    private static Vec3 wispPos(CellBounds cell, int i, float t) {
        double fx = 0.0050 + hash01(7000L + i * 17L) * 0.0045;
        double fy = 0.0036 + hash01(7001L + i * 17L) * 0.0034;
        double fz = 0.0044 + hash01(7002L + i * 17L) * 0.0048;
        double px = hash01(7003L + i * 17L) * Math.PI * 2.0;
        double py = hash01(7004L + i * 17L) * Math.PI * 2.0;
        double pz = hash01(7005L + i * 17L) * Math.PI * 2.0;
        double ax = 5.0 + hash01(7006L + i * 17L) * 6.0;
        double ay = 3.0 + hash01(7007L + i * 17L) * 4.5;
        double az = 5.0 + hash01(7008L + i * 17L) * 6.0;
        double yBase = cell.floor + 6.0 + hash01(7009L + i * 17L) * 12.0;

        double x = cell.cx + Math.sin(t * fx + px) * ax
                + Math.sin(t * fz * 1.71 + pz) * 1.8;
        double y = yBase + Math.sin(t * fy + py) * ay;
        double z = cell.cz + Math.sin(t * fz + pz) * az
                + Math.cos(t * fx * 1.37 + px) * 1.8;
        return new Vec3(x, y, z);
    }

    /**
     * Large, slow, nearly black sheets sliding through the void. Alpha-blended, so whatever
     * sits behind one dims as it passes - folds in the darkness rather than objects in it.
     */
    private static void emitMurk(VertexConsumer vc, Matrix4f m,
                                 Vector3f camRight, Vector3f camUp,
                                 Vec3 cam, float time, float breath) {
        for (int i = 0; i < MURK_COUNT; i++) {
            double bx = hash01(9000L + i * 3L) * MURK_WRAP;
            double by = hash01(9001L + i * 3L) * MURK_WRAP;
            double bz = hash01(9002L + i * 3L) * MURK_WRAP;
            double vx = (hash01(9100L + i * 7L) - 0.5) * 0.006;
            double vz = (hash01(9200L + i * 7L) - 0.5) * 0.006;

            float rx = wrapRel(bx + vx * time - cam.x, MURK_WRAP);
            float ry = wrapRel(by + 0.003 * time - cam.y, MURK_WRAP);
            float rz = wrapRel(bz + vz * time - cam.z, MURK_WRAP);

            float distSq = rx * rx + ry * ry + rz * rz;
            float fade = 1F - Mth.clamp(distSq / (14F * 14F), 0F, 1F);
            // Also fade when very close so a veil never hard-clips through the camera.
            fade *= Mth.clamp((distSq - 2F) / 6F, 0F, 1F);
            if (fade < 0.03F) continue;

            float size = 2.2F + (float) hash01(9300L + i) * 2.2F;
            float pulse = 0.7F + 0.3F * Mth.sin(time * 0.011F + i * 2.1F);
            int alpha = (int) (fade * pulse * (2F - breath) * 46F);
            AuraQuads.billboard(vc, m, camRight, camUp, rx, ry, rz, size,
                    MURK_R, MURK_G, MURK_B, alpha);
        }
    }

    /**
     * A pair of horizontal lens eyes framed in the camera basis, so the watcher always faces
     * whoever is being watched. Blinks by squashing the lens height.
     */
    private static void emitEye(Eye eye, VertexConsumer vc, Matrix4f m,
                                Vector3f camRight, Vector3f camUp,
                                Vec3 cam, float time, boolean isCore) {
        float open = openAmount(eye, time);
        if (open < 0.02F) return;

        float blinkPhase = time * (0.020F + (float) hash01(eye.seed) * 0.012F)
                + (float) hash01(eye.seed * 3L) * 10F;
        float squint = (float) Math.pow(0.5F + 0.5F * Mth.sin(blinkPhase), 30.0);
        float blink = 1F - 0.92F * squint;

        float ox = (float) (eye.pos.x - cam.x);
        float oy = (float) (eye.pos.y - cam.y);
        float oz = (float) (eye.pos.z - cam.z);

        float rx = eye.scale;
        float ry = eye.scale * 0.30F * open * blink;
        float gap = eye.scale * 1.45F;

        for (int side = -1; side <= 1; side += 2) {
            float ecx = ox + camRight.x * gap * side * 0.5F;
            float ecy = oy + camRight.y * gap * side * 0.5F;
            float ecz = oz + camRight.z * gap * side * 0.5F;

            int rimPearls = 14;
            for (int i = 0; i < rimPearls; i++) {
                float ang = i * (Mth.TWO_PI / rimPearls);
                float ex = Mth.cos(ang) * rx;
                float ey = Mth.sin(ang) * ry;
                float wx = camRight.x * ex + camUp.x * ey;
                float wy = camRight.y * ex + camUp.y * ey;
                float wz = camRight.z * ex + camUp.z * ey;
                if (isCore) {
                    AuraQuads.billboard(vc, m, camRight, camUp,
                            ecx + wx, ecy + wy, ecz + wz, 0.045F,
                            RIM_R, RIM_G, RIM_B, (int) (open * 190F));
                } else {
                    AuraQuads.billboardHalo(vc, m, camRight, camUp,
                            ecx + wx, ecy + wy, ecz + wz, 0.14F,
                            RIM_R, RIM_G, RIM_B, (int) (open * 90F));
                }
            }

            float pupil = eye.scale * 0.22F * (0.6F + 0.4F * open) * blink;
            if (isCore) {
                AuraQuads.billboard(vc, m, camRight, camUp, ecx, ecy, ecz, pupil,
                        PUPIL_R, PUPIL_G, PUPIL_B, (int) (open * blink * 220F));
            } else {
                AuraQuads.billboardHalo(vc, m, camRight, camUp, ecx, ecy, ecz, pupil * 3F,
                        PUPIL_R, PUPIL_G, PUPIL_B, (int) (open * blink * 120F));
            }
        }
    }

    /** A pale streak darting through the dark, stretched along its own motion. */
    private static void emitSkitter(Skitter s, VertexConsumer vc, Matrix4f m,
                                    Vector3f camFwd, Vec3 cam, float time, boolean isCore) {
        float age = time - s.startTick;
        if (age < 0F || age > s.lifeTicks) return;
        float env = Mth.sin((age / s.lifeTicks) * Mth.PI);

        double px = s.start.x + s.dir.x * SKITTER_SPEED * age;
        double py = s.start.y + s.dir.y * SKITTER_SPEED * age;
        double pz = s.start.z + s.dir.z * SKITTER_SPEED * age;

        float rx = (float) (px - cam.x);
        float ry = (float) (py - cam.y);
        float rz = (float) (pz - cam.z);

        float velX = (float) s.dir.x * SKITTER_SPEED;
        float velY = (float) s.dir.y * SKITTER_SPEED;
        float velZ = (float) s.dir.z * SKITTER_SPEED;

        if (isCore) {
            AuraQuads.streak(vc, m, camFwd, rx, ry, rz, 0.09F, velX, velY, velZ,
                    SKITTER_R, SKITTER_G, SKITTER_B, (int) (env * 120F));
        } else {
            AuraQuads.streak(vc, m, camFwd, rx, ry, rz, 0.28F, velX, velY, velZ,
                    SKITTER_R, SKITTER_G, SKITTER_B, (int) (env * 70F));
        }
    }

    /** A guttering vertical crack of violet light, occasionally spiking bright. */
    private static void emitTear(Tear t, PoseStack pose, VertexConsumer shell,
                                 Vec3 cam, float time) {
        float age = time - t.startTick;
        if (age < 0F || age > t.lifeTicks) return;
        float in = Mth.clamp(age / 15F, 0F, 1F);
        float out = Mth.clamp((t.lifeTicks - age) / 25F, 0F, 1F);
        float env = in * out;

        float ph = (float) hash01(t.seed) * 20F;
        float flicker = 0.35F + 0.65F * Mth.abs(Mth.sin(time * 0.9F + ph)
                * Mth.sin(time * 0.23F + ph * 1.7F));
        float spike = (float) Math.pow(0.5F + 0.5F * Mth.sin(time * 0.31F + ph), 24.0);
        float intensity = env * (flicker + spike * 1.5F);
        int alpha = AuraQuads.clampByte((int) (intensity * 60F));
        if (alpha < 3) return;

        pose.pushPose();
        pose.translate(t.pos.x - cam.x, t.pos.y - cam.y, t.pos.z - cam.z);
        Matrix4f m = pose.last().pose();
        AuraQuads.verticalPillar(shell, m, 0F, t.height, 0.12F, 0.02F,
                TEAR_R, TEAR_G, TEAR_B, alpha, (int) (alpha * 0.25F));
        pose.popPose();
    }

    private static float openAmount(Eye eye, float time) {
        float in = Mth.clamp((time - eye.birthTick) / EYE_FADE_IN, 0F, 1F);
        float eased = in * in * (3F - 2F * in);
        if (eye.closeTick < 0) return eased;
        float out = Mth.clamp((time - eye.closeTick) / EYE_FADE_OUT, 0F, 1F);
        return eased * (1F - out * out);
    }

    /** Wraps a camera-relative offset into [-wrap/2, wrap/2). */
    private static float wrapRel(double v, float wrap) {
        double w = v % wrap;
        if (w < 0) w += wrap;
        return (float) (w - wrap * 0.5);
    }

    private static double hash01(long v) {
        long z = v + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    private record CellBounds(double cx, double cz, int floor, double flameX, double flameZ) {}

    private static final class Eye {
        Vec3 pos;
        int birthTick;
        int lifeTicks;
        int closeTick = -1;
        long seed;
        float scale;
    }

    private static final class Skitter {
        Vec3 start;
        Vec3 dir;
        int startTick;
        int lifeTicks;
    }

    private static final class Tear {
        Vec3 pos;
        int startTick;
        int lifeTicks;
        float height;
        long seed;
    }
}
