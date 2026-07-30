package com.confect1on.dynetech.client.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import com.confect1on.dynetech.blockentity.CryoPreservatorBlockEntity;
import com.confect1on.dynetech.menu.CryoPreservatorMenu;

/**
 * Ice-themed procedural GUI. The whole machine area fills with a translucent ice cast that
 * rises from the bottom as the reservoir fills, so a glance at the panel tells you roughly
 * how much fuel is loaded. Info panel sits over the fill on the left; central blood vial slot
 * and side ice input slot sit above it. A furnace-style burn meter sits just under the blood
 * vial to show how far into the currently-melting ice we are.
 *
 * <p>Layout is procedural (no PNG assets), matching the Structure Shrinker style: flat panel,
 * clear zones, no overlap.
 */
public class CryoPreservatorScreen extends AbstractContainerScreen<CryoPreservatorMenu> {

    private static final int PANEL_DARK = 0xFF1F2A34;
    private static final int PANEL_MID = 0xFF3E4E5A;
    private static final int PANEL_FACE = 0xFFC4D8E4;
    private static final int PANEL_HIGHLIGHT = 0xFFE4EEF4;
    private static final int TANK_ICE = 0xB09EDCEE;
    private static final int TANK_ICE_HIGHLIGHT = 0xE0E5F6FA;
    private static final int INFO_BG = 0xEA1F2A34;
    private static final int INFO_STROKE = 0xFF6E8B99;
    private static final int SLOT_FRAME_OUTER = 0xFF1F2A34;
    private static final int SLOT_FRAME_INNER = 0xFF6E8B99;
    private static final int BURN_FRAME = 0xFF2B3B48;
    private static final int BURN_EMPTY = 0xFF39485A;
    private static final int BURN_FILL = 0xFF6ECFE8;
    private static final int TEXT_LIGHT = 0xFFF0F8FB;
    private static final int TEXT_MUTED = 0xFFB4D0DA;
    private static final int TEXT_TITLE = 0xFF1B2733;

    // Machine area bounds inside the screen. All slot positions live within this rectangle.
    private static final int MACHINE_X0 = 6, MACHINE_Y0 = 18;
    private static final int MACHINE_X1 = 194, MACHINE_Y1 = 102;

    // Info card sits on the left over the ice fill.
    private static final int INFO_X = 10, INFO_Y = 22;
    private static final int INFO_W = 74, INFO_H = 76;

    // Burn meter sits just below the blood frame.
    private static final int BURN_W = 28, BURN_H = 5;

    public CryoPreservatorScreen(CryoPreservatorMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 200;
        this.imageHeight = 200;
        this.inventoryLabelY = -1000; // hidden - custom labels only
        this.titleLabelX = 8;
        this.titleLabelY = 6;
    }

    @Override
    public void render(GuiGraphics gfx, int mx, int my, float partial) {
        this.renderBackground(gfx, mx, my, partial);
        super.render(gfx, mx, my, partial);
        this.renderTooltip(gfx, mx, my);
    }

    @Override
    protected void renderBg(GuiGraphics gfx, float partial, int mx, int my) {
        int x0 = leftPos, y0 = topPos, x1 = x0 + imageWidth, y1 = y0 + imageHeight;

        // Flat outer panel: dark border → mid → light face. Matches the other DyneTech screens.
        gfx.fill(x0, y0, x1, y1, PANEL_DARK);
        gfx.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, PANEL_MID);
        gfx.fill(x0 + 3, y0 + 3, x1 - 3, y1 - 3, PANEL_FACE);

        drawMachineFrame(gfx);
        drawIceFill(gfx);
        drawInfoCard(gfx);
        drawSlotFrame(gfx, leftPos + CryoPreservatorMenu.BLOOD_X, topPos + CryoPreservatorMenu.BLOOD_Y);
        drawIceGrid(gfx);
        drawBurnMeter(gfx);
        drawPlayerInvWells(gfx);
    }

    private void drawIceGrid(GuiGraphics gfx) {
        int gx = leftPos + CryoPreservatorMenu.ICE_GRID_X;
        int gy = topPos + CryoPreservatorMenu.ICE_GRID_Y;
        int step = CryoPreservatorMenu.ICE_SLOT_STEP;
        // Decorative outer frame around the 2x2 grid so it reads as one "ice input box".
        gfx.fill(gx - 6, gy - 6, gx + step + 22, gy + step + 22, PANEL_DARK);
        gfx.fill(gx - 5, gy - 5, gx + step + 21, gy + step + 21, INFO_STROKE);
        gfx.fill(gx - 4, gy - 4, gx + step + 20, gy + step + 20, PANEL_MID);
        for (int col = 0; col < 2; col++) {
            for (int row = 0; row < 2; row++) {
                drawSlotFrame(gfx, gx + col * step, gy + row * step);
            }
        }
    }

    /** Inset border around the whole machine area so the ice fill has a clear container. */
    private void drawMachineFrame(GuiGraphics gfx) {
        int x0 = leftPos + MACHINE_X0, y0 = topPos + MACHINE_Y0;
        int x1 = leftPos + MACHINE_X1, y1 = topPos + MACHINE_Y1;
        gfx.fill(x0, y0, x1, y1, PANEL_DARK);
        gfx.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, PANEL_MID);
        gfx.fill(x0 + 2, y0 + 2, x1 - 2, y1 - 2, 0xFF2E3E48);
    }

    /** Rises from the bottom of the machine area, proportional to iceStored / MAX. */
    private void drawIceFill(GuiGraphics gfx) {
        int x0 = leftPos + MACHINE_X0 + 2, x1 = leftPos + MACHINE_X1 - 2;
        int yBottom = topPos + MACHINE_Y1 - 2;
        int yTop = topPos + MACHINE_Y0 + 2;
        int totalH = yBottom - yTop;

        int stored = menu.getIceStored();
        int max = CryoPreservatorBlockEntity.MAX_ICE_STORAGE;
        int fillH = (int) ((long) stored * totalH / Math.max(1, max));
        if (fillH <= 0) return;

        int fillTop = yBottom - fillH;
        gfx.fill(x0, fillTop, x1, yBottom, TANK_ICE);
        // Crisp highlight strip along the fill line so the level reads clearly against the panel.
        gfx.fill(x0, fillTop, x1, fillTop + 1, TANK_ICE_HIGHLIGHT);
        // Vertical grain lines fade into the fill body for a chunky-ice look.
        for (int gx = x0 + 10; gx < x1; gx += 14) {
            gfx.fill(gx, fillTop + 2, gx + 1, yBottom - 2, TANK_ICE_HIGHLIGHT & 0x40FFFFFF);
        }
    }

    /** Info card is drawn over the ice fill so its text stays readable at any fill level. */
    private void drawInfoCard(GuiGraphics gfx) {
        int x0 = leftPos + INFO_X, y0 = topPos + INFO_Y;
        int x1 = x0 + INFO_W, y1 = y0 + INFO_H;
        gfx.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, INFO_STROKE);
        gfx.fill(x0, y0, x1, y1, INFO_BG);
    }

    /** Two-ring frame around a slot to make it pop against the ice fill. */
    private void drawSlotFrame(GuiGraphics gfx, int sx, int sy) {
        gfx.fill(sx - 3, sy - 3, sx + 19, sy + 19, SLOT_FRAME_OUTER);
        gfx.fill(sx - 2, sy - 2, sx + 18, sy + 18, SLOT_FRAME_INNER);
        gfx.fill(sx - 1, sy - 1, sx + 17, sy + 17, PANEL_HIGHLIGHT);
    }

    private void drawBurnMeter(GuiGraphics gfx) {
        // Center the meter horizontally under the blood slot.
        int bx = leftPos + CryoPreservatorMenu.BLOOD_X + (16 - BURN_W) / 2;
        int by = topPos + CryoPreservatorMenu.BLOOD_Y + 22;

        gfx.fill(bx - 1, by - 1, bx + BURN_W + 1, by + BURN_H + 1, BURN_FRAME);
        gfx.fill(bx, by, bx + BURN_W, by + BURN_H, BURN_EMPTY);

        int interval = menu.getIntervalTicks();
        int progress = menu.getIceProgress();
        if (progress > 0 && interval > 0) {
            // Furnace flames shrink as fuel depletes; mirror that so a full bar means "ice just
            // started melting" and empty means "about to swap in the next ice".
            int remaining = interval - progress;
            int fillW = (int) ((long) remaining * BURN_W / interval);
            if (fillW > 0) {
                gfx.fill(bx, by, bx + fillW, by + BURN_H, BURN_FILL);
                gfx.fill(bx, by, bx + fillW, by + 1, TANK_ICE_HIGHLIGHT);
            }
        }
    }

    private void drawPlayerInvWells(GuiGraphics gfx) {
        int invLeft = leftPos + (200 - 162) / 2;
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 9; c++)
                GeneSequencerScreen.drawSlotWell(gfx, invLeft + c * 18, topPos + 118 + r * 18);
        for (int c = 0; c < 9; c++)
            GeneSequencerScreen.drawSlotWell(gfx, invLeft + c * 18, topPos + 176);
    }

    @Override
    protected void renderLabels(GuiGraphics gfx, int mx, int my) {
        gfx.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, TEXT_TITLE, false);

        // renderLabels runs with a pose translated by (leftPos, topPos), so all coords are local.
        int tx = INFO_X + 6;
        int ty = INFO_Y + 6;

        // Stock line.
        int stored = menu.getIceStored();
        gfx.drawString(this.font,
                Component.translatable("gui.dynetech.cryo.stored", stored, CryoPreservatorBlockEntity.MAX_ICE_STORAGE)
                        .withStyle(ChatFormatting.WHITE),
                tx, ty, TEXT_LIGHT, false);

        // Preservation time remaining.
        long fuelTicks = menu.fuelTicksRemaining();
        ty += 14;
        gfx.drawString(this.font,
                Component.translatable("gui.dynetech.cryo.fresh_for").withStyle(ChatFormatting.GRAY),
                tx, ty, TEXT_MUTED, false);
        ty += 10;
        ChatFormatting durationColor = fuelTicks > 0 ? ChatFormatting.AQUA : ChatFormatting.RED;
        gfx.drawString(this.font, Component.literal(formatDuration(fuelTicks)).withStyle(durationColor),
                tx, ty, TEXT_LIGHT, false);

        // Status line at the bottom of the info card.
        ty += 18;
        Component status = menu.isPreserving()
                ? Component.translatable("gui.dynetech.cryo.status.preserving").withStyle(ChatFormatting.AQUA)
                : (stored == 0
                    ? Component.translatable("gui.dynetech.cryo.status.no_ice").withStyle(ChatFormatting.RED)
                    : Component.translatable("gui.dynetech.cryo.status.idle").withStyle(ChatFormatting.GRAY));
        gfx.drawString(this.font, status, tx, ty, TEXT_LIGHT, false);
    }

    private static String formatDuration(long ticks) {
        if (ticks <= 0L) return "0m";
        long seconds = ticks / 20L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m";
        return seconds + "s";
    }
}
