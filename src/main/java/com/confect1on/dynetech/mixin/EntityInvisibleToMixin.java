package com.confect1on.dynetech.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.confect1on.dynetech.effect.DTEffects;

/**
 * Chameleon translucency: overrides {@link Entity#isInvisibleTo} to return {@code false} for
 * any living entity carrying the {@link DTEffects#CAMOUFLAGE} marker.
 *
 * <p>Vanilla {@code LivingEntityRenderer.render} chooses its render type from two flags -
 * {@code bodyVisible} (true when the entity does not have {@code INVISIBILITY}) and
 * {@code invisibleButVisible} (true when the entity IS invisible but the current camera can
 * still see it - the "show invisible teammates" case). Vanilla only takes the translucent path
 * when {@code invisibleButVisible}. By flipping {@code isInvisibleTo} to false while keeping
 * the vanilla {@code INVISIBILITY} effect applied, we drive the renderer down the translucent
 * branch for every viewer - no team gymnastics required.
 */
@Mixin(Entity.class)
public class EntityInvisibleToMixin {

    @Inject(method = "isInvisibleTo", at = @At("HEAD"), cancellable = true)
    private void dynetech$camouflageForcesTranslucent(Player viewer, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity)(Object)this;
        if (self instanceof LivingEntity le && le.hasEffect(DTEffects.CAMOUFLAGE)) {
            cir.setReturnValue(false);
        }
    }
}
