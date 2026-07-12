package com.confect1on.dynetech.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

public final class RenderHelper {

    private RenderHelper() {}

    /** Draws an AABB wireframe as 12 thin filled boxes so thickness is consistent regardless of driver line-width support. */
    public static void drawThickWireBox(PoseStack pose, MultiBufferSource buffers, AABB box, float thickness,
                                        float r, float g, float b, float a) {
        VertexConsumer vc = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f m = pose.last().pose();
        float x1 = (float) box.minX, y1 = (float) box.minY, z1 = (float) box.minZ;
        float x2 = (float) box.maxX, y2 = (float) box.maxY, z2 = (float) box.maxZ;
        float t = thickness * 0.5F;

        // 4 edges along X (top/bottom × front/back)
        cuboid(vc, m, x1 - t, y1 - t, z1 - t, x2 + t, y1 + t, z1 + t, r, g, b, a);
        cuboid(vc, m, x1 - t, y1 - t, z2 - t, x2 + t, y1 + t, z2 + t, r, g, b, a);
        cuboid(vc, m, x1 - t, y2 - t, z1 - t, x2 + t, y2 + t, z1 + t, r, g, b, a);
        cuboid(vc, m, x1 - t, y2 - t, z2 - t, x2 + t, y2 + t, z2 + t, r, g, b, a);
        // 4 vertical edges (along Y)
        cuboid(vc, m, x1 - t, y1 - t, z1 - t, x1 + t, y2 + t, z1 + t, r, g, b, a);
        cuboid(vc, m, x1 - t, y1 - t, z2 - t, x1 + t, y2 + t, z2 + t, r, g, b, a);
        cuboid(vc, m, x2 - t, y1 - t, z1 - t, x2 + t, y2 + t, z1 + t, r, g, b, a);
        cuboid(vc, m, x2 - t, y1 - t, z2 - t, x2 + t, y2 + t, z2 + t, r, g, b, a);
        // 4 edges along Z (top/bottom × left/right)
        cuboid(vc, m, x1 - t, y1 - t, z1, x1 + t, y1 + t, z2, r, g, b, a);
        cuboid(vc, m, x2 - t, y1 - t, z1, x2 + t, y1 + t, z2, r, g, b, a);
        cuboid(vc, m, x1 - t, y2 - t, z1, x1 + t, y2 + t, z2, r, g, b, a);
        cuboid(vc, m, x2 - t, y2 - t, z1, x2 + t, y2 + t, z2, r, g, b, a);
    }

    /** Draws a filled translucent AABB (6 faces) in the current pose frame. */
    public static void drawFilledBox(PoseStack pose, MultiBufferSource buffers, AABB box,
                                     float r, float g, float b, float a) {
        VertexConsumer vc = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f m = pose.last().pose();
        float x1 = (float) box.minX, y1 = (float) box.minY, z1 = (float) box.minZ;
        float x2 = (float) box.maxX, y2 = (float) box.maxY, z2 = (float) box.maxZ;
        cuboid(vc, m, x1, y1, z1, x2, y2, z2, r, g, b, a);
    }

    private static void cuboid(VertexConsumer vc, Matrix4f m,
                               float x1, float y1, float z1, float x2, float y2, float z2,
                               float r, float g, float b, float a) {
        // -Z face
        vc.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(m, x1, y2, z1).setColor(r, g, b, a);
        vc.addVertex(m, x2, y2, z1).setColor(r, g, b, a);
        vc.addVertex(m, x2, y1, z1).setColor(r, g, b, a);
        // +Z face
        vc.addVertex(m, x1, y1, z2).setColor(r, g, b, a);
        vc.addVertex(m, x2, y1, z2).setColor(r, g, b, a);
        vc.addVertex(m, x2, y2, z2).setColor(r, g, b, a);
        vc.addVertex(m, x1, y2, z2).setColor(r, g, b, a);
        // -X face
        vc.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(m, x1, y1, z2).setColor(r, g, b, a);
        vc.addVertex(m, x1, y2, z2).setColor(r, g, b, a);
        vc.addVertex(m, x1, y2, z1).setColor(r, g, b, a);
        // +X face
        vc.addVertex(m, x2, y1, z1).setColor(r, g, b, a);
        vc.addVertex(m, x2, y2, z1).setColor(r, g, b, a);
        vc.addVertex(m, x2, y2, z2).setColor(r, g, b, a);
        vc.addVertex(m, x2, y1, z2).setColor(r, g, b, a);
        // -Y face
        vc.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(m, x2, y1, z1).setColor(r, g, b, a);
        vc.addVertex(m, x2, y1, z2).setColor(r, g, b, a);
        vc.addVertex(m, x1, y1, z2).setColor(r, g, b, a);
        // +Y face
        vc.addVertex(m, x1, y2, z1).setColor(r, g, b, a);
        vc.addVertex(m, x1, y2, z2).setColor(r, g, b, a);
        vc.addVertex(m, x2, y2, z2).setColor(r, g, b, a);
        vc.addVertex(m, x2, y2, z1).setColor(r, g, b, a);
    }
}
