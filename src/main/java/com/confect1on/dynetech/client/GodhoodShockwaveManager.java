package com.confect1on.dynetech.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import com.confect1on.dynetech.DyneTech;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Client-side shockwave renderer for the Godhood detonation. Server sends one
 * {@code GodhoodDetonation} packet with the two damage-threshold radii; the manager grows
 * both rings from 0 to their target over a short window and fades them out.
 *
 * <p>Two rings are drawn:
 * <ul>
 *   <li>Inner (5 block instant-lethal edge): bright yellow-white, quick expansion.</li>
 *   <li>Outer (15 block falloff edge): warm amber, longer expansion.</li>
 * </ul>
 *
 * <p>Both are rendered as flat annuli on the horizontal plane at the god's Y position so
 * bystanders can see the actual damage zones without depth-parsing a sphere. A ring is
 * more legible than a particle burst for the "here is the boundary" message.
 */
@EventBusSubscriber(modid = DyneTech.MODID, value = Dist.CLIENT)
public final class GodhoodShockwaveManager {

    private GodhoodShockwaveManager() {}

    private static final int RING_SEGMENTS = 64;
    private static final int INNER_DURATION_TICKS = 10;
    private static final int OUTER_DURATION_TICKS = 16;

    private static final Map<Integer, Shockwave> ACTIVE = new HashMap<>();
    private static final RandomSource RNG = RandomSource.create();

    public static void trigger(int entityId, float innerRadius, float outerRadius) {
        Minecraft mc = Minecraft.getInstance();
        long startNanos = System.nanoTime();
        Vec3 origin = null;
        if (mc.level != null) {
            Entity e = mc.level.getEntity(entityId);
            if (e != null) origin = new Vec3(e.getX(), e.getY(), e.getZ());
        }
        ACTIVE.put(entityId, new Shockwave(startNanos, origin, innerRadius, outerRadius));
        // Halo continues into its own explosion phase in step with the rings, so no call
        // to the burnup manager - the two effects intentionally overlap.

        // A small vertical punctuation - a handful of upward end-rod motes at the origin
        // so the moment reads even when the rings are still small.
        if (mc.level != null && origin != null) {
            for (int i = 0; i < 12; i++) {
                double ax = (RNG.nextDouble() - 0.5) * 1.0;
                double az = (RNG.nextDouble() - 0.5) * 1.0;
                mc.level.addParticle(ParticleTypes.END_ROD,
                        origin.x + ax, origin.y + 1.0, origin.z + az,
                        ax * 0.10, 0.25 + RNG.nextDouble() * 0.15, az * 0.10);
            }
        }
    }

    /**
     * Draw both rings during the translucent-blocks render stage so they layer over the
     * ground correctly and against transparent blocks like water without popping.
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (ACTIVE.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.getPosition();

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        long nowNanos = System.nanoTime();

        Iterator<Map.Entry<Integer, Shockwave>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Shockwave> entry = it.next();
            Shockwave sw = entry.getValue();
            if (sw.origin == null) { it.remove(); continue; }

            float ticksElapsed = (nowNanos - sw.startNanos) / 50_000_000F; // 20 tps => 50ms per tick
            if (ticksElapsed > OUTER_DURATION_TICKS + 2) { it.remove(); continue; }

            pose.pushPose();
            pose.translate(sw.origin.x - cam.x, sw.origin.y - cam.y + 0.05, sw.origin.z - cam.z);
            drawRing(pose, buffers, sw.innerRadius, ticksElapsed, INNER_DURATION_TICKS,
                    0xFF, 0xF5, 0xB0, 0.75F);
            drawRing(pose, buffers, sw.outerRadius, ticksElapsed, OUTER_DURATION_TICKS,
                    0xFF, 0x9A, 0x30, 0.55F);
            pose.popPose();
        }

        buffers.endBatch(RenderType.debugQuads());
    }

    /**
     * Draws one expanding ring as a thin filled annulus. The radius grows from 0 to
     * {@code targetRadius} over {@code duration} ticks (ease-out) while thickness holds
     * roughly constant so the ring stays readable at all distances. Alpha fades over the
     * back half of the window.
     */
    private static void drawRing(PoseStack pose, MultiBufferSource buffers,
                                 float targetRadius, float ticksElapsed, int duration,
                                 int r255, int g255, int b255, float alphaScale) {
        if (ticksElapsed >= duration) return;
        float t = ticksElapsed / (float) duration;
        // Ease-out cubic so the ring snaps out fast and slows into its final radius.
        float easedT = 1F - (float) Math.pow(1F - t, 3);
        float currentRadius = targetRadius * easedT;
        if (currentRadius <= 0.1F) return;

        // Thickness scales gently with radius so a bigger ring reads with matching bulk.
        float thickness = 0.35F + currentRadius * 0.05F;
        float rInner = Math.max(0F, currentRadius - thickness * 0.5F);
        float rOuter = currentRadius + thickness * 0.5F;

        float alpha = alphaScale * (t < 0.5F ? 1F : Mth.lerp((t - 0.5F) * 2F, 1F, 0F));
        float rf = r255 / 255F;
        float gf = g255 / 255F;
        float bf = b255 / 255F;

        VertexConsumer vc = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f m = pose.last().pose();

        for (int i = 0; i < RING_SEGMENTS; i++) {
            float a0 = (i / (float) RING_SEGMENTS) * Mth.TWO_PI;
            float a1 = ((i + 1) / (float) RING_SEGMENTS) * Mth.TWO_PI;
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0);
            float c1 = Mth.cos(a1), s1 = Mth.sin(a1);

            float x0i = c0 * rInner, z0i = s0 * rInner;
            float x0o = c0 * rOuter, z0o = s0 * rOuter;
            float x1i = c1 * rInner, z1i = s1 * rInner;
            float x1o = c1 * rOuter, z1o = s1 * rOuter;

            // Top-facing (visible from above): wound CCW when viewed from +Y.
            vc.addVertex(m, x0i, 0F, z0i).setColor(rf, gf, bf, alpha);
            vc.addVertex(m, x0o, 0F, z0o).setColor(rf, gf, bf, alpha);
            vc.addVertex(m, x1o, 0F, z1o).setColor(rf, gf, bf, alpha);
            vc.addVertex(m, x1i, 0F, z1i).setColor(rf, gf, bf, alpha);

            // Bottom-facing (visible from below): reversed winding so face-culling shows this
            // one when the camera is under the ring plane.
            vc.addVertex(m, x1i, 0F, z1i).setColor(rf, gf, bf, alpha);
            vc.addVertex(m, x1o, 0F, z1o).setColor(rf, gf, bf, alpha);
            vc.addVertex(m, x0o, 0F, z0o).setColor(rf, gf, bf, alpha);
            vc.addVertex(m, x0i, 0F, z0i).setColor(rf, gf, bf, alpha);
        }
    }

    private record Shockwave(long startNanos, Vec3 origin, float innerRadius, float outerRadius) {}
}
