package com.confect1on.dynetech.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import com.confect1on.dynetech.storage.StructureBlob;

/** Shared: walks a StructureBlob and draws each block using BlockRenderDispatcher. */
public final class MiniStructureRenderer {

    private MiniStructureRenderer() {}

    /**
     * Renders the blob centered horizontally at (0, 0, 0). Caller controls the enclosing scale/pose.
     *
     * @param fitToUnitCube if true, scales down so max dimension = 1 (useful for inventory icon);
     *                      if false, renders at natural size (world entity — Pehkui scales the whole thing).
     */
    public static void render(StructureBlob blob, PoseStack pose, MultiBufferSource buffers,
                              int light, int overlay, boolean fitToUnitCube) {
        Vec3i size = blob.size();
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) return;

        pose.pushPose();

        if (fitToUnitCube) {
            float maxDim = Math.max(size.getX(), Math.max(size.getY(), size.getZ()));
            float scale = 1F / maxDim;
            pose.scale(scale, scale, scale);
        }
        pose.translate(-size.getX() / 2.0, 0, -size.getZ() / 2.0);

        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    BlockState state = blob.getBlockState(x, y, z);
                    if (state.isAir()) continue;
                    if (state.getRenderShape() != RenderShape.MODEL) continue;
                    pose.pushPose();
                    pose.translate(x, y, z);
                    dispatcher.renderSingleBlock(state, pose, buffers, light, overlay);
                    pose.popPose();
                }
            }
        }
        pose.popPose();
    }
}
