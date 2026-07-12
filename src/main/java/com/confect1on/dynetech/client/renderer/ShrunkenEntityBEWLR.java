package com.confect1on.dynetech.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import com.confect1on.dynetech.client.ClientEntityCache;
import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.storage.ShrunkenEntityRef;

/**
 * ItemStack renderer for the shrunken-entity item. Renders the captured mob's 3D preview centered
 * in the slot, mirroring how {@link ShrunkenStructureBEWLR} presents shrunken structures.
 */
public class ShrunkenEntityBEWLR extends BlockEntityWithoutLevelRenderer {

    public ShrunkenEntityBEWLR() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext ctx, PoseStack pose,
                             MultiBufferSource buffers, int light, int overlay) {
        ShrunkenEntityRef ref = stack.get(DTDataComponents.SHRUNKEN_ENTITY.get());
        if (ref == null) return;

        LivingEntity preview = ClientEntityCache.get(ref);
        if (preview == null) return;

        pose.pushPose();
        double yOffset = switch (ctx) {
            case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND,
                 THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> 0.35;
            default -> 0.0;
        };
        pose.translate(0.5, yOffset, 0.5); // centre the mob in the slot; lift it out of the hand mesh
        // Extra shrink on top of fit-to-unit-cube so the icon reads as a tiny captured mob rather
        // than filling the slot the way a full-size structure block would.
        pose.scale(0.4F, 0.4F, 0.4F);
        MiniEntityRenderer.render(preview, pose, buffers, light, 0F, true);
        pose.popPose();
    }
}
