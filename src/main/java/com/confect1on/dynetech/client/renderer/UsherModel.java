package com.confect1on.dynetech.client.renderer;

import com.confect1on.dynetech.entity.UsherEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;

/**
 * Standard humanoid player-format model. The provided skin is authored at 64x64 Steve
 * proportions, so no arm remapping is needed - default {@link HumanoidModel} is a direct fit.
 */
public class UsherModel extends HumanoidModel<UsherEntity> {

    public UsherModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
        return LayerDefinition.create(mesh, 64, 64);
    }
}
