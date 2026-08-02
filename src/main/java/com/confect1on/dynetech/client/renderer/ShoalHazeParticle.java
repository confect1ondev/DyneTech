package com.confect1on.dynetech.client.renderer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * The still, dim cousin of {@link ShoalMoteParticle}. Used to build the subtle cloud that hangs
 * around a cluster of Shoal blocks: same sprite, but softer, larger, and mostly frozen in place.
 * Some particles hold perfectly still; others drift on a slow current.
 */
public class ShoalHazeParticle extends SingleQuadParticle {

    private static final ParticleRenderType RENDER_TYPE = new ParticleRenderType() {
        @Override
        public BufferBuilder begin(Tesselator tesselator, TextureManager textureManager) {
            ShoalMoteTexture.ensureRegistered();
            RenderSystem.depthMask(true);
            RenderSystem.setShader(GameRenderer::getParticleShader);
            RenderSystem.setShaderTexture(0, ShoalMoteTexture.TEXTURE);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            return tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public String toString() {
            return "dynetech:shoal_haze";
        }
    };

    private final float baseAlpha;

    protected ShoalHazeParticle(ClientLevel level, double x, double y, double z,
                                double vx, double vy, double vz) {
        super(level, x, y, z);
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.rCol = 110F / 255F;
        this.gCol = 200F / 255F;
        this.bCol = 1.0F;
        this.baseAlpha = 0.18F + this.random.nextFloat() * 0.12F;
        this.alpha = 0F;
        this.quadSize = 0.10F + this.random.nextFloat() * 0.10F;
        this.lifetime = 60 + this.random.nextInt(80);
        this.friction = 0.985F;
        this.gravity = 0.0F;
        this.hasPhysics = false;
    }

    @Override
    public void tick() {
        super.tick();
        // Slow fade-in over the first quarter of life, hold, then fade out. Reads as a hanging
        // cloud rather than a firework mote.
        float t = (float) this.age / (float) this.lifetime;
        float fade;
        if (t < 0.25F) {
            fade = t / 0.25F;
        } else if (t > 0.7F) {
            fade = 1.0F - (t - 0.7F) / 0.3F;
        } else {
            fade = 1.0F;
        }
        this.alpha = this.baseAlpha * fade;
    }

    @Override protected float getU0() { return 0.0F; }
    @Override protected float getU1() { return 1.0F; }
    @Override protected float getV0() { return 0.0F; }
    @Override protected float getV1() { return 1.0F; }

    @Override
    public int getLightColor(float partialTick) {
        return LightTexture.FULL_BRIGHT;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return RENDER_TYPE;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new ShoalHazeParticle(level, x, y, z, vx, vy, vz);
        }
    }
}
