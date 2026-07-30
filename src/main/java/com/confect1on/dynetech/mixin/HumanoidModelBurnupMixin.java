package com.confect1on.dynetech.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.confect1on.dynetech.client.GodhoodBurnupManager;

/**
 * Injects at the tail of {@link HumanoidModel#setupAnim} so the burn-up pose
 * (arms up-and-out, head tilted back, small vibration) overrides the default idle animation
 * for any entity currently in the Godhood burn-up window.
 *
 * <p>Covers players, zombies, skeletons, husks - anything whose renderer uses a
 * HumanoidModel. Since the vanilla method sets the arm and head rotations from scratch each
 * frame, doing this at TAIL is the earliest reliable point to override them without a
 * further injector fighting for the last write.
 *
 * <p>Client-side only via the {@code client} array in dynetech.mixins.json - HumanoidModel
 * is a client class and this mixin has no meaning on a dedicated server.
 */
@Mixin(HumanoidModel.class)
public class HumanoidModelBurnupMixin {

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
            at = @At("TAIL"))
    private void dynetech$applyGodhoodBurnupPose(LivingEntity entity, float limbSwing,
                                                 float limbSwingAmount, float ageInTicks,
                                                 float netHeadYaw, float headPitch,
                                                 CallbackInfo ci) {
        GodhoodBurnupManager.applyPoseToHumanoid((HumanoidModel<?>) (Object) this, entity);
    }
}
