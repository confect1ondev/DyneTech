package com.confect1on.dynetech.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import com.confect1on.dynetech.DyneTech;
import com.confect1on.dynetech.client.renderer.RenderHelper;
import com.confect1on.dynetech.pehkui.PehkuiCompat;
import com.confect1on.dynetech.storage.StructureBlob;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@EventBusSubscriber(modid = DyneTech.MODID, value = Dist.CLIENT)
public final class DiscoPulseManager {

    private static final ResourceLocation WHITE_TEX = DyneTech.id("textures/misc/white.png");

    public static final int PULSE_COUNT = 5;
    public static final int PULSE_STAGGER = 2;
    public static final int PULSE_LIFETIME = 10;
    private static final int TOTAL_LIFETIME = (PULSE_COUNT - 1) * PULSE_STAGGER + PULSE_LIFETIME;

    private static final Map<Integer, Integer> START_TICKS = new HashMap<>();
    private static int clientTick = 0;

    private DiscoPulseManager() {}

    public static void trigger(int entityId) {
        START_TICKS.put(entityId, clientTick);
    }

    public static int getStartTick(int entityId) {
        Integer v = START_TICKS.get(entityId);
        return v == null ? -1 : v;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        clientTick++;
        Iterator<Map.Entry<Integer, Integer>> it = START_TICKS.entrySet().iterator();
        while (it.hasNext()) {
            if (clientTick - it.next().getValue() > TOTAL_LIFETIME + 2) it.remove();
        }
    }

    /**
     * Proportional halo puff as a fraction of the body size (e.g. 0.12 = 12% larger).
     * The pose stack in RenderLivingEvent.Post is already scaled by Pehkui, so the halo must
     * be expressed as a factor applied on top of the body — NOT as a world-space addition,
     * which would double-scale (pehkui × (pehkui + halo)) and blow up on grown entities and
     * collapse inside shrunken ones.
     */
    private static float haloPuff(float progress) {
        return 0.05F + 0.20F * progress; // 5% halo at start, expands to 25% as it fades
    }

    /** White base tinted red for scale &lt; 1 (shrunk) and blue for scale &gt; 1 (grown). Packed 0x00RRGGBB. */
    public static int getScaleTintRGB(float scale) {
        int r, g, b;
        if (scale >= 1F) {
            float t = Mth.clamp((scale - 1F) / 4F, 0F, 1F);
            r = 255 - (int) (t * 215F);
            g = 255 - (int) (t * 190F);
            b = 255;
        } else {
            float t = Mth.clamp((1F - scale) / 0.9F, 0F, 1F);
            r = 255;
            g = 255 - (int) (t * 190F);
            b = 255 - (int) (t * 215F);
        }
        return (r << 16) | (g << 8) | b;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @SubscribeEvent
    public static void onRenderLivingPost(RenderLivingEvent.Post event) {
        LivingEntity entity = event.getEntity();
        int startTick = getStartTick(entity.getId());
        if (startTick < 0) return;

        LivingEntityRenderer<?, ?> renderer = event.getRenderer();
        EntityModel model = renderer.getModel();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource buffers = event.getMultiBufferSource();
        float partialTick = event.getPartialTick();
        int light = event.getPackedLight();

        int elapsed = clientTick - startTick;
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(WHITE_TEX));
        float pehkui = PehkuiCompat.getScale(entity);
        int tintRgb = getScaleTintRGB(pehkui);
        float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);

        for (int i = 0; i < PULSE_COUNT; i++) {
            float pulseElapsed = (elapsed + partialTick) - i * PULSE_STAGGER;
            if (pulseElapsed < 0F || pulseElapsed > PULSE_LIFETIME) continue;
            float progress = pulseElapsed / PULSE_LIFETIME;
            int alpha = Math.max(0, Math.min(255, (int) ((1F - progress) * 0.6F * 255F)));
            if (alpha == 0) continue;

            float puff = haloPuff(progress);
            float s = 1F + puff;

            // Pose is already Pehkui-scaled; puff is a proportional factor on TOP of that,
            // so the ghost stays a consistent % larger than the body at any pehkui scale.
            pose.pushPose();
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180F - bodyYaw));
            pose.scale(s, s, s);
            pose.scale(-1F, -1F, 1F);
            pose.scale(0.9375F, 0.9375F, 0.9375F);
            pose.translate(0F, -1.501F, 0F);

            int color = (alpha << 24) | tintRgb;
            model.renderToBuffer(pose, vc, light, OverlayTexture.NO_OVERLAY, color);
            pose.popPose();
        }
    }

    /**
     * Directly draws pulse ghosts of a preview mob's model — the same visual style as the disc's
     * {@link #onRenderLivingPost} handler, but invoked explicitly for entities that aren't the
     * dispatcher's current subject (e.g. a preview {@link LivingEntity} rendered inside another
     * entity's renderer). Uses {@code carrierScale} for tint so the ghost colors track the parent
     * carrier's Pehkui scale, not the preview's.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void renderEntityPulses(int carrierId, LivingEntity preview,
                                          net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?> renderer,
                                          PoseStack pose, MultiBufferSource buffers,
                                          float partialTick, float carrierScale) {
        int startTick = getStartTick(carrierId);
        if (startTick < 0) return;

        EntityModel model = renderer.getModel();
        int elapsed = clientTick - startTick;
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(WHITE_TEX));
        int tintRgb = getScaleTintRGB(carrierScale);
        float bodyYaw = Mth.rotLerp(partialTick, preview.yBodyRotO, preview.yBodyRot);

        for (int i = 0; i < PULSE_COUNT; i++) {
            float pulseElapsed = (elapsed + partialTick) - i * PULSE_STAGGER;
            if (pulseElapsed < 0F || pulseElapsed > PULSE_LIFETIME) continue;
            float progress = pulseElapsed / PULSE_LIFETIME;
            int alpha = Math.max(0, Math.min(255, (int) ((1F - progress) * 0.6F * 255F)));
            if (alpha == 0) continue;

            float puff = haloPuff(progress);
            float s = 1F + puff;

            // Same stack as onRenderLivingPost: proportional halo on top of the carrier's pose scale.
            pose.pushPose();
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180F - bodyYaw));
            pose.scale(s, s, s);
            pose.scale(-1F, -1F, 1F);
            pose.scale(0.9375F, 0.9375F, 0.9375F);
            pose.translate(0F, -1.501F, 0F);

            int color = (alpha << 24) | tintRgb;
            model.renderToBuffer(pose, vc, light(), OverlayTexture.NO_OVERLAY, color);
            pose.popPose();
        }
    }

    private static int light() {
        // Full-bright for the ghost overlays — the underlying pose's packedLight only applies to
        // the primary model; overlays feel too dim if lit by the local block light.
        return 0xF000F0;
    }

    /**
     * Renders pulse silhouettes around a shrunken structure — filled translucent cubes on each surface block
     * (any non-air block with at least one air neighbor), inflated by a constant world-space halo. Traces the
     * structure's actual shape instead of a single bounding box.
     */
    public static void renderStructurePulses(int entityId, float currentScale, StructureBlob blob,
                                             PoseStack pose, MultiBufferSource buffers, float partialTick) {
        int startTick = getStartTick(entityId);
        if (startTick < 0) return;

        int elapsed = clientTick - startTick;
        int tintRgb = getScaleTintRGB(currentScale);
        float rF = ((tintRgb >> 16) & 0xFF) / 255F;
        float gF = ((tintRgb >> 8) & 0xFF) / 255F;
        float bF = (tintRgb & 0xFF) / 255F;

        Vec3i size = blob.size();
        int sx = size.getX(), sy = size.getY(), sz = size.getZ();
        float halfX = sx / 2F;
        float halfZ = sz / 2F;

        for (int i = 0; i < PULSE_COUNT; i++) {
            float pulseElapsed = (elapsed + partialTick) - i * PULSE_STAGGER;
            if (pulseElapsed < 0F || pulseElapsed > PULSE_LIFETIME) continue;
            float progress = pulseElapsed / PULSE_LIFETIME;
            float alpha = (1F - progress) * 0.5F;
            if (alpha <= 0F) continue;

            // Proportional puff: pose is Pehkui-scaled, so a local-space inflation of `puff/2`
            // per face gives a consistent halo of `puff × block_size × pehkui` in world space
            // — regardless of pehkui. No inverse-scale hack needed.
            float extraLocal = haloPuff(progress) * 0.5F;

            pose.pushPose();
            pose.translate(-halfX, 0, -halfZ); // match MiniStructureRenderer's translate
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        BlockState state = blob.getBlockState(x, y, z);
                        if (state.isAir()) continue;
                        if (!hasAirNeighbor(blob, x, y, z, sx, sy, sz)) continue;

                        AABB box = new AABB(
                                x - extraLocal, y - extraLocal, z - extraLocal,
                                x + 1 + extraLocal, y + 1 + extraLocal, z + 1 + extraLocal);
                        RenderHelper.drawFilledBox(pose, buffers, box, rF, gF, bF, alpha);
                    }
                }
            }
            pose.popPose();
        }
    }

    private static boolean hasAirNeighbor(StructureBlob blob, int x, int y, int z, int sx, int sy, int sz) {
        for (Direction dir : Direction.values()) {
            int nx = x + dir.getStepX();
            int ny = y + dir.getStepY();
            int nz = z + dir.getStepZ();
            if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz) return true;
            if (blob.getBlockState(nx, ny, nz).isAir()) return true;
        }
        return false;
    }
}
