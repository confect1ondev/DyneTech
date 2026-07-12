package com.confect1on.dynetech.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import com.confect1on.dynetech.blockentity.StructureShrinkerBlockEntity;
import com.confect1on.dynetech.client.ClientSettings;

public class StructureShrinkerBlockEntityRenderer implements BlockEntityRenderer<StructureShrinkerBlockEntity> {

    private static final float OUTLINE_THICKNESS = 0.06F;

    public StructureShrinkerBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(StructureShrinkerBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        if (!ClientSettings.showSelectionBox()) return;
        BlockPos rel1 = be.getSelectionStartRelative();
        BlockPos rel2 = be.getSelectionEndRelative();
        if (rel1.equals(BlockPos.ZERO) && rel2.equals(BlockPos.ZERO)) return;

        int minX = Math.min(rel1.getX(), rel2.getX());
        int minY = Math.min(rel1.getY(), rel2.getY());
        int minZ = Math.min(rel1.getZ(), rel2.getZ());
        int maxX = Math.max(rel1.getX(), rel2.getX());
        int maxY = Math.max(rel1.getY(), rel2.getY());
        int maxZ = Math.max(rel1.getZ(), rel2.getZ());
        AABB box = new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);

        float t = Minecraft.getInstance().player != null
                ? (Minecraft.getInstance().player.tickCount + partial) / 10F
                : partial;
        float alpha = 0.65F + (Mth.sin(t) + 1F) / 6F;

        RenderHelper.drawThickWireBox(pose, buffers, box, OUTLINE_THICKNESS, 1F, 0F, 0F, alpha);
    }

    @Override
    public boolean shouldRenderOffScreen(StructureShrinkerBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }

    @Override
    public AABB getRenderBoundingBox(StructureShrinkerBlockEntity be) {
        return AABB.INFINITE;
    }
}
