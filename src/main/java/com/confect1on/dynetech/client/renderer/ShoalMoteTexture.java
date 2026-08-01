package com.confect1on.dynetech.client.renderer;

import com.confect1on.dynetech.DyneTech;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-side dynamic texture for the Shoal mote sprites. Generated procedurally on client init
 * so we don't ship a PNG for a shape that's just a soft radial gradient. Registered under a mod
 * id so the {@link ShoalRenderTypes} shard can reference it by name.
 */
public final class ShoalMoteTexture {

    public static final ResourceLocation TEXTURE = DyneTech.id("mote");
    private static final int SIZE = 32;

    private static boolean registered;

    private ShoalMoteTexture() {}

    public static void ensureRegistered() {
        if (registered) return;
        NativeImage image = buildRadialGradient();
        DynamicTexture texture = new DynamicTexture(image);
        Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
        registered = true;
    }

    /**
     * White radial falloff, alpha = smoothstep on distance-from-center. Squared falloff gives a
     * softer edge without adding a second sample. Colors are white so the RGB tint comes entirely
     * from the vertex color at draw time.
     */
    private static NativeImage buildRadialGradient() {
        NativeImage img = new NativeImage(SIZE, SIZE, false);
        double center = SIZE * 0.5D;
        double radius = SIZE * 0.48D;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double dx = (x + 0.5D) - center;
                double dy = (y + 0.5D) - center;
                double dist = Math.sqrt(dx * dx + dy * dy);
                double t = Math.max(0.0D, 1.0D - dist / radius);
                double alpha = t * t;
                int a = (int) Math.round(alpha * 255.0D);
                // NativeImage stores as ABGR little-endian. White RGB with variable alpha.
                img.setPixelRGBA(x, y, (a << 24) | 0x00FFFFFF);
            }
        }
        return img;
    }
}
