package com.confect1on.dynetech.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import com.confect1on.dynetech.blockentity.StructureShrinkerBlockEntity;
import com.confect1on.dynetech.client.ClientSettings;

public class StructureShrinkerBlockEntityRenderer implements BlockEntityRenderer<StructureShrinkerBlockEntity> {

    private static final float OUTLINE_THICKNESS = 0.06F;

    // Rig sits on top of the block: plate hovers a hair above y=1 to dodge z-fighting.
    private static final float PLATE_TOP_Y      = 1.06F;
    private static final float PLATE_THICKNESS  = 0.05F;
    private static final float PLATE_HALF_WIDTH = 0.75F;
    private static final float HOLO_SIZE        = 1.5F;
    private static final int   HOLO_MAX_BLOCKS  = 8000;

    // Red hologram tint (0-255, multiplied per-channel over the block's own vertex color).
    private static final int TINT_R = 255, TINT_G = 90, TINT_B = 110, TINT_A = 150;

    // Reused across frames to keep the per-block loop allocation-free.
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();

    public StructureShrinkerBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(StructureShrinkerBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        BlockPos rel1 = be.getSelectionStartRelative();
        BlockPos rel2 = be.getSelectionEndRelative();
        boolean hasSelection = !(rel1.equals(BlockPos.ZERO) && rel2.equals(BlockPos.ZERO));

        if (hasSelection && ClientSettings.showSelectionBox()) {
            int minX = Math.min(rel1.getX(), rel2.getX());
            int minY = Math.min(rel1.getY(), rel2.getY());
            int minZ = Math.min(rel1.getZ(), rel2.getZ());
            int maxX = Math.max(rel1.getX(), rel2.getX());
            int maxY = Math.max(rel1.getY(), rel2.getY());
            int maxZ = Math.max(rel1.getZ(), rel2.getZ());
            AABB box = new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);

            var mc = Minecraft.getInstance();
            float t = mc.player != null ? (mc.player.tickCount + partial) / 10F : partial;
            float alpha = 0.65F + (Mth.sin(t) + 1F) / 6F;

            RenderHelper.drawThickWireBox(pose, buffers, box, OUTLINE_THICKNESS, 1F, 0F, 0F, alpha);
        }

        if (hasSelection) {
            float pulse = pulse(partial);
            renderBasePlate(pose, buffers, pulse);
            renderHologram(be, partial, pose, buffers);
        }
    }

    private static float pulse(float partial) {
        var mc = Minecraft.getInstance();
        float t = mc.player != null ? (mc.player.tickCount + partial) : partial;
        return 0.75F + Mth.sin(t / 6F) * 0.25F;
    }

    private void renderBasePlate(PoseStack pose, MultiBufferSource buffers, float pulse) {
        float top = PLATE_TOP_Y;
        float bot = PLATE_TOP_Y - PLATE_THICKNESS;
        float ph  = PLATE_HALF_WIDTH;
        float cx = 0.5F, cz = 0.5F;

        // Base fill.
        RenderHelper.drawFilledBox(pose, buffers,
                new AABB(cx - ph, bot, cz - ph, cx + ph, top, cz + ph),
                1F, 0.15F, 0.20F, 0.28F * pulse);

        // Grid lines across the top face.
        int divs = 6;
        float step = (2F * ph) / divs;
        float lineY1 = top + 0.002F;
        float lineY2 = top + 0.010F;
        float lineHalfThick = 0.010F;
        float gR = 1F, gG = 0.35F, gB = 0.45F, gA = 0.75F * pulse;
        for (int i = 0; i <= divs; i++) {
            float p = -ph + i * step;
            RenderHelper.drawFilledBox(pose, buffers,
                    new AABB(cx - ph, lineY1, cz + p - lineHalfThick,
                             cx + ph, lineY2, cz + p + lineHalfThick),
                    gR, gG, gB, gA);
            RenderHelper.drawFilledBox(pose, buffers,
                    new AABB(cx + p - lineHalfThick, lineY1, cz - ph,
                             cx + p + lineHalfThick, lineY2, cz + ph),
                    gR, gG, gB, gA);
        }

        // Brighter edge outline.
        float edge = 0.020F;
        float edgeA = 0.85F * pulse;
        RenderHelper.drawFilledBox(pose, buffers,
                new AABB(cx - ph, lineY1, cz - ph, cx + ph, lineY2, cz - ph + edge),
                1F, 0.5F, 0.6F, edgeA);
        RenderHelper.drawFilledBox(pose, buffers,
                new AABB(cx - ph, lineY1, cz + ph - edge, cx + ph, lineY2, cz + ph),
                1F, 0.5F, 0.6F, edgeA);
        RenderHelper.drawFilledBox(pose, buffers,
                new AABB(cx - ph, lineY1, cz - ph, cx - ph + edge, lineY2, cz + ph),
                1F, 0.5F, 0.6F, edgeA);
        RenderHelper.drawFilledBox(pose, buffers,
                new AABB(cx + ph - edge, lineY1, cz - ph, cx + ph, lineY2, cz + ph),
                1F, 0.5F, 0.6F, edgeA);
    }

    private void renderHologram(StructureShrinkerBlockEntity be, float partial,
                                PoseStack pose, MultiBufferSource buffers) {
        Level level = be.getLevel();
        if (level == null) return;

        BlockPos startAbs = be.getSelectionStart();
        BlockPos endAbs = be.getSelectionEnd();
        int minX = Math.min(startAbs.getX(), endAbs.getX());
        int minY = Math.min(startAbs.getY(), endAbs.getY());
        int minZ = Math.min(startAbs.getZ(), endAbs.getZ());
        int maxX = Math.max(startAbs.getX(), endAbs.getX());
        int maxY = Math.max(startAbs.getY(), endAbs.getY());
        int maxZ = Math.max(startAbs.getZ(), endAbs.getZ());
        int dx = maxX - minX + 1;
        int dy = maxY - minY + 1;
        int dz = maxZ - minZ + 1;

        // Y-spin only, so horizontal extent is bounded by the XZ diagonal and vertical by dy.
        float horiz = Mth.sqrt(dx * (float) dx + dz * (float) dz);
        float scaleH = HOLO_SIZE / horiz;
        float scaleV = HOLO_SIZE / dy;
        float scale = Math.min(scaleH, scaleV);

        var mc = Minecraft.getInstance();
        float t = mc.player != null ? (mc.player.tickCount + partial) : partial;
        float spin = (t * 0.6f) % 360f;

        pose.pushPose();
        // Pose origin sits at plate top center. After the translate below, block (0,0,0)'s
        // bottom face rests on the plate; only X/Z are centered on the plate.
        pose.translate(0.5f, PLATE_TOP_Y, 0.5f);
        pose.mulPose(Axis.YP.rotationDegrees(spin));
        pose.scale(scale, scale, scale);
        pose.translate(-dx / 2f, 0f, -dz / 2f);

        BlockRenderDispatcher brd = mc.getBlockRenderer();
        MultiBufferSource tintBuffers = HologramBufferSource.get(buffers, TINT_R, TINT_G, TINT_B, TINT_A);

        int rendered = 0;
        for (int y = 0; y < dy && rendered < HOLO_MAX_BLOCKS; y++) {
            for (int z = 0; z < dz && rendered < HOLO_MAX_BLOCKS; z++) {
                for (int x = 0; x < dx && rendered < HOLO_MAX_BLOCKS; x++) {
                    cursor.set(minX + x, minY + y, minZ + z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) continue;
                    if (state.getRenderShape() != RenderShape.MODEL) continue;
                    if (isFullyEnclosed(level, minX + x, minY + y, minZ + z,
                            minX, minY, minZ, maxX, maxY, maxZ, neighbor)) continue;
                    pose.pushPose();
                    pose.translate(x, y, z);
                    brd.renderSingleBlock(state, pose, tintBuffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                    pose.popPose();
                    rendered++;
                }
            }
        }

        pose.popPose();
    }

    private static boolean isFullyEnclosed(Level level, int wx, int wy, int wz,
                                           int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                           BlockPos.MutableBlockPos scratch) {
        return neighborOccludes(level, wx - 1, wy, wz, minX, minY, minZ, maxX, maxY, maxZ, scratch)
            && neighborOccludes(level, wx + 1, wy, wz, minX, minY, minZ, maxX, maxY, maxZ, scratch)
            && neighborOccludes(level, wx, wy - 1, wz, minX, minY, minZ, maxX, maxY, maxZ, scratch)
            && neighborOccludes(level, wx, wy + 1, wz, minX, minY, minZ, maxX, maxY, maxZ, scratch)
            && neighborOccludes(level, wx, wy, wz - 1, minX, minY, minZ, maxX, maxY, maxZ, scratch)
            && neighborOccludes(level, wx, wy, wz + 1, minX, minY, minZ, maxX, maxY, maxZ, scratch);
    }

    private static boolean neighborOccludes(Level level, int nx, int ny, int nz,
                                            int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                            BlockPos.MutableBlockPos scratch) {
        // Anything outside the selection AABB is treated as non-blocking so the outer shell stays.
        if (nx < minX || nx > maxX || ny < minY || ny > maxY || nz < minZ || nz > maxZ) return false;
        scratch.set(nx, ny, nz);
        return level.getBlockState(scratch).canOcclude();
    }

    // Reroutes every block render type through translucent() so the hologram alpha-blends
    // instead of getting cutout-tested, and multiplies each vertex color by a red tint.
    // Cached so we're not making a new consumer per block per frame.
    private static final class HologramBufferSource implements MultiBufferSource {
        private static final ThreadLocal<HologramBufferSource> POOL = ThreadLocal.withInitial(HologramBufferSource::new);

        private VertexConsumer wrapper;

        static HologramBufferSource get(MultiBufferSource inner, int r, int g, int b, int a) {
            HologramBufferSource src = POOL.get();
            src.wrapper = new TintingVertexConsumer(inner.getBuffer(RenderType.translucent()), r, g, b, a);
            return src;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            return wrapper;
        }
    }

    private static final class TintingVertexConsumer implements VertexConsumer {
        private final VertexConsumer inner;
        private final int rMul, gMul, bMul, aMul;

        TintingVertexConsumer(VertexConsumer inner, int rMul, int gMul, int bMul, int aMul) {
            this.inner = inner;
            this.rMul = rMul;
            this.gMul = gMul;
            this.bMul = bMul;
            this.aMul = aMul;
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) { return inner.addVertex(x, y, z); }
        @Override public VertexConsumer setUv(float u, float v) { return inner.setUv(u, v); }
        @Override public VertexConsumer setUv1(int u, int v) { return inner.setUv1(u, v); }
        @Override public VertexConsumer setUv2(int u, int v) { return inner.setUv2(u, v); }
        @Override public VertexConsumer setNormal(float nx, float ny, float nz) { return inner.setNormal(nx, ny, nz); }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return inner.setColor((r * rMul) / 255, (g * gMul) / 255, (b * bMul) / 255, (a * aMul) / 255);
        }
    }

    @Override
    public boolean shouldRenderOffScreen(StructureShrinkerBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    @Override
    public AABB getRenderBoundingBox(StructureShrinkerBlockEntity be) {
        // Covers plate + hologram (~1.5 tall, 1.5 wide) above the block, plus some slop for grid glow.
        BlockPos p = be.getBlockPos();
        return new AABB(
                p.getX() - 0.5, p.getY(),        p.getZ() - 0.5,
                p.getX() + 1.5, p.getY() + 3.0,  p.getZ() + 1.5);
    }
}
