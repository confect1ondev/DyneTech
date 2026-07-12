package com.confect1on.dynetech.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import com.confect1on.dynetech.client.ClientSettings;
import com.confect1on.dynetech.menu.StructureShrinkerMenu;
import com.confect1on.dynetech.network.DTPayloads;

public class StructureShrinkerScreen extends AbstractContainerScreen<StructureShrinkerMenu> {

    public StructureShrinkerScreen(StructureShrinkerMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 200;
        this.imageHeight = 140;
        this.inventoryLabelY = -1000; // hidden
    }

    @Override
    protected void init() {
        super.init();
        int cx = leftPos + imageWidth / 2;
        int by = topPos + imageHeight - 30;

        Button shrinkBtn = Button.builder(Component.translatable("gui.dynetech.structure_shrinker.shrink"),
                        b -> PacketDistributor.sendToServer(new DTPayloads.ActivateShrinker()))
                .bounds(cx - 60, by, 120, 20).build();
        shrinkBtn.active = menu.hasSelection();
        addRenderableWidget(shrinkBtn);

        addRenderableWidget(Button.builder(bbLabel(), b -> {
                    ClientSettings.toggleShowSelectionBox();
                    b.setMessage(bbLabel());
                })
                .bounds(cx - 60, by - 25, 120, 20).build());
    }

    private static Component bbLabel() {
        return Component.translatable(ClientSettings.showSelectionBox()
                ? "gui.dynetech.structure_shrinker.hide_box"
                : "gui.dynetech.structure_shrinker.show_box");
    }

    @Override
    protected void renderBg(GuiGraphics gfx, float partial, int mx, int my) {
        gfx.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF303030);
        gfx.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xFFC6C6C6);
    }

    @Override
    protected void renderLabels(GuiGraphics gfx, int mx, int my) {
        gfx.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 0x404040, false);

        int textX = 10;
        int textY = 25;
        if (menu.hasSelection()) {
            BlockPos s = menu.getSelectionStart();
            BlockPos e = menu.getSelectionEnd();
            gfx.drawString(this.font, Component.translatable("gui.dynetech.structure_shrinker.corner_a"),
                    textX, textY, 0x404040, false);
            gfx.drawString(this.font, s.getX() + ", " + s.getY() + ", " + s.getZ(),
                    textX + 60, textY, 0x202020, false);
            gfx.drawString(this.font, Component.translatable("gui.dynetech.structure_shrinker.corner_b"),
                    textX, textY + 12, 0x404040, false);
            gfx.drawString(this.font, e.getX() + ", " + e.getY() + ", " + e.getZ(),
                    textX + 60, textY + 12, 0x202020, false);

            int dx = Math.abs(e.getX() - s.getX()) + 1;
            int dy = Math.abs(e.getY() - s.getY()) + 1;
            int dz = Math.abs(e.getZ() - s.getZ()) + 1;
            gfx.drawString(this.font,
                    Component.translatable("gui.dynetech.structure_shrinker.size", dx, dy, dz),
                    textX, textY + 28, 0x404040, false);
        } else {
            gfx.drawString(this.font, Component.translatable("gui.dynetech.structure_shrinker.no_selection"),
                    textX, textY, 0xB00020, false);
        }
    }
}
