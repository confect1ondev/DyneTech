package com.confect1on.dynetech.client.renderer;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import com.confect1on.dynetech.entity.VigilEntity;

public class VigilModel extends HumanoidModel<VigilEntity> {

    public VigilModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createBodyLayer(boolean slim) {
        MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
        if (slim) {
            // Alex-style: rebuild arms at 3 wide, dropped 0.5 to match the visual seam of a
            // slim player skin. Left arm stays mirrored from the right-arm UV, good enough for
            // a statue and it means the artist doesn't have to author a separate left-arm slice.
            PartDefinition root = mesh.getRoot();
            root.addOrReplaceChild("right_arm",
                    CubeListBuilder.create()
                            .texOffs(40, 16)
                            .addBox(-2.0F, -2.0F, -2.0F, 3.0F, 12.0F, 4.0F),
                    PartPose.offset(-5.0F, 2.5F, 0.0F));
            root.addOrReplaceChild("left_arm",
                    CubeListBuilder.create()
                            .mirror()
                            .texOffs(40, 16)
                            .addBox(-1.0F, -2.0F, -2.0F, 3.0F, 12.0F, 4.0F),
                    PartPose.offset(5.0F, 2.5F, 0.0F));
        }
        return LayerDefinition.create(mesh, 64, 64);
    }

    // Statue pose. Do NOT call super.setupAnim: HumanoidModel drives arm rotation from limbSwing
    // AND idle bob from ageInTicks (via AnimationUtils.bobArms). Skipping super is the only
    // reliable way to zero both. A Vigil should render as a rigid statue at all times, even
    // between server position updates when it's mid-move.
    @Override
    public void setupAnim(VigilEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        zero(this.head);
        zero(this.hat);
        zero(this.body);
        zero(this.rightArm);
        zero(this.leftArm);
        zero(this.rightLeg);
        zero(this.leftLeg);
    }

    private static void zero(ModelPart part) {
        part.xRot = 0.0F;
        part.yRot = 0.0F;
        part.zRot = 0.0F;
    }
}
