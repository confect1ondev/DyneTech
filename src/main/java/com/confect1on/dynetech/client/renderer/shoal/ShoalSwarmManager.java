package com.confect1on.dynetech.client.renderer.shoal;

import com.confect1on.dynetech.entity.ShoalEntity;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import foundry.veil.platform.VeilEventPlatform;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL30C.glBindBufferBase;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.glDispatchCompute;

/**
 * GPU half of the Shoal. The renderer enqueues visible swarms during the entity pass; at
 * AFTER_PARTICLES this manager uploads each swarm's ShoalParams block, dispatches the compute
 * sim over its mote SSBO, then draws the pool twice with the vertex-pulling mote shader: an
 * alpha-blended core pass and a premultiplied glow pass routed into Veil's bloom buffer.
 *
 * <p>Bindings are pinned at fixed indices in the shader (uniform block 2, SSBO 3) and bound
 * with raw glBindBufferBase per swarm. Veil's ShaderBlock system gives each block a persistent
 * index and only reprograms a shader's block name to that index when the (index, name) pair
 * changes, so putting one block per swarm behind the same name pointed every draw at whichever
 * swarm bound last. One shared UBO + explicit-binding SSBOs sidesteps that.
 */
public final class ShoalSwarmManager {

    public static final int MOTE_COUNT = 9216;
    private static final int MOTE_STRIDE = 64;
    private static final int LOCAL_SIZE = 256;

    private static final int UBO_BINDING = 2;
    private static final int SSBO_BINDING = 3;

    private static final ResourceLocation SIM_SHADER = ResourceLocation.fromNamespaceAndPath("dynetech", "shoal/sim");
    private static final ResourceLocation MOTE_SHADER = ResourceLocation.fromNamespaceAndPath("dynetech", "shoal/mote");

    // Same palette as the CPU path: luminous blue core that whitens with excitement, dim glow
    // tint because the glow accumulates additively. The glow is further scaled down since the
    // GPU pool holds roughly six times the old per-lobe mote count and additive overlap sums.
    private static final float CORE_R = 110F / 255F, CORE_G = 200F / 255F, CORE_B = 1F;
    private static final float EXCITE_R = 200F / 255F, EXCITE_G = 235F / 255F;
    private static final float GLOW_R = 40F / 255F, GLOW_G = 90F / 255F, GLOW_B = 140F / 255F;
    private static final float GLOW_ALPHA = 0.85F;
    private static final float GLOW_DENSITY = 0.3F;
    private static final float GLOW_SCALE = 3.5F;

    private static final Map<Integer, GpuSwarm> SWARMS = new HashMap<>();
    private static final List<GpuSwarm> QUEUE = new ArrayList<>();
    private static int vao;
    private static int sharedUbo;
    private static ByteBuffer uboStaging;
    private static long lastSweepTick = Long.MIN_VALUE;

    private ShoalSwarmManager() {
    }

    public static void init() {
        VeilEventPlatform.INSTANCE.onVeilRenderLevelStage((stage, levelRenderer, bufferSource, matrixStack,
                                                          frustumMatrix, projectionMatrix, renderTick,
                                                          deltaTracker, camera, frustum) -> {
            if (stage == VeilRenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                drawQueued(frustumMatrix, projectionMatrix, camera);
            }
        });
        VeilEventPlatform.INSTANCE.onFreeNativeResources(ShoalSwarmManager::freeAll);
    }

    /** Called from the entity render pass. Advances the CPU state and queues the GPU work. */
    public static void enqueue(ShoalEntity entity, Vec3 anchor, float partialTicks, int drawCount) {
        GpuSwarm swarm = SWARMS.get(entity.getId());
        if (swarm == null) {
            swarm = new GpuSwarm(entity, seedFor(entity));
            SWARMS.put(entity.getId(), swarm);
        }
        swarm.state.update(entity, anchor, partialTicks);
        swarm.drawCount = drawCount;
        if (!swarm.queued) {
            swarm.queued = true;
            QUEUE.add(swarm);
        }
        maybeSweep(entity.tickCount);
    }

    private static void drawQueued(Matrix4fc modelView, Matrix4fc projection, Camera camera) {
        if (QUEUE.isEmpty()) return;

        ShaderProgram sim = VeilRenderSystem.renderer().getShaderManager().getShader(SIM_SHADER);
        ShaderProgram draw = VeilRenderSystem.renderer().getShaderManager().getShader(MOTE_SHADER);
        if (sim == null || draw == null) {
            clearQueue();
            return;
        }

        if (sharedUbo == 0) {
            sharedUbo = glGenBuffers();
            glBindBuffer(GL_UNIFORM_BUFFER, sharedUbo);
            glBufferData(GL_UNIFORM_BUFFER, ShoalSwarmState.UBO_SIZE, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_UNIFORM_BUFFER, 0);
            uboStaging = MemoryUtil.memAlloc(ShoalSwarmState.UBO_SIZE);
        }

        // Sim dispatches for every queued swarm, then one barrier before any of them draw.
        sim.bind();
        for (GpuSwarm s : QUEUE) {
            uploadParams(s);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, SSBO_BINDING, s.ssbo);
            glDispatchCompute(MOTE_COUNT / LOCAL_SIZE, 1, 1);
        }
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        if (vao == 0) vao = glGenVertexArrays();
        glBindVertexArray(vao);
        Vec3 cam = camera.getPosition();

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();

        // Core pass: alpha-blended crisp dots, depth-written like the old mote render type.
        RenderSystem.depthMask(true);
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        draw.bind();
        draw.getUniformSafe("ProjMat").setMatrix(projection);
        draw.getUniformSafe("ModelViewMat").setMatrix(modelView);
        for (GpuSwarm s : QUEUE) {
            uploadParams(s);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, SSBO_BINDING, s.ssbo);
            draw.getUniformSafe("GlowPass").setFloat(0F);
            draw.getUniformSafe("SizeScale").setFloat(1F);
            float excite = s.state.excitement();
            draw.getUniformSafe("ColorMod").setVector(
                    CORE_R + (EXCITE_R - CORE_R) * excite * 0.6F,
                    CORE_G + (EXCITE_G - CORE_G) * excite * 0.6F,
                    CORE_B, 1F);
            draw.getUniformSafe("AnchorCamPos").setVector(
                    (float) (s.state.anchorX() - cam.x),
                    (float) (s.state.anchorY() - cam.y),
                    (float) (s.state.anchorZ() - cam.z));
            glDrawArrays(GL_TRIANGLES, 0, s.drawCount * 6);
        }

        // Glow pass into the bloom buffer. Premultiplied add with alpha forced to zero, so the
        // bloom composite blurs it without re-adding the raw color. If bloom is unavailable the
        // shard is a no-op and this lands in the main target as a plain additive halo.
        VeilRenderSystem.BLOOM_SHARD.setupRenderState();
        RenderSystem.depthMask(false);
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        for (GpuSwarm s : QUEUE) {
            uploadParams(s);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, SSBO_BINDING, s.ssbo);
            float excite = s.state.excitement();
            draw.getUniformSafe("GlowPass").setFloat(1F);
            draw.getUniformSafe("SizeScale").setFloat(GLOW_SCALE * (1F + 0.15F * excite));
            draw.getUniformSafe("ColorMod").setVector(GLOW_R, GLOW_G, GLOW_B,
                    Math.min(1F, GLOW_ALPHA * (1F + 0.2F * excite)) * GLOW_DENSITY);
            draw.getUniformSafe("AnchorCamPos").setVector(
                    (float) (s.state.anchorX() - cam.x),
                    (float) (s.state.anchorY() - cam.y),
                    (float) (s.state.anchorZ() - cam.z));
            glDrawArrays(GL_TRIANGLES, 0, s.drawCount * 6);
        }
        VeilRenderSystem.BLOOM_SHARD.clearRenderState();

        ShaderProgram.unbind();
        glBindVertexArray(0);
        glBindBufferBase(GL_UNIFORM_BUFFER, UBO_BINDING, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, SSBO_BINDING, 0);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        clearQueue();
    }

    private static void uploadParams(GpuSwarm s) {
        uboStaging.clear();
        s.state.write(uboStaging);
        uboStaging.rewind();
        glBindBuffer(GL_UNIFORM_BUFFER, sharedUbo);
        glBufferSubData(GL_UNIFORM_BUFFER, 0L, uboStaging);
        glBindBufferBase(GL_UNIFORM_BUFFER, UBO_BINDING, sharedUbo);
    }

    private static void clearQueue() {
        for (GpuSwarm s : QUEUE) s.queued = false;
        QUEUE.clear();
    }

    private static void maybeSweep(long tick) {
        if (tick - lastSweepTick < 200L) return;
        lastSweepTick = tick;
        Minecraft mc = Minecraft.getInstance();
        Iterator<Map.Entry<Integer, GpuSwarm>> it = SWARMS.entrySet().iterator();
        while (it.hasNext()) {
            GpuSwarm s = it.next().getValue();
            if (s.entity.isRemoved() || s.entity.level() != mc.level) {
                s.free();
                it.remove();
            }
        }
    }

    /** Drops every GPU record. Runs on resource reload teardown and on level unload. */
    public static void freeAll() {
        for (GpuSwarm s : SWARMS.values()) s.free();
        SWARMS.clear();
        QUEUE.clear();
        if (vao != 0) {
            glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (sharedUbo != 0) {
            glDeleteBuffers(sharedUbo);
            sharedUbo = 0;
        }
        if (uboStaging != null) {
            MemoryUtil.memFree(uboStaging);
            uboStaging = null;
        }
    }

    private static int seedFor(ShoalEntity entity) {
        int seed = entity.getSeed();
        return seed == 0 ? entity.getId() * 0x9E3779B1 : seed;
    }

    private static final class GpuSwarm {
        final ShoalEntity entity;
        final ShoalSwarmState state;
        final int ssbo;
        int drawCount;
        boolean queued;

        GpuSwarm(ShoalEntity entity, int seed) {
            this.entity = entity;
            this.state = new ShoalSwarmState(seed);
            this.ssbo = glGenBuffers();
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, this.ssbo);
            glBufferData(GL_SHADER_STORAGE_BUFFER, (long) MOTE_COUNT * MOTE_STRIDE, GL_DYNAMIC_COPY);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        }

        void free() {
            glDeleteBuffers(ssbo);
        }
    }
}
