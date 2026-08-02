package com.confect1on.dynetech.client.renderer.aura;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Composite shape helpers built on {@link AuraQuads}. All positions are pose-local and callers
 * apply their own camera-relative translations before invoking these.
 *
 * <p>Deterministic RNG is used for shape jitter so a given seed produces the same silhouette
 * every frame; the caller decides whether to reseed per-tick (chaotic) or per-effect (stable).
 */
public final class AuraShapes {

    private AuraShapes() {}

    private static final int FULL_LIGHT = LightTexture.FULL_BRIGHT;
    private static final int NO_OVERLAY = OverlayTexture.NO_OVERLAY;

    /**
     * Segmented streaked pearl chain between two points, jittered off the straight line to read
     * as lightning. Works on any pearl RenderType (core or halo) - callers just pass the right
     * VertexConsumer.
     */
    public static void lightning(VertexConsumer vc, Matrix4f m,
                                 Vector3f camFwd,
                                 float ax, float ay, float az,
                                 float bx, float by, float bz,
                                 int segments, float jitter, float size,
                                 int r, int g, int b, int alpha,
                                 long seed) {
        RandomSource rng = deterministic(seed);
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-4F) return;
        Vector3f dir = new Vector3f(dx / len, dy / len, dz / len);
        Vector3f side = new Vector3f(-dir.z, 0F, dir.x);
        if (side.lengthSquared() < 1.0e-6F) side.set(1F, 0F, 0F);
        side.normalize();
        Vector3f upV = new Vector3f(dir).cross(side).normalize();

        float step = 1F / segments;
        float px = ax, py = ay, pz = az;
        for (int i = 1; i <= segments; i++) {
            float t = i * step;
            // Fade jitter toward endpoints so the bolt still terminates at (b, by, bz).
            float wobble = jitter * (1F - Math.abs(t * 2F - 1F));
            float ju = (rng.nextFloat() * 2F - 1F) * wobble;
            float jv = (rng.nextFloat() * 2F - 1F) * wobble;
            float nx = ax + dx * t + side.x * ju + upV.x * jv;
            float ny = ay + dy * t + side.y * ju + upV.y * jv;
            float nz = az + dz * t + side.z * ju + upV.z * jv;
            float mx = (px + nx) * 0.5F;
            float my = (py + ny) * 0.5F;
            float mz = (pz + nz) * 0.5F;
            float sx = nx - px;
            float sy = ny - py;
            float sz = nz - pz;
            AuraQuads.streak(vc, m, camFwd, mx, my, mz, size, sx, sy, sz, r, g, b, alpha);
            px = nx; py = ny; pz = nz;
        }
    }

    /** Pearl chain along a parabolic arc from a to b, apex lifted by {@code apexLift} in Y. */
    public static void pearlArc(VertexConsumer vc, Matrix4f m,
                                Vector3f right, Vector3f up,
                                float ax, float ay, float az,
                                float bx, float by, float bz,
                                float apexLift, int pearls, float size,
                                int r, int g, int b, int alpha) {
        for (int i = 0; i < pearls; i++) {
            float t = (i + 0.5F) / pearls;
            float x = Mth.lerp(t, ax, bx);
            float y = Mth.lerp(t, ay, by) + apexLift * 4F * t * (1F - t);
            float z = Mth.lerp(t, az, bz);
            AuraQuads.billboard(vc, m, right, up, x, y, z, size, r, g, b, alpha);
        }
    }

    /**
     * 3D arc between two points where the apex is displaced by an arbitrary vector (not just Y).
     * Handy for wing silhouettes that curve outward as well as upward.
     */
    public static void pearlArc3d(VertexConsumer vc, Matrix4f m,
                                  Vector3f right, Vector3f up,
                                  float ax, float ay, float az,
                                  float bx, float by, float bz,
                                  float apexX, float apexY, float apexZ,
                                  int pearls, float size,
                                  int r, int g, int b, int alpha) {
        for (int i = 0; i < pearls; i++) {
            float t = (i + 0.5F) / pearls;
            float bez = 4F * t * (1F - t);
            float x = Mth.lerp(t, ax, bx) + apexX * bez;
            float y = Mth.lerp(t, ay, by) + apexY * bez;
            float z = Mth.lerp(t, az, bz) + apexZ * bez;
            AuraQuads.billboard(vc, m, right, up, x, y, z, size, r, g, b, alpha);
        }
    }

    /**
     * Sigil ring - one horizontal ring of {@code segments} pearls at radius {@code radius},
     * rotated by {@code phase}. Pearls are drawn as up-facing ground quads so the ring hugs
     * the floor.
     */
    public static void sigilRing(VertexConsumer vc, Matrix4f m,
                                 float radius, int segments, float phase,
                                 float pearlHalf,
                                 int r, int g, int b, int alpha) {
        for (int i = 0; i < segments; i++) {
            float ang = phase + i * (Mth.TWO_PI / segments);
            float x = Mth.cos(ang) * radius;
            float z = Mth.sin(ang) * radius;
            addUpQuad(vc, m, x, 0F, z, pearlHalf, r, g, b, alpha);
        }
    }

    /**
     * Radial spokes on a horizontal sigil - N small pearls arranged along each spoke, sitting
     * between {@code rInner} and {@code rOuter}.
     */
    public static void sigilSpokes(VertexConsumer vc, Matrix4f m,
                                   int spokes, int pearlsPerSpoke,
                                   float rInner, float rOuter, float phase,
                                   float pearlHalf,
                                   int r, int g, int b, int alpha) {
        for (int s = 0; s < spokes; s++) {
            float ang = phase + s * (Mth.TWO_PI / spokes);
            float c = Mth.cos(ang), sn = Mth.sin(ang);
            for (int i = 0; i < pearlsPerSpoke; i++) {
                float t = pearlsPerSpoke == 1 ? 0.5F : i / (float) (pearlsPerSpoke - 1);
                float rr = Mth.lerp(t, rInner, rOuter);
                addUpQuad(vc, m, c * rr, 0F, sn * rr, pearlHalf, r, g, b, alpha);
            }
        }
    }

    /**
     * Hexagram - a six-pointed star silhouette made of pearl vertices at hex corners plus dense
     * pearls along the two overlapping triangles' edges. Reads as a runic base sigil.
     */
    public static void hexagram(VertexConsumer vc, Matrix4f m,
                                float radius, float phase, float pearlHalf,
                                int r, int g, int b, int alpha) {
        for (int i = 0; i < 6; i++) {
            float ang = phase + i * (Mth.TWO_PI / 6);
            float x = Mth.cos(ang) * radius;
            float z = Mth.sin(ang) * radius;
            addUpQuad(vc, m, x, 0F, z, pearlHalf, r, g, b, alpha);
        }
        // Star edges - dense pearls along each triangle's legs.
        for (int t = 0; t < 2; t++) {
            float offset = t * (Mth.PI / 6F);
            for (int i = 0; i < 3; i++) {
                float a0 = phase + offset + i * (Mth.TWO_PI / 3F);
                float a1 = phase + offset + ((i + 1) % 3) * (Mth.TWO_PI / 3F);
                float x0 = Mth.cos(a0) * radius, z0 = Mth.sin(a0) * radius;
                float x1 = Mth.cos(a1) * radius, z1 = Mth.sin(a1) * radius;
                int pearls = 10;
                for (int p = 1; p < pearls; p++) {
                    float k = p / (float) pearls;
                    float x = Mth.lerp(k, x0, x1);
                    float z = Mth.lerp(k, z0, z1);
                    addUpQuad(vc, m, x, 0F, z, pearlHalf * 0.75F, r, g, b, alpha);
                }
            }
        }
    }

    /**
     * God-ray beam quad from {@code origin} along {@code dir}, tapering from {@code baseWidth}
     * to {@code tipWidth} over {@code length}. Written to the shell RenderType.
     */
    public static void lightBeam(VertexConsumer shell, Matrix4f m,
                                 float ox, float oy, float oz,
                                 Vector3f dir, Vector3f cross,
                                 float length, float baseWidth, float tipWidth,
                                 int r, int g, int b, int baseAlpha) {
        int tipA = Math.max(0, baseAlpha / 6);
        float tipX = ox + dir.x * length;
        float tipY = oy + dir.y * length;
        float tipZ = oz + dir.z * length;
        float bh = baseWidth * 0.5F;
        float th = tipWidth * 0.5F;
        shell.addVertex(m, ox + cross.x * bh, oy + cross.y * bh, oz + cross.z * bh)
                .setColor(r, g, b, baseAlpha);
        shell.addVertex(m, ox - cross.x * bh, oy - cross.y * bh, oz - cross.z * bh)
                .setColor(r, g, b, baseAlpha);
        shell.addVertex(m, tipX - cross.x * th, tipY - cross.y * th, tipZ - cross.z * th)
                .setColor(r, g, b, tipA);
        shell.addVertex(m, tipX + cross.x * th, tipY + cross.y * th, tipZ + cross.z * th)
                .setColor(r, g, b, tipA);
    }

    /**
     * Ground-radial line - a thin scorch crack from origin outward. Two-quad "V" shape drawn
     * horizontally so the crack reads as a filled slit in the floor.
     */
    public static void groundCrack(VertexConsumer shell, Matrix4f m,
                                   float angle, float rInner, float rOuter, float width,
                                   int r, int g, int b, int innerAlpha) {
        float c = Mth.cos(angle);
        float s = Mth.sin(angle);
        float px = c * rInner, pz = s * rInner;
        float qx = c * rOuter, qz = s * rOuter;
        // Perpendicular in the horizontal plane.
        float nx = -s;
        float nz = c;
        float bh = width * 0.5F;
        float outerAlpha = 0;
        shell.addVertex(m, px + nx * bh, 0F, pz + nz * bh).setColor(r, g, b, innerAlpha);
        shell.addVertex(m, px - nx * bh, 0F, pz - nz * bh).setColor(r, g, b, innerAlpha);
        shell.addVertex(m, qx - nx * bh * 0.4F, 0F, qz - nz * bh * 0.4F).setColor(r, g, b, outerAlpha);
        shell.addVertex(m, qx + nx * bh * 0.4F, 0F, qz + nz * bh * 0.4F).setColor(r, g, b, outerAlpha);
    }

    private static void addUpQuad(VertexConsumer vc, Matrix4f m,
                                  float x, float y, float z, float half,
                                  int r, int g, int b, int alpha) {
        vc.addVertex(m, x - half, y, z - half).setColor(r, g, b, alpha).setUv(0F, 0F)
                .setOverlay(NO_OVERLAY).setLight(FULL_LIGHT);
        vc.addVertex(m, x + half, y, z - half).setColor(r, g, b, alpha).setUv(1F, 0F)
                .setOverlay(NO_OVERLAY).setLight(FULL_LIGHT);
        vc.addVertex(m, x + half, y, z + half).setColor(r, g, b, alpha).setUv(1F, 1F)
                .setOverlay(NO_OVERLAY).setLight(FULL_LIGHT);
        vc.addVertex(m, x - half, y, z + half).setColor(r, g, b, alpha).setUv(0F, 1F)
                .setOverlay(NO_OVERLAY).setLight(FULL_LIGHT);
    }

    /** Splitmix64 seeded RNG so a given seed always produces the same jitter. */
    private static RandomSource deterministic(long seed) {
        long z = (seed ^ 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return RandomSource.create(z);
    }
}
