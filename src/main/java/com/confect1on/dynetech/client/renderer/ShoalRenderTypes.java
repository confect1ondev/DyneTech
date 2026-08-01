package com.confect1on.dynetech.client.renderer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * Custom render type for the Shoal mote swarm: source-alpha additive blend, no cull,
 * depth-tested, with a lightmap slot forced to full-bright at vertex-write time.
 *
 * <p>Vanilla's {@code ADDITIVE_TRANSPARENCY} is {@code blendFunc(ONE, ONE)}, which discards the
 * fragment alpha for RGB blending: every mote would slam its full color into the framebuffer
 * regardless of the swarm's fade curve, saturating stacks toward white on any overlap and
 * making the fade-in/out invisible. We use {@code SRC_ALPHA, ONE} so alpha scales each mote's
 * contribution smoothly and the base tint survives overlap.
 */
public final class ShoalRenderTypes {

    // Standard alpha blend. Each mote reads as a solid colored dot regardless of the framebuffer
    // brightness it's drawn over, which was the failure mode of both additive and screen blends:
    // dim contributions against a bright sky (or bright contributions saturating to white on
    // overlap) meant most of the swarm just wasn't visible. Alpha blend trades the glow bloom for
    // guaranteed color fidelity, which for a "cloud of tiny motes" reading is the right tradeoff.
    private static final RenderStateShard.TransparencyStateShard ALPHA_BLEND =
            new RenderStateShard.TransparencyStateShard(
                    "dynetech_shoal_alpha",
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

    private static final RenderType MOTE = RenderType.create(
            "dynetech_shoal_mote",
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
            VertexFormat.Mode.QUADS,
            32 * 1024,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_TEX_LIGHTMAP_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(
                            ShoalMoteTexture.TEXTURE, false, false))
                    .setTransparencyState(ALPHA_BLEND)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    // Depth write matters here: entities draw before translucent terrain, so
                    // without it water behind the swarm blended over the dots and dimmed them.
                    // The shader's 0.1 alpha discard keeps the soft quad edges from stamping
                    // invisible depth. Halos skip the depth write since additive glow dimming
                    // under water is barely noticeable and halo-sized depth stamps would
                    // punch visible holes in the water surface.
                    .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .createCompositeState(false));

    // Halo pass: additive, scaled by source alpha. Halos are drawn dim and large so overlapping
    // contributions build a soft bloom around the core dots without saturating to white the way
    // full-brightness additive motes did.
    private static final RenderStateShard.TransparencyStateShard ADDITIVE_HALO =
            new RenderStateShard.TransparencyStateShard(
                    "dynetech_shoal_halo",
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

    private static final RenderType HALO = RenderType.create(
            "dynetech_shoal_halo",
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
            VertexFormat.Mode.QUADS,
            32 * 1024,
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

    private ShoalRenderTypes() {}

    public static RenderType mote() {
        return MOTE;
    }

    public static RenderType halo() {
        return HALO;
    }
}
