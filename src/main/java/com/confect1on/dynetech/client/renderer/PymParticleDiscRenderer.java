package com.confect1on.dynetech.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import com.confect1on.dynetech.entity.PymParticleDiscEntity;

/**
 * Renders the thrown disc lying flat (like a frisbee) and spinning with its flight speed,
 * instead of the camera-facing billboard that ThrownItemRenderer produces.
 */
public class PymParticleDiscRenderer extends EntityRenderer<PymParticleDiscEntity> {

    private final ItemRenderer itemRenderer;

    public PymParticleDiscRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(PymParticleDiscEntity entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        // Same first-tick suppression as ThrownItemRenderer: avoids the disc flashing in the
        // thrower's face on the spawn tick.
        if (entity.tickCount < 2 && this.entityRenderDispatcher.camera.getEntity().distanceToSqr(entity) < 12.25) {
            return;
        }

        float xRot = Mth.lerp(partialTicks, entity.xRotO, entity.getXRot());
        float yRot = Mth.lerp(partialTicks, entity.yRotO, entity.getYRot());
        float spin = (entity.tickCount + partialTicks) * (float) entity.getDeltaMovement().length() * 72.0F;

        poseStack.pushPose();
        poseStack.translate(0.0F, 0.125F, 0.0F);
        poseStack.scale(0.25F, 0.25F, 0.25F);
        poseStack.mulPose(Axis.YP.rotationDegrees(yRot - 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(xRot));
        poseStack.mulPose(Axis.YP.rotationDegrees(spin));
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
        this.itemRenderer.renderStatic(entity.getItem(), ItemDisplayContext.NONE, packedLight,
                OverlayTexture.NO_OVERLAY, poseStack, buffer, entity.level(), entity.getId());
        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(PymParticleDiscEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
