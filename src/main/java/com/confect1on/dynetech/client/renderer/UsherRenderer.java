package com.confect1on.dynetech.client.renderer;

import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.entity.UsherEntity;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

public class UsherRenderer extends HumanoidMobRenderer<UsherEntity, UsherModel> {

    public static final ModelLayerLocation MODEL_LAYER =
            new ModelLayerLocation(DyneTech.id("usher"), "main");

    private static final ResourceLocation TEXTURE = DyneTech.id("textures/entity/usher/usher.png");

    public UsherRenderer(EntityRendererProvider.Context context) {
        super(context, new UsherModel(context.bakeLayer(MODEL_LAYER)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(UsherEntity entity) {
        return TEXTURE;
    }
}
