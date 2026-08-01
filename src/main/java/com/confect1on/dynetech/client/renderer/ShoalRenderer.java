package com.confect1on.dynetech.client.renderer;

import com.confect1on.dynetech.entity.ShoalEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Custom batched renderer for the Shoal. Draws several hundred camera-facing quads per swarm as
 * a single additive pass through {@link ShoalRenderTypes#mote()}. Motes are simulated per-frame
 * in {@link ShoalSwarm}; the renderer just walks them and emits four vertices each.
 *
 * <p>Per-swarm state is keyed off the entity instance via a {@link WeakHashMap} so unloaded
 * Shoals drop their swarm memory without an explicit teardown call.
 */
public class ShoalRenderer extends EntityRenderer<ShoalEntity> {

    // Two thirds of the old solo-pair budget: the swarm now travels as a trio, so each lobe
    // carries fewer motes to keep the total on screen roughly where it was.
    private static final int SWARM_CAPACITY = 530;
    private static final int MAX_MOTES_NEAR = 530;
    private static final int MAX_MOTES_MID = 270;
    private static final int MAX_MOTES_FAR = 100;
    private static final float NEAR_DIST_SQ = 24F * 24F;
    private static final float MID_DIST_SQ = 48F * 48F;
    private static final float CULL_DIST_SQ = 96F * 96F;

    // Luminous blue base color. Vertex alpha now drives the fade directly (alpha blend), so no
    // premultiplication needed.
    private static final int COLOR_R = 110;
    private static final int COLOR_G = 200;
    private static final int COLOR_B = 255;

    // Halo tint is kept dim because the halo pass is additive: overlap sums, so bright values
    // would wash out to white. The alpha stays high instead, which also keeps more of the
    // radial falloff above the shader's 0.1 alpha discard.
    private static final int HALO_R = 40;
    private static final int HALO_G = 90;
    private static final int HALO_B = 140;
    private static final float HALO_SCALE = 3.5F;
    private static final float HALO_ALPHA = 0.85F;

    private static final Map<ShoalEntity, ShoalSwarm> SWARMS = new WeakHashMap<>();
    private static long lastGcTick = Long.MIN_VALUE;

    public ShoalRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRender(ShoalEntity entity, net.minecraft.client.renderer.culling.Frustum frustum,
                                double camX, double camY, double camZ) {
        // Cheap radius cull; the mote AABB extends past the entity's own 1.5-block box.
        double dx = entity.getX() - camX;
        double dy = entity.getY() - camY;
        double dz = entity.getZ() - camZ;
        if (dx * dx + dy * dy + dz * dz > CULL_DIST_SQ) return false;
        return frustum.isVisible(entity.getBoundingBox().inflate(6.0));
    }

    @Override
    public void render(ShoalEntity entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Camera camera = this.entityRenderDispatcher.camera;
        Vec3 camPos = camera.getPosition();
        double dx = entity.getX() - camPos.x;
        double dy = entity.getY() - camPos.y;
        double dz = entity.getZ() - camPos.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > CULL_DIST_SQ) return;

        // One stable swarm per entity at max capacity. LOD is applied by drawing fewer motes at
        // distance, not by rebuilding the pool. Rebuilding on every LOD crossing was resetting
        // ages and causing the whole cloud to fade in together every time the camera moved.
        ShoalSwarm swarm = SWARMS.get(entity);
        if (swarm == null) {
            swarm = new ShoalSwarm(seedFor(entity), SWARM_CAPACITY);
            SWARMS.put(entity, swarm);
        }
        maybeGc(entity.tickCount);

        int drawCount = distSq < NEAR_DIST_SQ ? MAX_MOTES_NEAR
                : distSq < MID_DIST_SQ ? MAX_MOTES_MID : MAX_MOTES_FAR;

        Vec3 entPos = entity.getPosition(partialTicks);
        Vec3 anchor = entPos.add(0.0D, 0.7D, 0.0D);
        swarm.update(entity, anchor, partialTicks);

        Matrix4f matrix = poseStack.last().pose();

        // Billboard basis: pull the camera's right and up in world space so every mote reuses
        // the same 3D offsets rather than pushing a matrix per mote.
        Vector3f camRight = new Vector3f();
        Vector3f camUp = new Vector3f();
        camera.rotation().transform(1F, 0F, 0F, camRight);
        camera.rotation().transform(0F, 1F, 0F, camUp);

        // The pose is already translated to the entity's interpolated position, so quad centers
        // must be given in entity-local space.
        double entX = entPos.x;
        double entY = entPos.y;
        double entZ = entPos.z;

        int count = Math.min(swarm.count(), drawCount);

        // Halo pass first so the additive glow sits behind the alpha-blended core dots. Each
        // pass runs its full loop before switching buffers; interleaving getBuffer calls per
        // mote would break the BufferSource batch.
        VertexConsumer halo = buffer.getBuffer(ShoalRenderTypes.halo());
        emitPass(halo, swarm, matrix, camRight, camUp, entX, entY, entZ, count,
                HALO_SCALE, HALO_ALPHA, HALO_R, HALO_G, HALO_B);

        VertexConsumer core = buffer.getBuffer(ShoalRenderTypes.mote());
        emitPass(core, swarm, matrix, camRight, camUp, entX, entY, entZ, count,
                1.0F, 1.0F, COLOR_R, COLOR_G, COLOR_B);

        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    private static void emitPass(VertexConsumer vc, ShoalSwarm swarm, Matrix4f matrix,
                                 Vector3f camRight, Vector3f camUp,
                                 double entX, double entY, double entZ, int count,
                                 float sizeScale, float alphaScale, int r, int g, int b) {
        int lightPacked = LightTexture.FULL_BRIGHT;
        int overlayPacked = OverlayTexture.NO_OVERLAY;
        for (int i = 0; i < count; i++) {
            float a = swarm.alpha(i) * alphaScale;
            if (a <= 0.01F) continue;
            float half = swarm.size(i) * sizeScale;
            float ox = (float) (swarm.px(i) - entX);
            float oy = (float) (swarm.py(i) - entY);
            float oz = (float) (swarm.pz(i) - entZ);

            float rx = camRight.x * half;
            float ry = camRight.y * half;
            float rz = camRight.z * half;
            float ux = camUp.x * half;
            float uy = camUp.y * half;
            float uz = camUp.z * half;

            int alphaByte = Math.min(255, Math.max(0, (int) (a * 255F)));

            // Winding: TL, TR, BR, BL for GL_QUADS with NO_CULL.
            addVertex(vc, matrix, ox - rx + ux, oy - ry + uy, oz - rz + uz,
                    r, g, b, alphaByte, 0F, 0F, overlayPacked, lightPacked);
            addVertex(vc, matrix, ox + rx + ux, oy + ry + uy, oz + rz + uz,
                    r, g, b, alphaByte, 1F, 0F, overlayPacked, lightPacked);
            addVertex(vc, matrix, ox + rx - ux, oy + ry - uy, oz + rz - uz,
                    r, g, b, alphaByte, 1F, 1F, overlayPacked, lightPacked);
            addVertex(vc, matrix, ox - rx - ux, oy - ry - uy, oz - rz - uz,
                    r, g, b, alphaByte, 0F, 1F, overlayPacked, lightPacked);
        }
    }

    private static void addVertex(VertexConsumer vc, Matrix4f matrix,
                                  float x, float y, float z,
                                  int r, int g, int b, int alpha, float u, float v,
                                  int overlay, int light) {
        vc.addVertex(matrix, x, y, z)
                .setColor(r, g, b, alpha)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light);
    }

    private static int seedFor(ShoalEntity entity) {
        int seed = entity.getSeed();
        return seed == 0 ? entity.getId() * 0x9E3779B1 : seed;
    }

    /**
     * Sweeps swarm map entries for entities that have been removed on the client. WeakHashMap
     * already reclaims when the ShoalEntity is GC'd, but a discarded-but-still-referenced entity
     * can hang on for a while; this drops its swarm immediately.
     */
    private static void maybeGc(int tick) {
        if (tick - lastGcTick < 200L) return;
        lastGcTick = tick;
        Iterator<Map.Entry<ShoalEntity, ShoalSwarm>> it = SWARMS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ShoalEntity, ShoalSwarm> e = it.next();
            if (e.getKey().isRemoved()) it.remove();
        }
    }

    @Override
    public ResourceLocation getTextureLocation(ShoalEntity entity) {
        return ShoalMoteTexture.TEXTURE;
    }

    @Override
    protected boolean shouldShowName(ShoalEntity entity) {
        return false;
    }

    @Override
    protected float getShadowRadius(ShoalEntity entity) {
        return 0.0F;
    }
}
