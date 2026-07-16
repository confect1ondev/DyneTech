package com.confect1on.dynetech.client.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import com.confect1on.dynetech.client.ClientSettings;
import com.confect1on.dynetech.menu.StructureShrinkerMenu;
import com.confect1on.dynetech.network.DTPayloads;

public class StructureShrinkerScreen extends AbstractContainerScreen<StructureShrinkerMenu> {

    // Panel colors match the block front frame.
    private static final int COLOR_PANEL_OUTER = 0xFF141416;
    private static final int COLOR_PANEL_FILL  = 0xFF26262A;
    private static final int COLOR_PANEL_BEVEL_LIGHT = 0xFF3A3A40;
    private static final int COLOR_PANEL_BEVEL_DARK  = 0xFF0A0A0C;

    // Side panel (right of viewport). Slightly deeper base color than the main panel.
    private static final int COLOR_SIDE_FILL   = 0xFF1D1D22;
    private static final int COLOR_SIDE_BORDER = 0xFFFF3040;
    private static final int COLOR_SIDE_BRACKET = 0xFFFFC0C6;

    // Viewport (red gradient + grid).
    private static final int COLOR_VIEWPORT_TOP    = 0xFF3A0006;
    private static final int COLOR_VIEWPORT_BOTTOM = 0xFF860012;
    private static final int COLOR_VIEWPORT_VIGNETTE = 0x80000000;
    private static final int COLOR_GRID_MAJOR = 0x80FF3A46;
    private static final int COLOR_GRID_MINOR = 0x40FF2030;
    private static final int COLOR_VIEWPORT_BORDER = 0xFFFF3040;
    private static final int GRID_MINOR_SPACING = 8;
    private static final int GRID_MAJOR_SPACING = 32;

    // Safety cap on rendered blocks after interior culling. A 100^3 solid shell is ~59k,
    // so this ceilings the worst reasonable case without capping anything realistic.
    private static final int MAX_PREVIEW_RENDERED = 40000;

    // Layout (coords relative to leftPos/topPos).
    private static final int PANEL_W = 280;
    private static final int PANEL_H = 172;

    private static final int VIEWPORT_X = 8;
    private static final int VIEWPORT_Y = 18;
    private static final int VIEWPORT_W = 190;
    private static final int VIEWPORT_H = 146;

    private static final int SIDE_X = 204;
    private static final int SIDE_Y = 18;
    private static final int SIDE_W = 68;
    private static final int SIDE_H = 146;

    private static final int BUTTON_ROW_H = 22;
    private static final int ICON_BTN_W = 22;
    private static final int TEXT_BTN_W = 40;
    private static final int BUTTON_GAP = 4;

    public StructureShrinkerScreen(StructureShrinkerMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = PANEL_W;
        this.imageHeight = PANEL_H;
        this.inventoryLabelY = -1000; // hidden
    }

    @Override
    protected void init() {
        super.init();

        int rowY = topPos + SIDE_Y + SIDE_H - BUTTON_ROW_H - 6;
        int rowW = ICON_BTN_W + BUTTON_GAP + TEXT_BTN_W;
        int rowX = leftPos + SIDE_X + (SIDE_W - rowW) / 2;

        addRenderableWidget(new BoundingBoxIconButton(
                rowX, rowY, ICON_BTN_W, BUTTON_ROW_H,
                b -> {
                    ClientSettings.toggleShowSelectionBox();
                    b.setMessage(bbNarration());
                }));

        FuturisticTextButton shrinkBtn = new FuturisticTextButton(
                rowX + ICON_BTN_W + BUTTON_GAP, rowY, TEXT_BTN_W, BUTTON_ROW_H,
                Component.translatable("gui.dynetech.structure_shrinker.shrink"),
                b -> PacketDistributor.sendToServer(new DTPayloads.ActivateShrinker()));
        shrinkBtn.active = menu.hasSelection();
        addRenderableWidget(shrinkBtn);
    }

    private static Component bbNarration() {
        return Component.translatable(ClientSettings.showSelectionBox()
                ? "gui.dynetech.structure_shrinker.hide_box"
                : "gui.dynetech.structure_shrinker.show_box");
    }

    @Override
    protected void renderBg(GuiGraphics gfx, float partial, int mx, int my) {
        // Panel: outer border + fill + subtle bevel.
        gfx.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, COLOR_PANEL_OUTER);
        gfx.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, COLOR_PANEL_FILL);
        gfx.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + 2, COLOR_PANEL_BEVEL_LIGHT);
        gfx.fill(leftPos + 1, topPos + 1, leftPos + 2, topPos + imageHeight - 1, COLOR_PANEL_BEVEL_LIGHT);
        gfx.fill(leftPos + 1, topPos + imageHeight - 2, leftPos + imageWidth - 1, topPos + imageHeight - 1, COLOR_PANEL_BEVEL_DARK);
        gfx.fill(leftPos + imageWidth - 2, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, COLOR_PANEL_BEVEL_DARK);

        int vx1 = leftPos + VIEWPORT_X;
        int vy1 = topPos + VIEWPORT_Y;
        int vx2 = vx1 + VIEWPORT_W;
        int vy2 = vy1 + VIEWPORT_H;

        renderViewportBackground(gfx, vx1, vy1, vx2, vy2, partial);
        renderSelectionPreview(gfx, vx1, vy1, vx2, vy2, partial);
        renderViewportBorder(gfx, vx1, vy1, vx2, vy2);

        int sx1 = leftPos + SIDE_X;
        int sy1 = topPos + SIDE_Y;
        int sx2 = sx1 + SIDE_W;
        int sy2 = sy1 + SIDE_H;
        renderSidePanel(gfx, sx1, sy1, sx2, sy2);
    }

    private void renderViewportBackground(GuiGraphics gfx, int x1, int y1, int x2, int y2, float partial) {
        gfx.fillGradient(x1, y1, x2, y2, COLOR_VIEWPORT_TOP, COLOR_VIEWPORT_BOTTOM);

        for (int gx = 0; gx <= VIEWPORT_W; gx += GRID_MINOR_SPACING) {
            int color = (gx % GRID_MAJOR_SPACING == 0) ? COLOR_GRID_MAJOR : COLOR_GRID_MINOR;
            gfx.fill(x1 + gx, y1, x1 + gx + 1, y2, color);
        }
        for (int gy = 0; gy <= VIEWPORT_H; gy += GRID_MINOR_SPACING) {
            int color = (gy % GRID_MAJOR_SPACING == 0) ? COLOR_GRID_MAJOR : COLOR_GRID_MINOR;
            gfx.fill(x1, y1 + gy, x2, y1 + gy + 1, color);
        }

        gfx.fillGradient(x1, y1, x2, y1 + 12, COLOR_VIEWPORT_VIGNETTE, 0x00000000);
        gfx.fillGradient(x1, y2 - 12, x2, y2, 0x00000000, COLOR_VIEWPORT_VIGNETTE);

        float t = (Minecraft.getInstance().player != null
                ? (Minecraft.getInstance().player.tickCount + partial)
                : partial) * 0.5f;
        int scanY = y1 + (int) ((Mth.sin(t) * 0.5f + 0.5f) * (VIEWPORT_H - 2));
        gfx.fill(x1, scanY, x2, scanY + 1, 0x33FFA0A8);
    }

    private void renderViewportBorder(GuiGraphics gfx, int x1, int y1, int x2, int y2) {
        gfx.fill(x1 - 1, y1 - 1, x2 + 1, y1,     COLOR_VIEWPORT_BORDER);
        gfx.fill(x1 - 1, y2,     x2 + 1, y2 + 1, COLOR_VIEWPORT_BORDER);
        gfx.fill(x1 - 1, y1 - 1, x1,     y2 + 1, COLOR_VIEWPORT_BORDER);
        gfx.fill(x2,     y1 - 1, x2 + 1, y2 + 1, COLOR_VIEWPORT_BORDER);
        int c = 6;
        int bracket = 0xFFFFC0C6;
        gfx.fill(x1 - 1, y1 - 1, x1 + c, y1,     bracket);
        gfx.fill(x1 - 1, y1 - 1, x1,     y1 + c, bracket);
        gfx.fill(x2 - c, y1 - 1, x2 + 1, y1,     bracket);
        gfx.fill(x2,     y1 - 1, x2 + 1, y1 + c, bracket);
        gfx.fill(x1 - 1, y2,     x1 + c, y2 + 1, bracket);
        gfx.fill(x1 - 1, y2 - c, x1,     y2 + 1, bracket);
        gfx.fill(x2 - c, y2,     x2 + 1, y2 + 1, bracket);
        gfx.fill(x2,     y2 - c, x2 + 1, y2 + 1, bracket);
    }

    private void renderSidePanel(GuiGraphics gfx, int x1, int y1, int x2, int y2) {
        gfx.fill(x1, y1, x2, y2, COLOR_SIDE_FILL);
        gfx.fill(x1 - 1, y1 - 1, x2 + 1, y1,     COLOR_SIDE_BORDER);
        gfx.fill(x1 - 1, y2,     x2 + 1, y2 + 1, COLOR_SIDE_BORDER);
        gfx.fill(x1 - 1, y1 - 1, x1,     y2 + 1, COLOR_SIDE_BORDER);
        gfx.fill(x2,     y1 - 1, x2 + 1, y2 + 1, COLOR_SIDE_BORDER);
        int c = 5;
        gfx.fill(x1 - 1, y1 - 1, x1 + c, y1,     COLOR_SIDE_BRACKET);
        gfx.fill(x1 - 1, y1 - 1, x1,     y1 + c, COLOR_SIDE_BRACKET);
        gfx.fill(x2 - c, y1 - 1, x2 + 1, y1,     COLOR_SIDE_BRACKET);
        gfx.fill(x2,     y1 - 1, x2 + 1, y1 + c, COLOR_SIDE_BRACKET);
        gfx.fill(x1 - 1, y2,     x1 + c, y2 + 1, COLOR_SIDE_BRACKET);
        gfx.fill(x1 - 1, y2 - c, x1,     y2 + 1, COLOR_SIDE_BRACKET);
        gfx.fill(x2 - c, y2,     x2 + 1, y2 + 1, COLOR_SIDE_BRACKET);
        gfx.fill(x2,     y2 - c, x2 + 1, y2 + 1, COLOR_SIDE_BRACKET);

        // Separator between info and buttons.
        int sepY = y2 - BUTTON_ROW_H - 12;
        gfx.fill(x1 + 4, sepY, x2 - 4, sepY + 1, 0x80FF3040);
    }

    private void renderSelectionPreview(GuiGraphics gfx, int x1, int y1, int x2, int y2, float partial) {
        if (!menu.hasSelection()) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        BlockPos a = menu.getSelectionStart();
        BlockPos b = menu.getSelectionEnd();
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX());
        int maxY = Math.max(a.getY(), b.getY());
        int maxZ = Math.max(a.getZ(), b.getZ());
        int dx = maxX - minX + 1;
        int dy = maxY - minY + 1;
        int dz = maxZ - minZ + 1;

        // Half-diagonal bounds the projected extent under any rotation, so fitting that to
        // the viewport keeps the whole selection inside the frame at any spin/tilt angle.
        float halfDiag = 0.5f * Mth.sqrt(dx * (float) dx + dy * (float) dy + dz * (float) dz);
        float fit = Math.min(VIEWPORT_W, VIEWPORT_H) * 0.42f;
        float scale = fit / halfDiag;

        float centerX = (x1 + x2) / 2f;
        float centerY = (y1 + y2) / 2f + 4f;

        float t = Minecraft.getInstance().player != null
                ? (Minecraft.getInstance().player.tickCount + partial)
                : partial;
        float spin = (t * 1.2f) % 360f;

        gfx.enableScissor(x1, y1, x2, y2);
        PoseStack pose = gfx.pose();
        pose.pushPose();
        pose.translate(centerX, centerY, 200f);
        pose.scale(scale, -scale, scale);
        pose.mulPose(Axis.XP.rotationDegrees(28f));
        pose.mulPose(Axis.YP.rotationDegrees(spin));
        pose.translate(-dx / 2f, -dy / 2f, -dz / 2f);

        BlockRenderDispatcher brd = Minecraft.getInstance().getBlockRenderer();
        MultiBufferSource.BufferSource bs = Minecraft.getInstance().renderBuffers().bufferSource();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        int rendered = 0;
        for (int y = 0; y < dy && rendered < MAX_PREVIEW_RENDERED; y++) {
            for (int z = 0; z < dz && rendered < MAX_PREVIEW_RENDERED; z++) {
                for (int x = 0; x < dx && rendered < MAX_PREVIEW_RENDERED; x++) {
                    cursor.set(minX + x, minY + y, minZ + z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) continue;
                    if (state.getRenderShape() != RenderShape.MODEL) continue;
                    // Skip interior blocks (all 6 neighbors in-selection are fully occluding).
                    // Boundary blocks always render since out-of-selection neighbors are non-blocking.
                    if (isFullyEnclosed(level, minX + x, minY + y, minZ + z,
                            minX, minY, minZ, maxX, maxY, maxZ, neighbor)) continue;
                    pose.pushPose();
                    pose.translate(x, y, z);
                    brd.renderSingleBlock(state, pose, bs, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                    pose.popPose();
                    rendered++;
                }
            }
        }

        bs.endBatch();
        pose.popPose();
        gfx.disableScissor();
    }

    private static boolean isFullyEnclosed(ClientLevel level, int wx, int wy, int wz,
                                           int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                           BlockPos.MutableBlockPos scratch) {
        return neighborOccludes(level, wx - 1, wy, wz, minX, minY, minZ, maxX, maxY, maxZ, scratch)
            && neighborOccludes(level, wx + 1, wy, wz, minX, minY, minZ, maxX, maxY, maxZ, scratch)
            && neighborOccludes(level, wx, wy - 1, wz, minX, minY, minZ, maxX, maxY, maxZ, scratch)
            && neighborOccludes(level, wx, wy + 1, wz, minX, minY, minZ, maxX, maxY, maxZ, scratch)
            && neighborOccludes(level, wx, wy, wz - 1, minX, minY, minZ, maxX, maxY, maxZ, scratch)
            && neighborOccludes(level, wx, wy, wz + 1, minX, minY, minZ, maxX, maxY, maxZ, scratch);
    }

    private static boolean neighborOccludes(ClientLevel level, int nx, int ny, int nz,
                                            int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                            BlockPos.MutableBlockPos scratch) {
        // Anything outside the selection AABB counts as non-blocking so the outer shell
        // always renders, even if the world block next to it happens to be solid.
        if (nx < minX || nx > maxX || ny < minY || ny > maxY || nz < minZ || nz > maxZ) return false;
        scratch.set(nx, ny, nz);
        return level.getBlockState(scratch).canOcclude();
    }

    @Override
    protected void renderLabels(GuiGraphics gfx, int mx, int my) {
        gfx.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 0xFFE0E0E4, false);

        int tx = SIDE_X + 5;
        int ty = SIDE_Y + 6;
        if (menu.hasSelection()) {
            BlockPos s = menu.getSelectionStart();
            BlockPos e = menu.getSelectionEnd();
            int dx = Math.abs(e.getX() - s.getX()) + 1;
            int dy = Math.abs(e.getY() - s.getY()) + 1;
            int dz = Math.abs(e.getZ() - s.getZ()) + 1;
            gfx.drawString(this.font, Component.literal("SIZE"), tx, ty, 0xFFFF6A78, false);
            gfx.drawString(this.font, dx + "x" + dy + "x" + dz, tx, ty + 10, 0xFFE0E0E4, false);

            gfx.drawString(this.font, Component.literal("A"), tx, ty + 26, 0xFFFF6A78, false);
            gfx.drawString(this.font, s.getX() + "," + s.getY() + "," + s.getZ(),
                    tx, ty + 36, 0xFFB0B0B8, false);

            gfx.drawString(this.font, Component.literal("B"), tx, ty + 50, 0xFFFF6A78, false);
            gfx.drawString(this.font, e.getX() + "," + e.getY() + "," + e.getZ(),
                    tx, ty + 60, 0xFFB0B0B8, false);
        } else {
            gfx.drawString(this.font, Component.literal("NO"), tx, ty, 0xFFFF4050, false);
            gfx.drawString(this.font, Component.literal("SELECTION"), tx, ty + 10, 0xFFFF4050, false);
        }
    }

    // Buttons

    private static abstract class FuturisticButton extends AbstractButton {
        FuturisticButton(int x, int y, int w, int h, Component msg) {
            super(x, y, w, h, msg);
        }

        @Override
        public void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
            int x1 = getX(), y1 = getY(), x2 = getX() + width, y2 = getY() + height;
            boolean hovered = isHoveredOrFocused();
            int borderColor = !active ? 0xFF3A3A40 : (hovered ? 0xFFFF7A88 : 0xFFFF3040);
            int fillTop     = !active ? 0xFF1A1A1E : (hovered ? 0xFF3A0812 : 0xFF20080C);
            int fillBottom  = !active ? 0xFF141416 : (hovered ? 0xFF52101C : 0xFF32101C);
            int bracketColor = !active ? 0xFF6A6A70 : 0xFFFFC0C6;

            gfx.fillGradient(x1, y1, x2, y2, fillTop, fillBottom);
            gfx.fill(x1, y1, x2, y1 + 1, borderColor);
            gfx.fill(x1, y2 - 1, x2, y2, borderColor);
            gfx.fill(x1, y1, x1 + 1, y2, borderColor);
            gfx.fill(x2 - 1, y1, x2, y2, borderColor);
            // HUD-style corner brackets.
            gfx.fill(x1, y1, x1 + 3, y1 + 1, bracketColor);
            gfx.fill(x1, y1, x1 + 1, y1 + 3, bracketColor);
            gfx.fill(x2 - 3, y1, x2, y1 + 1, bracketColor);
            gfx.fill(x2 - 1, y1, x2, y1 + 3, bracketColor);
            gfx.fill(x1, y2 - 1, x1 + 3, y2, bracketColor);
            gfx.fill(x1, y2 - 3, x1 + 1, y2, bracketColor);
            gfx.fill(x2 - 3, y2 - 1, x2, y2, bracketColor);
            gfx.fill(x2 - 1, y2 - 3, x2, y2, bracketColor);

            renderContent(gfx, hovered);
        }

        protected abstract void renderContent(GuiGraphics gfx, boolean hovered);

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            this.defaultButtonNarrationText(out);
        }
    }

    private static class FuturisticTextButton extends FuturisticButton {
        private final OnPress onPress;

        FuturisticTextButton(int x, int y, int w, int h, Component msg, OnPress onPress) {
            super(x, y, w, h, msg);
            this.onPress = onPress;
        }

        @Override
        public void onPress() {
            onPress.onPress(this);
        }

        @Override
        protected void renderContent(GuiGraphics gfx, boolean hovered) {
            var mc = Minecraft.getInstance();
            int color = !active ? 0xFF6A6A70 : (hovered ? 0xFFFFFFFF : 0xFFFFC0C6);
            int tx = getX() + width / 2 - mc.font.width(getMessage()) / 2;
            int ty = getY() + (height - 8) / 2;
            gfx.drawString(mc.font, getMessage(), tx, ty, color, false);
        }
    }

    private static class BoundingBoxIconButton extends FuturisticButton {
        private final OnPress onPress;

        BoundingBoxIconButton(int x, int y, int w, int h, OnPress onPress) {
            super(x, y, w, h, bbNarration());
            this.onPress = onPress;
        }

        @Override
        public void onPress() {
            onPress.onPress(this);
        }

        @Override
        protected void renderContent(GuiGraphics gfx, boolean hovered) {
            boolean on = ClientSettings.showSelectionBox();
            int color = !active ? 0xFF6A6A70 : (on ? 0xFFFF7A88 : (hovered ? 0xFFFFFFFF : 0xFFFFC0C6));
            int cx = getX() + width / 2;
            int cy = getY() + height / 2;
            // Isometric wire cube: back square offset up-right, front square offset down-left,
            // corner dots joining them.
            int off = 2;
            int s = 4;
            int bx1 = cx - s + off, by1 = cy - s - off;
            int bx2 = cx + s + off, by2 = cy + s - off;
            drawRectOutline(gfx, bx1, by1, bx2, by2, color);
            int fx1 = cx - s - off, fy1 = cy - s + off;
            int fx2 = cx + s - off, fy2 = cy + s + off;
            drawRectOutline(gfx, fx1, fy1, fx2, fy2, color);
            gfx.fill(bx1, by1, bx1 + 1, by1 + 1, color);
            gfx.fill(fx1, fy1, fx1 + 1, fy1 + 1, color);
            gfx.fill(bx2 - 1, by2 - 1, bx2, by2, color);
            gfx.fill(fx2 - 1, fy2 - 1, fx2, fy2, color);
        }

        private static void drawRectOutline(GuiGraphics gfx, int x1, int y1, int x2, int y2, int color) {
            gfx.fill(x1, y1, x2, y1 + 1, color);
            gfx.fill(x1, y2 - 1, x2, y2, color);
            gfx.fill(x1, y1, x1 + 1, y2, color);
            gfx.fill(x2 - 1, y1, x2, y2, color);
        }
    }

    @FunctionalInterface
    private interface OnPress {
        void onPress(FuturisticButton button);
    }
}
