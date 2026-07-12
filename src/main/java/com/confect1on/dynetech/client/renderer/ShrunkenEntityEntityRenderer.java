package com.confect1on.dynetech.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.client.ClientEntityCache;
import com.confect1on.dynetech.client.DiscoPulseManager;
import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.entity.ShrunkenEntityEntity;
import com.confect1on.dynetech.pehkui.PehkuiCompat;
import com.confect1on.dynetech.storage.ShrunkenEntityRef;

/**
 * World-side renderer for the carrier. Renders the captured mob at natural size (Pehkui scales the
 * whole pose stack), plus disc-style pulse ghosts around it during shrink/grow animations.
 */
public class ShrunkenEntityEntityRenderer extends EntityRenderer<ShrunkenEntityEntity> {

    private static final ResourceLocation FALLBACK = DyneTech.id("textures/entity/shrunken_structure.png");

    public ShrunkenEntityEntityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(ShrunkenEntityEntity entity, float yaw, float partial, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        ShrunkenEntityRef ref = entity.getItem().get(DTDataComponents.SHRUNKEN_ENTITY.get());
        if (ref == null) {
            super.render(entity, yaw, partial, pose, buffers, light);
            return;
        }

        LivingEntity preview = ClientEntityCache.get(ref);
        if (preview == null) {
            super.render(entity, yaw, partial, pose, buffers, light);
            return;
        }

        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(entity.getYRot()));

        MiniEntityRenderer.render(preview, pose, buffers, light, partial, false);

        var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(preview);
        if (renderer instanceof LivingEntityRenderer<?, ?> lr) {
            DiscoPulseManager.renderEntityPulses(
                    entity.getId(), preview, lr, pose, buffers, partial,
                    PehkuiCompat.getScale(entity));
        }

        pose.popPose();

        super.render(entity, yaw, partial, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(ShrunkenEntityEntity entity) {
        return FALLBACK;
    }
}
