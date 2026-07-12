package com.confect1on.dynetech.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.client.ClientStructureCache;
import com.confect1on.dynetech.client.DiscoPulseManager;
import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.entity.ShrunkenStructureEntity;
import com.confect1on.dynetech.pehkui.PehkuiCompat;
import com.confect1on.dynetech.storage.ShrunkenStructureRef;
import com.confect1on.dynetech.storage.StructureBlob;

/**
 * Renders the world entity at natural block-size (Pehkui does the mini-scaling).
 */
public class ShrunkenStructureEntityRenderer extends EntityRenderer<ShrunkenStructureEntity> {

    private static final ResourceLocation FALLBACK = DyneTech.id("textures/entity/shrunken_structure.png");

    public ShrunkenStructureEntityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(ShrunkenStructureEntity entity, float yaw, float partial, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        ShrunkenStructureRef ref = entity.getItem().get(DTDataComponents.SHRUNKEN_STRUCTURE.get());
        if (ref == null) {
            super.render(entity, yaw, partial, pose, buffers, light);
            return;
        }

        StructureBlob blob = ClientStructureCache.get(ref.id());
        if (blob == null) {
            ClientStructureCache.requestIfMissing(ref.id());
            super.render(entity, yaw, partial, pose, buffers, light);
            return;
        }

        pose.pushPose();
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(entity.getYRot()));
        MiniStructureRenderer.render(blob, pose, buffers, light, OverlayTexture.NO_OVERLAY, false);
        DiscoPulseManager.renderStructurePulses(
                entity.getId(), PehkuiCompat.getScale(entity), blob,
                pose, buffers, partial);
        pose.popPose();

        super.render(entity, yaw, partial, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(ShrunkenStructureEntity entity) {
        return FALLBACK;
    }
}
