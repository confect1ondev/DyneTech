package com.confect1on.dynetech.client.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import com.confect1on.dynetech.gene.Perk;
import com.confect1on.dynetech.gene.PerkCondition;
import com.confect1on.dynetech.gene.PerkEntry;
import com.confect1on.dynetech.gene.Perks;
import com.confect1on.dynetech.gene.SpeciesPool;
import com.confect1on.dynetech.gene.VialContents;
import com.confect1on.dynetech.gene.VialState;
import com.confect1on.dynetech.item.GeneVialItem;
import com.confect1on.dynetech.menu.GeneMicroscopeMenu;

import java.util.ArrayList;
import java.util.List;

/**
 * Paginated + scrollable microscope screen.
 *
 * <p>All body / title / subtitle text goes through {@link net.minecraft.client.gui.Font#split(Component, int)}
 * before rendering, so multi-line wrapping works uniformly and vanilla text styles survive the
 * wrap. Long perk names wrap onto a second line inside the panel instead of overflowing the
 * right edge.
 *
 * <p>Layout is sized so the nav buttons sit strictly above the inventory divider strip and never
 * clash with the player-inv label. All slot Y positions come from {@link GeneMicroscopeMenu}'s
 * constants so the drawn wells always line up with where items render.
 */
public class GeneMicroscopeScreen extends AbstractContainerScreen<GeneMicroscopeMenu> {

    // Vanilla palette.
    private static final int PANEL_LIGHT = 0xFFC6C6C6;
    private static final int PANEL_HIGHLIGHT = 0xFFFFFFFF;
    private static final int PANEL_SHADOW = 0xFF555555;
    private static final int SLOT_INNER = 0xFF8B8B8B;
    private static final int SLOT_BORDER = 0xFF373737;
    private static final int PAGE_BG = 0xFFEEEDE3;
    private static final int PAGE_BORDER = 0xFF666666;
    private static final int TEXT_DARK = 0x404040;
    private static final int TEXT_SUB = 0x707070;
    private static final int SCROLLBAR_TRACK = 0xFF888888;
    private static final int SCROLLBAR_THUMB = 0xFF505050;

    // Info panel geometry — local coords (inside the leftPos/topPos frame).
    private static final int INFO_X = 44;
    private static final int INFO_Y = 18;
    private static final int INFO_W_PADDING = 6;
    private static final int INFO_H = 78;
    private static final int INFO_TEXT_PAD = 5;
    private static final int LINE_H = 10;

    private static final int BUTTON_ROW_Y = INFO_Y + INFO_H + 2;   // 98 — sits above the 112 divider
    private static final int BUTTON_H = 12;

    private int page = 0;
    private int scrollOffset = 0;
    private List<Page> pages = List.of();
    private ItemStack lastSeen = ItemStack.EMPTY;

    private Button prevBtn;
    private Button nextBtn;

    public GeneMicroscopeScreen(GeneMicroscopeMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 210;
        this.inventoryLabelY = GeneMicroscopeMenu.INV_ROW0_Y - 12;
    }

    @Override
    protected void init() {
        super.init();
        int infoW = infoPanelWidth();
        int bx = leftPos + INFO_X;
        int by = topPos + BUTTON_ROW_Y;

        prevBtn = Button.builder(Component.literal("<"), b -> {
            if (page > 0) { page--; scrollOffset = 0; refreshButtons(); }
        }).bounds(bx, by, 14, BUTTON_H).build();

        nextBtn = Button.builder(Component.literal(">"), b -> {
            if (page < pages.size() - 1) { page++; scrollOffset = 0; refreshButtons(); }
        }).bounds(bx + infoW - 14, by, 14, BUTTON_H).build();

        addRenderableWidget(prevBtn);
        addRenderableWidget(nextBtn);
        refreshButtons();
    }

    private void refreshButtons() {
        if (prevBtn != null) prevBtn.active = page > 0;
        if (nextBtn != null) nextBtn.active = page < pages.size() - 1;
    }

    private int infoPanelWidth() {
        return imageWidth - INFO_X - INFO_W_PADDING;
    }

    private int textAreaWidth() {
        return infoPanelWidth() - INFO_TEXT_PAD * 2;
    }

    @Override
    public void render(GuiGraphics gfx, int mx, int my, float partial) {
        ItemStack current = menu.loadedVial();
        if (!ItemStack.matches(current, lastSeen)) {
            lastSeen = current.copy();
            pages = buildPages(current);
            if (page >= pages.size()) page = Math.max(0, pages.size() - 1);
            scrollOffset = 0;
            refreshButtons();
        }
        this.renderBackground(gfx, mx, my, partial);
        super.render(gfx, mx, my, partial);
        this.renderTooltip(gfx, mx, my);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int px = leftPos + INFO_X, py = topPos + INFO_Y;
        int pw = infoPanelWidth(), ph = INFO_H;
        if (mouseX >= px && mouseX <= px + pw && mouseY >= py && mouseY <= py + ph) {
            int step = deltaY > 0 ? -1 : 1;
            scrollOffset = Math.max(0, scrollOffset + step);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    protected void renderBg(GuiGraphics gfx, float partial, int mx, int my) {
        int x0 = leftPos, y0 = topPos, x1 = x0 + imageWidth, y1 = y0 + imageHeight;

        gfx.fill(x0, y0, x1, y1, PANEL_LIGHT);
        gfx.fill(x0, y0, x1, y0 + 1, PANEL_HIGHLIGHT);
        gfx.fill(x0, y0, x0 + 1, y1, PANEL_HIGHLIGHT);
        gfx.fill(x1 - 1, y0, x1, y1, PANEL_SHADOW);
        gfx.fill(x0, y1 - 1, x1, y1, PANEL_SHADOW);

        drawWell(gfx, leftPos + GeneMicroscopeMenu.SLOT_X, topPos + GeneMicroscopeMenu.SLOT_Y);

        int px = leftPos + INFO_X, py = topPos + INFO_Y;
        int pw = infoPanelWidth();
        gfx.fill(px - 1, py - 1, px + pw + 1, py + INFO_H + 1, PAGE_BORDER);
        gfx.fill(px, py, px + pw, py + INFO_H, PAGE_BG);

        int invTop = topPos + inventoryLabelY - 4;
        gfx.fill(x0 + 5, invTop, x1 - 5, invTop + 1, PANEL_SHADOW);

        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 9; c++)
                drawWell(gfx, leftPos + GeneMicroscopeMenu.INV_X0 + c * 18,
                        topPos + GeneMicroscopeMenu.INV_ROW0_Y + r * 18);
        for (int c = 0; c < 9; c++)
            drawWell(gfx, leftPos + GeneMicroscopeMenu.INV_X0 + c * 18,
                    topPos + GeneMicroscopeMenu.INV_HOTBAR_Y);
    }

    private static void drawWell(GuiGraphics gfx, int x, int y) {
        gfx.fill(x - 1, y - 1, x + 17, y + 17, SLOT_BORDER);
        gfx.fill(x, y, x + 16, y + 16, SLOT_INNER);
    }

    @Override
    protected void renderLabels(GuiGraphics gfx, int mx, int my) {
        gfx.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, TEXT_DARK, false);
        gfx.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, TEXT_DARK, false);

        if (pages.isEmpty()) {
            gfx.drawString(this.font, Component.translatable("dynetech.microscope.empty"),
                    INFO_X + INFO_TEXT_PAD, INFO_Y + INFO_TEXT_PAD, TEXT_SUB, false);
            return;
        }

        Page current = pages.get(page);
        int textX = INFO_X + INFO_TEXT_PAD;
        int textAreaW = textAreaWidth();

        // Wrap every section through the font splitter so styles are preserved and long titles
        // cascade onto multiple lines rather than overflowing.
        List<FormattedCharSequence> allLines = new ArrayList<>();
        allLines.addAll(this.font.split(current.title, textAreaW));
        if (current.subtitle != null) allLines.addAll(this.font.split(current.subtitle, textAreaW));
        allLines.add(FormattedCharSequence.EMPTY);
        allLines.addAll(this.font.split(current.body, textAreaW));

        int visibleLines = (INFO_H - INFO_TEXT_PAD * 2) / LINE_H;
        int maxOffset = Math.max(0, allLines.size() - visibleLines);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;

        int lineY = INFO_Y + INFO_TEXT_PAD;
        int end = Math.min(allLines.size(), scrollOffset + visibleLines);
        for (int i = scrollOffset; i < end; i++) {
            gfx.drawString(this.font, allLines.get(i), textX, lineY, TEXT_DARK, false);
            lineY += LINE_H;
        }

        // Right-edge scrollbar when the content doesn't fit.
        if (allLines.size() > visibleLines) {
            int trackX = INFO_X + infoPanelWidth() - 3;
            int trackTop = INFO_Y + INFO_TEXT_PAD;
            int trackBot = INFO_Y + INFO_H - INFO_TEXT_PAD;
            gfx.fill(trackX, trackTop, trackX + 2, trackBot, SCROLLBAR_TRACK);
            int trackH = trackBot - trackTop;
            int thumbH = Math.max(4, trackH * visibleLines / allLines.size());
            int thumbY = trackTop + (maxOffset > 0 ? (int) ((float) scrollOffset / maxOffset * (trackH - thumbH)) : 0);
            gfx.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, SCROLLBAR_THUMB);
        }

        String pageLabel = (page + 1) + " / " + pages.size();
        int textW = this.font.width(pageLabel);
        int cx = INFO_X + infoPanelWidth() / 2 - textW / 2;
        gfx.drawString(this.font, pageLabel, cx, BUTTON_ROW_Y + 2, TEXT_DARK, false);
    }

    // ==== Page assembly ====

    private static List<Page> buildPages(ItemStack stack) {
        if (stack.isEmpty()) return List.of();
        VialContents c = GeneVialItem.getContents(stack);
        List<Page> out = new ArrayList<>();

        switch (c.state()) {
            case EMPTY -> {}
            case RAW -> {
                if (c.isPlayerBlood()) {
                    String donor = c.donorName().orElse("?");
                    out.add(new Page(
                            Component.translatable("dynetech.microscope.donor_player", donor)
                                    .withStyle(ChatFormatting.DARK_RED),
                            Component.translatable("dynetech.microscope.snapshot_note").withStyle(ChatFormatting.DARK_GRAY),
                            Component.translatable("dynetech.microscope.snapshot_body")));
                    for (PerkEntry entry : c.playerSnapshot().orElse(List.of())) addPerkPage(entry, out);
                } else {
                    EntityType<?> type = c.donorType().map(BuiltInRegistries.ENTITY_TYPE::get).orElse(null);
                    if (type != null) {
                        out.add(new Page(
                                Component.translatable("dynetech.microscope.species",
                                        Component.translatable(type.getDescriptionId())).withStyle(ChatFormatting.DARK_GRAY),
                                Component.translatable("dynetech.microscope.pool_note").withStyle(ChatFormatting.DARK_GRAY),
                                Component.translatable("dynetech.microscope.pool_body")));
                        for (SpeciesPool.Weighted w : SpeciesPool.forType(type)) {
                            Perk perk = Perks.get(w.perkId());
                            if (perk != null) addPerkInfoPage(perk, out);
                        }
                    }
                }
            }
            case ISOLATED, SERUM -> {
                for (PerkEntry entry : c.perks()) addPerkPage(entry, out);
            }
        }
        return out;
    }

    private static void addPerkPage(PerkEntry entry, List<Page> out) {
        Perk perk = Perks.get(entry.perkId());
        if (perk == null) return;
        MutableComponent subtitle = Component.empty()
                .append(Component.translatable(entry.grade().langKey()).withStyle(entry.grade().formatting()))
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(kindTag(perk));
        entry.condition().ifPresent(cond -> {
            if (cond == PerkCondition.ALWAYS) return;
            subtitle.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            subtitle.append(Component.translatable(cond.langKey()).withStyle(ChatFormatting.DARK_GRAY));
        });
        out.add(new Page(perk.displayName().copy(), subtitle, perk.description()));

        entry.condition().ifPresent(cond -> {
            if (cond == PerkCondition.ALWAYS) return;
            out.add(new Page(
                    Component.translatable(cond.langKey()).withStyle(ChatFormatting.DARK_PURPLE),
                    Component.translatable("dynetech.microscope.tag_condition").withStyle(ChatFormatting.DARK_GRAY),
                    cond.description()));
        });
    }

    private static void addPerkInfoPage(Perk perk, List<Page> out) {
        out.add(new Page(perk.displayName().copy(), kindTag(perk), perk.description()));
    }

    private static MutableComponent kindTag(Perk perk) {
        String key = perk.isDefect() ? "dynetech.microscope.tag_defect" : "dynetech.microscope.tag_gene";
        return Component.translatable(key)
                .withStyle(perk.isDefect() ? ChatFormatting.RED : ChatFormatting.DARK_GREEN);
    }

    private record Page(Component title, Component subtitle, Component body) {}
}
