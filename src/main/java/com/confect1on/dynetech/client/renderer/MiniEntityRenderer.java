package com.confect1on.dynetech.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.LivingEntity;

/**
 * Renders a preview {@link LivingEntity} at the current pose origin using the vanilla dispatcher.
 * The caller controls positioning and any enclosing scale (Pehkui, item-slot centering, etc).
 */
public final class MiniEntityRenderer {

    private MiniEntityRenderer() {}

    /**
     * @param fitToUnitCube if true, scales down so max(width, height) = 1 — the same normalization
     *                      {@link MiniStructureRenderer} does, so inventory icons render at a
     *                      comparable size regardless of the mob's real dimensions.
     */
    public static void render(LivingEntity preview, PoseStack pose, MultiBufferSource buffers,
                              int light, float partialTick, boolean fitToUnitCube) {
        pose.pushPose();

        if (fitToUnitCube) {
            float maxDim = Math.max(preview.getBbWidth(), preview.getBbHeight());
            if (maxDim > 0F) {
                float scale = 1F / maxDim;
                pose.scale(scale, scale, scale);
            }
        }

        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        // (0, 0, 0) is feet-origin — the dispatcher applies the standard LivingEntity scale/flip
        // itself, so we don't reproduce the 0.9375 / (-1, -1, 1) stack here.
        dispatcher.render(preview, 0, 0, 0, 0F, partialTick, pose, buffers, light);

        pose.popPose();
    }
}
