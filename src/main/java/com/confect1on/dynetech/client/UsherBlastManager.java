package com.confect1on.dynetech.client;

import com.confect1on.dynetech.DyneTech;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Usher Departure blast rings. Same expanding-annulus technique as
 * {@link GodhoodShockwaveManager}, recolored to the Usher's palette: deep black on the inner
 * ring, deep purple on the outer. Kept as its own manager (rather than parameterizing the
 * Godhood one) so future tuning of either effect can move independently.
 */
@EventBusSubscriber(modid = DyneTech.MODID, value = Dist.CLIENT)
public final class UsherBlastManager {

    private UsherBlastManager() {}

    private static final int RING_SEGMENTS = 64;
    private static final int INNER_DURATION_TICKS = 12;
    private static final int OUTER_DURATION_TICKS = 20;

    private static final Map<Integer, Blast> ACTIVE = new HashMap<>();

    public static void trigger(int entityId, float innerRadius, float outerRadius) {
        Minecraft mc = Minecraft.getInstance();
        long startNanos = System.nanoTime();
        Vec3 origin = null;
        if (mc.level != null) {
            Entity e = mc.level.getEntity(entityId);
            if (e != null) origin = new Vec3(e.getX(), e.getY(), e.getZ());
        }
        ACTIVE.put(entityId, new Blast(startNanos, origin, innerRadius, outerRadius));
    }

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

        Iterator<Map.Entry<Integer, Blast>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Blast> entry = it.next();
            Blast b = entry.getValue();
            if (b.origin == null) { it.remove(); continue; }

            float ticksElapsed = (nowNanos - b.startNanos) / 50_000_000F;
            if (ticksElapsed > OUTER_DURATION_TICKS + 2) { it.remove(); continue; }

            pose.pushPose();
            pose.translate(b.origin.x - cam.x, b.origin.y - cam.y + 0.05, b.origin.z - cam.z);
            // Inner ring - near-black core so the danger reads as consuming void.
            drawRing(pose, buffers, b.innerRadius, ticksElapsed, INNER_DURATION_TICKS,
                    0x1A, 0x0B, 0x1F, 0.85F);
            // Outer ring - deep purple falloff edge.
            drawRing(pose, buffers, b.outerRadius, ticksElapsed, OUTER_DURATION_TICKS,
                    0x7B, 0x2F, 0xBE, 0.65F);
            pose.popPose();
        }

        buffers.endBatch(RenderType.debugQuads());
    }

    private static void drawRing(PoseStack pose, MultiBufferSource buffers,
                                 float targetRadius, float ticksElapsed, int duration,
                                 int r255, int g255, int b255, float alphaScale) {
        if (ticksElapsed >= duration) return;
        float t = ticksElapsed / (float) duration;
        float easedT = 1F - (float) Math.pow(1F - t, 3);
        float currentRadius = targetRadius * easedT;
        if (currentRadius <= 0.1F) return;

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

            vc.addVertex(m, x0i, 0F, z0i).setColor(rf, gf, bf, alpha);
            vc.addVertex(m, x0o, 0F, z0o).setColor(rf, gf, bf, alpha);
            vc.addVertex(m, x1o, 0F, z1o).setColor(rf, gf, bf, alpha);
            vc.addVertex(m, x1i, 0F, z1i).setColor(rf, gf, bf, alpha);

            vc.addVertex(m, x1i, 0F, z1i).setColor(rf, gf, bf, alpha);
            vc.addVertex(m, x1o, 0F, z1o).setColor(rf, gf, bf, alpha);
            vc.addVertex(m, x0o, 0F, z0o).setColor(rf, gf, bf, alpha);
            vc.addVertex(m, x0i, 0F, z0i).setColor(rf, gf, bf, alpha);
        }
    }

    private record Blast(long startNanos, Vec3 origin, float innerRadius, float outerRadius) {}
}
