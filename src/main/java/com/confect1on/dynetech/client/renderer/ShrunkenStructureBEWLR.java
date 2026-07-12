package com.confect1on.dynetech.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import com.confect1on.dynetech.client.ClientStructureCache;
import com.confect1on.dynetech.component.DTDataComponents;
import com.confect1on.dynetech.storage.ShrunkenStructureRef;
import com.confect1on.dynetech.storage.StructureBlob;

/**
 * ItemStack-level renderer for the shrunken structure item. Used in inventory, hand, GUI, dropped item, etc.
 * The world-entity variant has its own renderer that handles Pehkui-scale + entity yaw.
 */
public class ShrunkenStructureBEWLR extends BlockEntityWithoutLevelRenderer {

    public ShrunkenStructureBEWLR() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext ctx, PoseStack pose,
                             MultiBufferSource buffers, int light, int overlay) {
        ShrunkenStructureRef ref = stack.get(DTDataComponents.SHRUNKEN_STRUCTURE.get());
        if (ref == null) return;

        StructureBlob blob = ClientStructureCache.get(ref.id());
        if (blob == null) {
            ClientStructureCache.requestIfMissing(ref.id());
            return;
        }

        pose.pushPose();
        double yOffset = switch (ctx) {
            case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND,
                 THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> 0.35;
            default -> 0.0;
        };
        pose.translate(0.5, yOffset, 0.5); // center in item slot; lift when held
        MiniStructureRenderer.render(blob, pose, buffers, light, overlay, true);
        pose.popPose();
    }
}
