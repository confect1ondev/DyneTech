package com.confect1on.dynetech.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import com.confect1on.dynetech.DyneTech;

/**
 * First-person screen overlay for the local player's own burn-up. Renders a white-to-gold
 * flash that peaks in the first half of the sequence, then fades so the shockwave is not
 * washed out. Registered on the mod bus.
 */
@EventBusSubscriber(modid = DyneTech.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class GodhoodOverlayHandler {

    private GodhoodOverlayHandler() {}

    private static final int TOTAL_DURATION_TICKS = 100;

    @SubscribeEvent
    public static void registerLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(DyneTech.id("godhood_burnup_flash"), GodhoodOverlayHandler::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.options.hideGui) return;

        float elapsed = GodhoodBurnupManager.elapsedTicks(mc.player.getId(), delta.getGameTimeDeltaPartialTick(true));
        if (elapsed < 0F) return;
        if (elapsed > TOTAL_DURATION_TICKS) return;

        float alpha = flashAlpha(elapsed);
        if (alpha <= 0.01F) return;

        int color = packedColor(elapsed, alpha);
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        graphics.fill(0, 0, w, h, color);
    }

    /**
     * Flash timeline: rises quickly during ignition, holds through the first third of
     * burn-up, then eases down so the shockwave detonation lands on an already-clearing
     * screen. Never fully opaque - HUD readability matters even during a spectacle.
     */
    static float flashAlpha(float elapsed) {
        if (elapsed < GodhoodBurnupManager.IGNITION_END_TICKS) {
            float t = elapsed / (float) GodhoodBurnupManager.IGNITION_END_TICKS;
            return Mth.lerp(t, 0F, 0.55F);
        }
        // Hold near-peak for ~30 ticks (the first ~1.5s of the burn), then linear fade to
        // 0 by the collapse boundary.
        int holdEnd = GodhoodBurnupManager.IGNITION_END_TICKS + 30;
        if (elapsed < holdEnd) {
            return 0.55F;
        }
        if (elapsed < GodhoodBurnupManager.COLLAPSE_END_TICKS) {
            float t = (elapsed - holdEnd) / (float) (GodhoodBurnupManager.COLLAPSE_END_TICKS - holdEnd);
            return Mth.lerp(t, 0.55F, 0F);
        }
        return 0F;
    }

    /**
     * Pure white during ignition (bleach the screen), warming to a gold hue as the burn
     * gets going. Packed 0xAARRGGBB for GuiGraphics.fill.
     */
    static int packedColor(float elapsed, float alpha) {
        float warmT = Mth.clamp(elapsed / GodhoodBurnupManager.IGNITION_END_TICKS, 0F, 1F);
        int r = 255;
        int g = (int) Mth.lerp(warmT, 255F, 235F);
        int b = (int) Mth.lerp(warmT, 255F, 150F);
        int a = Mth.clamp((int) (alpha * 255F), 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

}
