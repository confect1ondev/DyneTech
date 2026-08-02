package com.confect1on.dynetech.client.renderer.aura;

import com.confect1on.dynetech.client.renderer.ShoalMoteTexture;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * Shared render types for the Veil-driven aura visuals. Two pearl passes and one shell pass,
 * all going straight to the main framebuffer with plain GL blends - no framebuffer swap, so
 * the visuals render even if the Veil bloom pipeline is misconfigured or bypassed by a shader
 * pack. Bloom, when active, still catches the bright additive fragments naturally.
 *
 * <p>The pattern mirrors {@link com.confect1on.dynetech.client.renderer.ShoalRenderTypes}: one
 * alpha-blended core pass for a readable dot, one additive halo pass for the surrounding glow.
 * Shell is a separate untextured additive type for domes, ground discs, and god-rays.
 */
public final class AuraRenderTypes {

    private AuraRenderTypes() {}

    // Alpha-blended pearl nucleus. Depth-written so the pearls occlude each other reasonably.
    private static final RenderStateShard.TransparencyStateShard ALPHA_BLEND =
            new RenderStateShard.TransparencyStateShard(
                    "dynetech_aura_alpha",
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.blendFuncSeparate(
                                GlStateManager.SourceFactor.SRC_ALPHA,
                                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                                GlStateManager.SourceFactor.ONE,
                                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                    },
                    () -> {
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });

    // Additive halo - alpha modulates the RGB contribution, framebuffer sums up. Overlapping
    // halos build a brighter core naturally. No depth write; the crisp core carries occlusion.
    private static final RenderStateShard.TransparencyStateShard ADDITIVE_HALO =
            new RenderStateShard.TransparencyStateShard(
                    "dynetech_aura_additive",
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.blendFunc(
                                GlStateManager.SourceFactor.SRC_ALPHA,
                                GlStateManager.DestFactor.ONE);
                    },
                    () -> {
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });

    private static final RenderType PEARL_CORE = RenderType.create(
            "dynetech_aura_pearl_core",
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
            VertexFormat.Mode.QUADS,
            16 * 1024,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_TEX_LIGHTMAP_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(
                            ShoalMoteTexture.TEXTURE, false, false))
                    .setTransparencyState(ALPHA_BLEND)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .createCompositeState(false));

    private static final RenderType PEARL_HALO = RenderType.create(
            "dynetech_aura_pearl_halo",
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
            VertexFormat.Mode.QUADS,
            16 * 1024,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_TEX_LIGHTMAP_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(
                            ShoalMoteTexture.TEXTURE, false, false))
                    .setTransparencyState(ADDITIVE_HALO)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .createCompositeState(false));

    // Untextured additive geometry for domes, ground discs, and god-rays. The shape carries the
    // falloff, tinted per-vertex.
    private static final RenderType SHELL = RenderType.create(
            "dynetech_aura_shell",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            32 * 1024,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(ADDITIVE_HALO)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    public static RenderType pearlCore() {
        return PEARL_CORE;
    }

    public static RenderType pearlHalo() {
        return PEARL_HALO;
    }

    public static RenderType shell() {
        return SHELL;
    }
}
