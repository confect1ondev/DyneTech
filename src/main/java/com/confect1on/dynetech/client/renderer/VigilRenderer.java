package com.confect1on.dynetech.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.entity.VigilEntity;

public class VigilRenderer extends HumanoidMobRenderer<VigilEntity, VigilModel> {

    public static final ModelLayerLocation MODEL_LAYER =
            new ModelLayerLocation(DyneTech.id("vigil"), "main");
    public static final ModelLayerLocation MODEL_LAYER_SLIM =
            new ModelLayerLocation(DyneTech.id("vigil"), "slim");

    private static final ResourceLocation[] SKINS = new ResourceLocation[] {
            DyneTech.id("textures/entity/vigil/vigil_0.png"),
            DyneTech.id("textures/entity/vigil/vigil_1.png"),
            DyneTech.id("textures/entity/vigil/vigil_2.png"),
    };

    // Determined by inspecting the pixel at (55, 20). An Alex texture has transparency there
    // (right arm's back face doesn't reach that column at 3-wide), a default texture is opaque.
    private static final boolean[] SLIM_VARIANT = new boolean[] { true, true, false };

    private final VigilModel defaultModel;
    private final VigilModel slimModel;

    public VigilRenderer(EntityRendererProvider.Context context) {
        super(context, new VigilModel(context.bakeLayer(MODEL_LAYER)), 0.5F);
        this.defaultModel = this.model;
        this.slimModel = new VigilModel(context.bakeLayer(MODEL_LAYER_SLIM));
    }

    @Override
    public void render(VigilEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        int v = entity.getVariant();
        boolean slim = v >= 0 && v < SLIM_VARIANT.length && SLIM_VARIANT[v];
        this.model = slim ? this.slimModel : this.defaultModel;
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(VigilEntity entity) {
        int v = entity.getVariant();
        if (v < 0 || v >= SKINS.length) v = 0;
        return SKINS[v];
    }
}
