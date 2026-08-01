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
 * The vanilla-particle twin of the swarm's motes: same procedural radial-gradient sprite, same
 * luminous blue tint, fullbright. Rendered off the particle atlas via its own render type since
 * the mote texture is a runtime {@code DynamicTexture} and never enters the atlas.
 */
public class ShoalMoteParticle extends SingleQuadParticle {

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
            return "dynetech:shoal_mote";
        }
    };

    private final float baseAlpha;

    protected ShoalMoteParticle(ClientLevel level, double x, double y, double z,
                                double vx, double vy, double vz) {
        super(level, x, y, z);
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        // Matches ShoalRenderer's core tint (110, 200, 255).
        this.rCol = 110F / 255F;
        this.gCol = 200F / 255F;
        this.bCol = 1.0F;
        this.baseAlpha = 0.75F + this.random.nextFloat() * 0.2F;
        this.alpha = this.baseAlpha;
        this.quadSize = 0.05F + this.random.nextFloat() * 0.05F;
        this.lifetime = 14 + this.random.nextInt(10);
        this.friction = 0.94F;
        this.gravity = 0.0F;
        this.hasPhysics = false;
    }

    @Override
    public void tick() {
        super.tick();
        float t = (float) this.age / (float) this.lifetime;
        this.alpha = this.baseAlpha * (1.0F - t * t);
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
            return new ShoalMoteParticle(level, x, y, z, vx, vy, vz);
        }
    }
}
