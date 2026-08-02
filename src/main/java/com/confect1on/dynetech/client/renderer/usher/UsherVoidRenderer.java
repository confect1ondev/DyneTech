package com.confect1on.dynetech.client.renderer.usher;

import com.confect1on.dynetech.dimension.UsherInterior;
import com.confect1on.dynetech.dimension.UsherSectionAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.platform.VeilEventPlatform;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import static org.lwjgl.opengl.GL30C.*;

/**
 * The horizon of the Usher interior. The cell walls are invisible barrier blocks, so what a
 * captive actually sees in every direction is this: a fullscreen ray pass drawn right after
 * the sky, which intersects each eye ray with a huge sphere centered on the cell and shades
 * nested procedural shells inside it. Because the shading happens per-ray against real world
 * positions, the darkness has true parallax - pacing the cell slides the near folds across
 * the far veins, which reads as being sealed inside something vast rather than a skybox.
 *
 * <p>Draws with depth writes off at the far plane, so terrain, entities, and the ambience
 * quads all composite over it exactly like they would over the vanilla sky.
 */
public final class UsherVoidRenderer {

    private static final ResourceLocation SHADER =
            ResourceLocation.fromNamespaceAndPath("dynetech", "usher/void_sphere");

    // Far beyond the 16-block cell half-extent, close enough that walking still parallaxes.
    private static final float RADIUS = 96F;

    private static int vao;

    private UsherVoidRenderer() {}

    public static void init() {
        VeilEventPlatform.INSTANCE.onVeilRenderLevelStage((stage, levelRenderer, bufferSource, matrixStack,
                                                          frustumMatrix, projectionMatrix, renderTick,
                                                          deltaTracker, camera, frustum) -> {
            if (stage == VeilRenderLevelStageEvent.Stage.AFTER_SKY) {
                float time = renderTick + deltaTracker.getGameTimeDeltaPartialTick(false);
                draw(frustumMatrix, projectionMatrix, camera, time);
            }
        });
        VeilEventPlatform.INSTANCE.onFreeNativeResources(UsherVoidRenderer::free);
    }

    private static void draw(Matrix4fc modelView, Matrix4fc projection, Camera camera, float time) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(UsherInterior.DIMENSION)) return;

        ShaderProgram shader = VeilRenderSystem.renderer().getShaderManager().getShader(SHADER);
        if (shader == null) return; // vanilla end sky stays as the fallback backdrop

        Vec3 cam = camera.getPosition();
        int index = (int) Math.round(cam.x / (double) UsherSectionAllocator.SECTION_SPACING_X);
        double cx = UsherSectionAllocator.sectionCenterX(index);

        // Unprojects NDC back to camera-relative world space in the fragment shader.
        Matrix4f invViewProj = new Matrix4f(projection).mul(modelView).invert();

        shader.bind();
        shader.getUniformSafe("InvViewProj").setMatrix(invViewProj);
        shader.getUniformSafe("CenterCamPos").setVector(
                (float) (cx - cam.x),
                (float) (UsherSectionAllocator.CENTER_Y - cam.y),
                (float) (UsherSectionAllocator.CENTER_Z - cam.z));
        shader.getUniformSafe("Radius").setFloat(RADIUS);
        shader.getUniformSafe("GameTime").setFloat(time);

        if (vao == 0) vao = glGenVertexArrays();
        glBindVertexArray(vao);

        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);

        // Single fullscreen triangle, corners generated from gl_VertexID in the vertex shader.
        glDrawArrays(GL_TRIANGLES, 0, 3);

        glBindVertexArray(0);
        ShaderProgram.unbind();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
    }

    public static void free() {
        if (vao != 0) {
            glDeleteVertexArrays(vao);
            vao = 0;
        }
    }
}
