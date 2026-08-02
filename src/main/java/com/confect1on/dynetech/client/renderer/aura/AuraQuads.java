package com.confect1on.dynetech.client.renderer.aura;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Vertex emission helpers for the aura passes. Everything here is a thin wrapper around
 * {@link VertexConsumer#addVertex} that centralises the mote UV winding and additive halo
 * intensity packing.
 *
 * <p>Halo methods write plain source-alpha RGBA (not premultiplied); the halo RenderType uses
 * {@code SRC_ALPHA, ONE} so alpha modulates the RGB contribution and overlapping halos build a
 * brighter core naturally.
 */
public final class AuraQuads {

    private AuraQuads() {}

    private static final int FULL_LIGHT = LightTexture.FULL_BRIGHT;
    private static final int NO_OVERLAY = OverlayTexture.NO_OVERLAY;

    /** Camera-facing billboard quad. Center is in pose-local space. */
    public static void billboard(VertexConsumer vc, Matrix4f m,
                                 Vector3f right, Vector3f up,
                                 float cx, float cy, float cz, float halfSize,
                                 int r, int g, int b, int alpha) {
        float rx = right.x * halfSize, ry = right.y * halfSize, rz = right.z * halfSize;
        float ux = up.x * halfSize, uy = up.y * halfSize, uz = up.z * halfSize;
        vc.addVertex(m, cx - rx + ux, cy - ry + uy, cz - rz + uz)
                .setColor(r, g, b, alpha).setUv(0F, 0F)
                .setOverlay(NO_OVERLAY).setLight(FULL_LIGHT);
        vc.addVertex(m, cx + rx + ux, cy + ry + uy, cz + rz + uz)
                .setColor(r, g, b, alpha).setUv(1F, 0F)
                .setOverlay(NO_OVERLAY).setLight(FULL_LIGHT);
        vc.addVertex(m, cx + rx - ux, cy + ry - uy, cz + rz - uz)
                .setColor(r, g, b, alpha).setUv(1F, 1F)
                .setOverlay(NO_OVERLAY).setLight(FULL_LIGHT);
        vc.addVertex(m, cx - rx - ux, cy - ry - uy, cz - rz - uz)
                .setColor(r, g, b, alpha).setUv(0F, 1F)
                .setOverlay(NO_OVERLAY).setLight(FULL_LIGHT);
    }

    /**
     * Additive halo billboard. Alpha modulates the RGB contribution in the additive blend.
     * Non-premultiplied; the transparency shard's blend func handles the scaling.
     */
    public static void billboardHalo(VertexConsumer vc, Matrix4f m,
                                     Vector3f right, Vector3f up,
                                     float cx, float cy, float cz, float halfSize,
                                     int r, int g, int b, int alpha) {
        billboard(vc, m, right, up, cx, cy, cz, halfSize, r, g, b, alpha);
    }

    /**
     * Streaked billboard for a moving pearl - stretched along its screen-space velocity so a fast
     * mote reads as a comet trail instead of a jittering dot.
     */
    public static void streak(VertexConsumer vc, Matrix4f m,
                              Vector3f fwd,
                              float cx, float cy, float cz, float halfSize,
                              float velX, float velY, float velZ,
                              int r, int g, int b, int alpha) {
        float projFwd = fwd.x * velX + fwd.y * velY + fwd.z * velZ;
        float vpX = velX - fwd.x * projFwd;
        float vpY = velY - fwd.y * projFwd;
        float vpZ = velZ - fwd.z * projFwd;
        float sp = (float) Math.sqrt(vpX * vpX + vpY * vpY + vpZ * vpZ);
        if (sp < 1.0E-4F) {
            Vector3f right = new Vector3f(-fwd.z, 0F, fwd.x);
            if (right.lengthSquared() < 1.0e-6F) right.set(1F, 0F, 0F);
            right.normalize();
            Vector3f upV = new Vector3f(fwd).cross(right).normalize();
            billboard(vc, m, right, upV, cx, cy, cz, halfSize, r, g, b, alpha);
            return;
        }
        Vector3f dir = new Vector3f(vpX / sp, vpY / sp, vpZ / sp);
        Vector3f up = new Vector3f(fwd).cross(dir).normalize();

        float stretch = Math.min(1F + sp * 6F, 3.5F);
        float lx = dir.x * halfSize * stretch;
        float ly = dir.y * halfSize * stretch;
        float lz = dir.z * halfSize * stretch;
        float ux = up.x * halfSize;
        float uy = up.y * halfSize;
        float uz = up.z * halfSize;

        int a = clampByte((int) (alpha / (1F + (stretch - 1F) * 0.45F)));

        vc.addVertex(m, cx - lx + ux, cy - ly + uy, cz - lz + uz)
                .setColor(r, g, b, a).setUv(0F, 0F)
                .setOverlay(NO_OVERLAY).setLight(FULL_LIGHT);
        vc.addVertex(m, cx + lx + ux, cy + ly + uy, cz + lz + uz)
                .setColor(r, g, b, a).setUv(1F, 0F)
                .setOverlay(NO_OVERLAY).setLight(FULL_LIGHT);
        vc.addVertex(m, cx + lx - ux, cy + ly - uy, cz + lz - uz)
                .setColor(r, g, b, a).setUv(1F, 1F)
                .setOverlay(NO_OVERLAY).setLight(FULL_LIGHT);
        vc.addVertex(m, cx - lx - ux, cy - ly - uy, cz - lz - uz)
                .setColor(r, g, b, a).setUv(0F, 1F)
                .setOverlay(NO_OVERLAY).setLight(FULL_LIGHT);
    }

    /**
     * Horizontal glow disc - a fan of quads at Y=0 in pose-local space. Written into the shell
     * (additive) pass. Center bright, edge fades to zero.
     */
    public static void groundDisc(VertexConsumer vc, Matrix4f m,
                                  float radius, int segments,
                                  int centerR, int centerG, int centerB, int centerA,
                                  int edgeR, int edgeG, int edgeB, int edgeA) {
        float step = (float) (Math.PI * 2.0 / segments);
        for (int i = 0; i < segments; i++) {
            float a0 = i * step;
            float a1 = (i + 1) * step;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            vc.addVertex(m, 0F, 0F, 0F).setColor(centerR, centerG, centerB, centerA);
            vc.addVertex(m, c0 * radius, 0F, s0 * radius).setColor(edgeR, edgeG, edgeB, edgeA);
            vc.addVertex(m, c1 * radius, 0F, s1 * radius).setColor(edgeR, edgeG, edgeB, edgeA);
            vc.addVertex(m, 0F, 0F, 0F).setColor(centerR, centerG, centerB, centerA);
        }
    }

    /**
     * Dome shell - vertical band mesh sweeping around Y, brightest on the rim (fresnel-ish),
     * fading toward the pole so the dome reads as a shell viewed from a level camera.
     */
    public static void domeShell(VertexConsumer vc, Matrix4f m,
                                 float radius, int rings, int segments,
                                 int rr, int gg, int bb, int alpha) {
        int rings2 = Math.max(2, rings);
        int segs = Math.max(4, segments);
        for (int ri = 0; ri < rings2; ri++) {
            float t0 = ri / (float) rings2;
            float t1 = (ri + 1) / (float) rings2;
            float phi0 = t0 * (float) (Math.PI * 0.5);
            float phi1 = t1 * (float) (Math.PI * 0.5);
            float y0 = radius * (float) Math.sin(phi0);
            float y1 = radius * (float) Math.sin(phi1);
            float r0 = radius * (float) Math.cos(phi0);
            float r1 = radius * (float) Math.cos(phi1);
            float f0 = 1F - t0;
            float f1 = 1F - t1;
            int a0 = clampByte((int) (alpha * f0));
            int a1 = clampByte((int) (alpha * f1));
            float step = (float) (Math.PI * 2.0 / segs);
            for (int si = 0; si < segs; si++) {
                float ang0 = si * step;
                float ang1 = (si + 1) * step;
                float c0 = (float) Math.cos(ang0), s0 = (float) Math.sin(ang0);
                float c1 = (float) Math.cos(ang1), s1 = (float) Math.sin(ang1);
                vc.addVertex(m, c0 * r0, y0, s0 * r0).setColor(rr, gg, bb, a0);
                vc.addVertex(m, c1 * r0, y0, s1 * r0).setColor(rr, gg, bb, a0);
                vc.addVertex(m, c1 * r1, y1, s1 * r1).setColor(rr, gg, bb, a1);
                vc.addVertex(m, c0 * r1, y1, s0 * r1).setColor(rr, gg, bb, a1);
            }
        }
    }

    /**
     * World-space vertical column between {@code y0} and {@code y1} at pose-local (0,0). Two
     * crossed quads so the pillar reads volumetric from any angle. Written to the shell pass.
     */
    public static void verticalPillar(VertexConsumer vc, Matrix4f m,
                                      float y0, float y1, float half0, float half1,
                                      int r, int g, int b, int alpha0, int alpha1) {
        vc.addVertex(m, -half0, y0, 0F).setColor(r, g, b, alpha0);
        vc.addVertex(m,  half0, y0, 0F).setColor(r, g, b, alpha0);
        vc.addVertex(m,  half1, y1, 0F).setColor(r, g, b, alpha1);
        vc.addVertex(m, -half1, y1, 0F).setColor(r, g, b, alpha1);

        vc.addVertex(m, 0F, y0, -half0).setColor(r, g, b, alpha0);
        vc.addVertex(m, 0F, y0,  half0).setColor(r, g, b, alpha0);
        vc.addVertex(m, 0F, y1,  half1).setColor(r, g, b, alpha1);
        vc.addVertex(m, 0F, y1, -half1).setColor(r, g, b, alpha1);
    }

    public static int clampByte(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }
}
