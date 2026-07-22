package com.confect1on.dynetech.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import com.confect1on.dynetech.menu.GeneSplicerMenu;

public class GeneSplicerScreen extends AbstractContainerScreen<GeneSplicerMenu> {

    private static final int PANEL_LIGHT = 0xFFC6C6C6;
    private static final int PANEL_HIGHLIGHT = 0xFFFFFFFF;
    private static final int PANEL_SHADOW = 0xFF555555;
    private static final int TEXT_DARK = 0x404040;

    public GeneSplicerScreen(GeneSplicerMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
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

        gfx.fill(x0, y0, x1, y1, PANEL_LIGHT);
        gfx.fill(x0, y0, x1, y0 + 1, PANEL_HIGHLIGHT);
        gfx.fill(x0, y0, x0 + 1, y1, PANEL_HIGHLIGHT);
        gfx.fill(x1 - 1, y0, x1, y1, PANEL_SHADOW);
        gfx.fill(x0, y1 - 1, x1, y1, PANEL_SHADOW);

        int invTop = y0 + this.inventoryLabelY - 4;
        gfx.fill(x0 + 5, invTop, x1 - 5, invTop + 1, PANEL_SHADOW);

        GeneSequencerScreen.drawSlotWell(gfx, leftPos + GeneSplicerMenu.SLOT_A_X, topPos + GeneSplicerMenu.SLOTS_Y);
        GeneSequencerScreen.drawSlotWell(gfx, leftPos + GeneSplicerMenu.SLOT_B_X, topPos + GeneSplicerMenu.SLOTS_Y);

        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 9; c++) GeneSequencerScreen.drawSlotWell(gfx, leftPos + 8 + c * 18, topPos + 84 + r * 18);
        for (int c = 0; c < 9; c++) GeneSequencerScreen.drawSlotWell(gfx, leftPos + 8 + c * 18, topPos + 142);

        GeneSequencerScreen.drawProgressArrow(gfx,
                leftPos + GeneSplicerMenu.ARROW_X, topPos + GeneSplicerMenu.ARROW_Y,
                GeneSplicerMenu.ARROW_W, GeneSplicerMenu.ARROW_H,
                menu.getProgress(), menu.getProgressThreshold());
    }

    @Override
    protected void renderLabels(GuiGraphics gfx, int mx, int my) {
        gfx.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, TEXT_DARK, false);
        gfx.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, TEXT_DARK, false);
    }
}
