package com.confect1on.dynetech.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import com.confect1on.dynetech.menu.GeneSequencerMenu;

/**
 * Textureless container screen — colors and slot positions match vanilla containers so items
 * and hover tooltips render exactly like a vanilla furnace / crafting table.
 *
 * <p>All slot positions come from {@link GeneSequencerMenu}'s public constants so the drawn
 * background wells always sit under the actual slot bounds.
 */
public class GeneSequencerScreen extends AbstractContainerScreen<GeneSequencerMenu> {

    private static final int PANEL_LIGHT = 0xFFC6C6C6;
    private static final int PANEL_HIGHLIGHT = 0xFFFFFFFF;
    private static final int PANEL_SHADOW = 0xFF555555;
    private static final int SLOT_INNER = 0xFF8B8B8B;
    private static final int SLOT_BORDER = 0xFF373737;
    private static final int TEXT_DARK = 0x404040;

    public GeneSequencerScreen(GeneSequencerMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    /**
     * Canonical NeoForge 1.21 container-screen render order:
     * <ol>
     *   <li>{@link #renderBackground} — the darkened translucent overlay behind the panel</li>
     *   <li>{@code super.render} — {@code AbstractContainerScreen} draws bg → slots → labels</li>
     *   <li>{@link #renderTooltip} — item tooltip for the hovered slot</li>
     * </ol>
     * The explicit tooltip call at the end matches the pattern used by vanilla screens like
     * {@code CraftingScreen} and {@code InventoryScreen}, and by every reference NeoForge tutorial.
     * The parent {@code render} does invoke it internally, but redundant calls are visually
     * idempotent — this makes the intent obvious and immune to upstream refactors.
     */
    @Override
    public void render(GuiGraphics gfx, int mx, int my, float partial) {
        this.renderBackground(gfx, mx, my, partial);
        super.render(gfx, mx, my, partial);
        this.renderTooltip(gfx, mx, my);
    }

    @Override
    protected void renderBg(GuiGraphics gfx, float partial, int mx, int my) {
        int x0 = leftPos, y0 = topPos, x1 = x0 + imageWidth, y1 = y0 + imageHeight;

        gfx.fill(x0, y0, x1, y1, PANEL_LIGHT);
        gfx.fill(x0, y0, x1, y0 + 1, PANEL_HIGHLIGHT);
        gfx.fill(x0, y0, x0 + 1, y1, PANEL_HIGHLIGHT);
        gfx.fill(x1 - 1, y0, x1, y1, PANEL_SHADOW);
        gfx.fill(x0, y1 - 1, x1, y1, PANEL_SHADOW);

        int invTop = y0 + this.inventoryLabelY - 4;
        gfx.fill(x0 + 5, invTop, x1 - 5, invTop + 1, PANEL_SHADOW);

        drawSlotWell(gfx, leftPos + GeneSequencerMenu.INPUT_SLOT_X, topPos + GeneSequencerMenu.INPUT_SLOT_Y);
        for (int i = 0; i < 3; i++) {
            drawSlotWell(gfx,
                    leftPos + GeneSequencerMenu.OUTPUT_SLOT_X + i * GeneSequencerMenu.OUTPUT_SLOT_STEP,
                    topPos + GeneSequencerMenu.OUTPUT_SLOT_Y);
        }

        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 9; c++) drawSlotWell(gfx, leftPos + 8 + c * 18, topPos + 84 + r * 18);
        for (int c = 0; c < 9; c++) drawSlotWell(gfx, leftPos + 8 + c * 18, topPos + 142);

        drawProgressArrow(gfx,
                leftPos + GeneSequencerMenu.ARROW_X, topPos + GeneSequencerMenu.ARROW_Y,
                GeneSequencerMenu.ARROW_W, GeneSequencerMenu.ARROW_H,
                menu.getProgress(), menu.getProgressThreshold());
    }

    static void drawSlotWell(GuiGraphics gfx, int x, int y) {
        gfx.fill(x - 1, y - 1, x + 17, y + 17, SLOT_BORDER);
        gfx.fill(x, y, x + 16, y + 16, SLOT_INNER);
    }

    static void drawProgressArrow(GuiGraphics gfx, int x, int y, int w, int h, int progress, int threshold) {
        int trackTop = y + 1;
        int trackBot = y + h - 1;
        gfx.fill(x, trackTop, x + w - 4, trackBot, 0xFF7A7A7A);
        for (int i = 0; i < 4; i++) {
            gfx.fill(x + w - 4 + i, trackTop + i, x + w - 3 + i, trackBot - i, 0xFF7A7A7A);
        }
        int fillW = Math.min(w - 4, (w - 4) * progress / Math.max(1, threshold));
        if (fillW > 0) {
            gfx.fill(x, trackTop, x + fillW, trackBot, 0xFF3EBFA6);
            if (fillW >= w - 4) {
                for (int i = 0; i < 4; i++) {
                    gfx.fill(x + w - 4 + i, trackTop + i, x + w - 3 + i, trackBot - i, 0xFF3EBFA6);
                }
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics gfx, int mx, int my) {
        gfx.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, TEXT_DARK, false);
        gfx.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, TEXT_DARK, false);
    }
}
