package com.mlh.create_assembly_animation.client.gui;

import com.mlh.create_assembly_animation.AAConfig;
import com.mlh.create_assembly_animation.CreateAssemblyAnimation;
import com.mlh.create_assembly_animation.client.AnimationStyle;
import com.mlh.create_assembly_animation.client.Phase;
import com.mlh.create_assembly_animation.client.StyleSource;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static com.mlh.create_assembly_animation.client.gui.AAConfigScreen.label;

final class StyleBrowser extends AbstractWidget {

    private static final ResourceLocation GEAR = CreateAssemblyAnimation.asResource("textures/gui/gear.png");

    private static final int MIN_TILE_WIDTH = 150;
    private static final int TILE_GAP = 6;
    private static final int TILE_NAME = 16;
    private static final int ROW_HEIGHT = 40;
    private static final int ROW_GAP = 3;
    private static final int THUMB_WIDTH = 64;
    private static final int HEADER_HEIGHT = 20;
    private static final int GROUP_GAP = 8;
    private static final int NOTICE_HEIGHT = 16;
    private static final int GEAR_SIZE = 14;
    private static final int SCROLLBAR = 6;
    private static final int SCROLL_STEP = 24;
    private static final int POPUP_WIDTH = 220;
    private static final int POPUP_PADDING = 4;

    private final Phase phase;
    private final Set<String> collapsed;
    private final Consumer<AnimationStyle> onSelect;
    private final Consumer<AnimationStyle> onOpenSettings;
    private final List<Entry> entries = new ArrayList<>();

    private AAConfig.StyleView view = AAConfig.StyleView.TILES;
    private String filter = "";
    private double scroll;
    private int contentHeight;
    private boolean draggingScrollbar;

    @Nullable
    private List<FormattedCharSequence> tooltip;
    @Nullable
    private AnimationStyle popup;

    StyleBrowser(final int x, final int y, final int width, final int height, final Phase phase,
                 final Set<String> collapsed, final Consumer<AnimationStyle> onSelect,
                 final Consumer<AnimationStyle> onOpenSettings) {
        super(x, y, width, height, CommonComponents.EMPTY);
        this.phase = phase;
        this.collapsed = collapsed;
        this.onSelect = onSelect;
        this.onOpenSettings = onOpenSettings;
    }

    void setView(final AAConfig.StyleView view) {
        this.view = view;
        this.layout();
    }

    void setFilter(final String filter) {
        this.filter = filter.trim().toLowerCase(Locale.ROOT);
        this.scroll = 0;
        this.layout();
    }

    double scroll() {
        return this.scroll;
    }

    void setScroll(final double scroll) {
        this.scroll = Mth.clamp(scroll, 0, this.maxScroll());
    }

    void revealSelected() {
        for (final Entry entry : this.entries) {
            if (entry instanceof final StyleEntry style && style.style == this.phase.configuredStyle()) {
                if (entry.y < this.scroll)
                    this.setScroll(entry.y - HEADER_HEIGHT);
                else if (entry.y + entry.height > this.scroll + this.height)
                    this.setScroll(entry.y + entry.height - this.height + 4);
                return;
            }
        }
    }

    @Nullable
    List<FormattedCharSequence> tooltip() {
        return this.tooltip;
    }

    private int innerWidth() {
        return this.width - SCROLLBAR - 2;
    }

    private int maxScroll() {
        return Math.max(0, this.contentHeight - this.height);
    }

    private boolean matches(final AnimationStyle style) {
        if (this.filter.isEmpty())
            return true;
        return contains(style.displayName().getString(), this.filter)
                || contains(style.description().getString(), this.filter)
                || contains(style.source().name().getString(), this.filter)
                || contains(style.id().toString(), this.filter);
    }

    private static boolean contains(final String text, final String part) {
        return text.toLowerCase(Locale.ROOT).contains(part);
    }

    private void layout() {
        this.entries.clear();
        final int width = this.innerWidth();
        int y = 0;

        if (this.phase.configuredStyle() == null) {
            this.entries.add(new NoticeEntry(y, width, label("missing_style", this.phase.configuredStyleId(),
                    AnimationStyle.DEFAULT.displayName()).withStyle(ChatFormatting.GOLD)));
            y += NOTICE_HEIGHT + 4;
        }

        final Map<StyleSource, List<AnimationStyle>> groups = new LinkedHashMap<>();
        for (final AnimationStyle style : AnimationStyle.all())
            if (this.matches(style))
                groups.computeIfAbsent(style.source(), key -> new ArrayList<>()).add(style);

        if (groups.isEmpty()) {
            this.entries.add(new NoticeEntry(y, width, label("no_styles").withStyle(ChatFormatting.GRAY)));
            y += NOTICE_HEIGHT;
        }

        final int columns = Math.max(1, (width + TILE_GAP) / (MIN_TILE_WIDTH + TILE_GAP));
        final int tileWidth = (width - TILE_GAP * (columns - 1)) / columns;
        final int tileHeight = (tileWidth - 6) * 9 / 16 + TILE_NAME + 3;

        for (final Map.Entry<StyleSource, List<AnimationStyle>> group : groups.entrySet()) {
            final StyleSource source = group.getKey();
            final List<AnimationStyle> styles = group.getValue();
            final boolean open = !this.filter.isEmpty() || !this.collapsed.contains(source.id());
            this.entries.add(new HeaderEntry(y, width, source, styles, open));
            y += HEADER_HEIGHT + 4;
            if (!open) {
                y += GROUP_GAP - 4;
                continue;
            }

            if (this.view == AAConfig.StyleView.TILES) {
                for (int i = 0; i < styles.size(); i++) {
                    final int column = i % columns;
                    final int row = i / columns;
                    this.entries.add(new StyleEntry(column * (tileWidth + TILE_GAP), y + row * (tileHeight + TILE_GAP),
                            tileWidth, tileHeight, styles.get(i), true));
                }
                y += (styles.size() + columns - 1) / columns * (tileHeight + TILE_GAP) - TILE_GAP;
            } else {
                for (final AnimationStyle style : styles) {
                    this.entries.add(new StyleEntry(0, y, width, ROW_HEIGHT, style, false));
                    y += ROW_HEIGHT + ROW_GAP;
                }
                y -= ROW_GAP;
            }
            y += GROUP_GAP;
        }

        this.contentHeight = y;
        this.scroll = Mth.clamp(this.scroll, 0, this.maxScroll());
    }

    @Override
    public void setSize(final int width, final int height) {
        super.setSize(width, height);
        this.layout();
    }

    @Override
    protected void renderWidget(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        if (this.entries.isEmpty())
            this.layout();

        final int left = this.getX();
        final int top = this.getY();
        final int offset = top - (int) this.scroll;
        final boolean inside = this.isMouseOver(mouseX, mouseY);
        this.tooltip = null;
        this.popup = null;

        graphics.enableScissor(left, top, left + this.width, top + this.height);
        for (final Entry entry : this.entries) {
            final int y = offset + entry.y;
            if (y + entry.height < top || y > top + this.height)
                continue;
            final boolean hovered = inside && mouseX >= left + entry.x && mouseX < left + entry.x + entry.width
                    && mouseY >= y && mouseY < y + entry.height;
            entry.render(graphics, left + entry.x, y, hovered, mouseX, mouseY);
        }
        graphics.disableScissor();

        this.renderEdges(graphics);
        this.renderScrollbar(graphics);
    }

    private void renderEdges(final GuiGraphics graphics) {
        final int left = this.getX();
        final int right = left + this.innerWidth();
        if (this.scroll > 0)
            graphics.fillGradient(left, this.getY(), right, this.getY() + 6, 0x80000000, 0x00000000);
        if (this.scroll < this.maxScroll())
            graphics.fillGradient(left, this.getBottom() - 6, right, this.getBottom(), 0x00000000, 0x80000000);
    }

    private void renderScrollbar(final GuiGraphics graphics) {
        if (this.maxScroll() <= 0)
            return;
        final int x = this.getRight() - SCROLLBAR + 2;
        final int thumb = this.thumbHeight();
        final int thumbY = this.getY() + (int) (this.scroll / this.maxScroll() * (this.height - thumb));
        graphics.fill(x, this.getY(), x + SCROLLBAR - 2, this.getBottom(), 0x60000000);
        graphics.fill(x, thumbY, x + SCROLLBAR - 2, thumbY + thumb, this.draggingScrollbar ? 0xFFE0E0E0 : 0xFFA0A0A0);
    }

    void renderPopup(final GuiGraphics graphics, final int mouseX, final int mouseY, final int screenWidth,
                     final int screenHeight) {
        if (this.popup == null)
            return;

        final int width = Math.min(POPUP_WIDTH, screenWidth / 2);
        final int height = width * 9 / 16;

        int x = mouseX + 12;
        if (x + width + POPUP_PADDING > screenWidth)
            x = mouseX - 12 - width;
        final int y = Mth.clamp(mouseY - 12, POPUP_PADDING + 2, screenHeight - height - POPUP_PADDING - 2);

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        graphics.fill(x - POPUP_PADDING, y - POPUP_PADDING, x + width + POPUP_PADDING, y + height + POPUP_PADDING,
                0xF0100010);
        graphics.renderOutline(x - POPUP_PADDING, y - POPUP_PADDING, width + POPUP_PADDING * 2,
                height + POPUP_PADDING * 2, AAConfigScreen.SELECTED);
        StylePreview.render(graphics, this.popup, this.phase, x, y, width, height, false);
        graphics.pose().popPose();
    }

    private int thumbHeight() {
        return Mth.clamp(this.height * this.height / Math.max(1, this.contentHeight), 16, this.height);
    }

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    private static FormattedCharSequence ellipsize(final Component text, final int width) {
        final Font font = font();
        if (font.width(text) <= width)
            return text.getVisualOrderText();
        final FormattedText cut = font.substrByWidth(text, width - font.width("…"));
        return Language.getInstance().getVisualOrder(FormattedText.composite(cut, FormattedText.of("…", text.getStyle())));
    }

    private static void gear(final GuiGraphics graphics, final int x, final int y, final boolean hovered) {
        graphics.fill(x, y, x + GEAR_SIZE, y + GEAR_SIZE, hovered ? 0xE0404040 : 0xA0000000);
        graphics.renderOutline(x, y, GEAR_SIZE, GEAR_SIZE, hovered ? 0xFFFFFFFF : 0xFF707070);
        final float brightness = hovered ? 1.0f : 0.8f;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(brightness, brightness, brightness, 1.0f);
        graphics.blit(GEAR, x + 1, y + 1, GEAR_SIZE - 2, GEAR_SIZE - 2, 0f, 0f, 16, 16, 16, 16);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
    }

    private static void chevron(final GuiGraphics graphics, final int x, final int centreY, final boolean open,
                                final int color) {
        for (int i = 0; i < 3; i++) {
            if (open)
                graphics.fill(x + i, centreY - 1 + i, x + 5 - i, centreY + i, color);
            else
                graphics.fill(x + 1 + i, centreY - 2 + i, x + 2 + i, centreY + 3 - i, color);
        }
    }

    private static boolean within(final double mouseX, final double mouseY, final int x, final int y, final int size) {
        return mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size;
    }

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (!this.active || !this.visible || !this.isMouseOver(mouseX, mouseY))
            return false;

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && this.maxScroll() > 0 && mouseX >= this.getRight() - SCROLLBAR) {
            this.draggingScrollbar = true;
            this.dragScrollbar(mouseY);
            return true;
        }

        final int offset = this.getY() - (int) this.scroll;
        for (final Entry entry : this.entries) {
            final int x = this.getX() + entry.x;
            final int y = offset + entry.y;
            if (mouseX >= x && mouseX < x + entry.width && mouseY >= y && mouseY < y + entry.height)
                return entry.click(x, y, mouseX, mouseY, button);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        this.draggingScrollbar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(final double mouseX, final double mouseY, final int button, final double dragX,
                                final double dragY) {
        if (!this.draggingScrollbar)
            return false;
        this.dragScrollbar(mouseY);
        return true;
    }

    private void dragScrollbar(final double mouseY) {
        final int track = this.height - this.thumbHeight();
        if (track > 0)
            this.setScroll((mouseY - this.getY() - this.thumbHeight() / 2.0) / track * this.maxScroll());
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY) {
        if (!this.isMouseOver(mouseX, mouseY) || this.maxScroll() <= 0)
            return false;
        this.setScroll(this.scroll - scrollY * SCROLL_STEP);
        return true;
    }

    private static void playClick() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    @Override
    protected void updateWidgetNarration(final NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, label("styles"));
    }

    private abstract static class Entry {

        final int x;
        final int y;
        final int width;
        final int height;

        Entry(final int x, final int y, final int width, final int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        abstract void render(GuiGraphics graphics, int x, int y, boolean hovered, int mouseX, int mouseY);

        boolean click(final int x, final int y, final double mouseX, final double mouseY, final int button) {
            return true;
        }
    }

    private static final class NoticeEntry extends Entry {

        private final Component text;

        NoticeEntry(final int y, final int width, final Component text) {
            super(0, y, width, NOTICE_HEIGHT);
            this.text = text;
        }

        @Override
        void render(final GuiGraphics graphics, final int x, final int y, final boolean hovered, final int mouseX,
                    final int mouseY) {
            graphics.fill(x, y, x + this.width, y + this.height, 0x60000000);
            graphics.drawCenteredString(font(), ellipsize(this.text, this.width - 8), x + this.width / 2, y + 4, 0xFFFFFFFF);
        }
    }

    private final class HeaderEntry extends Entry {

        private final StyleSource source;
        private final List<AnimationStyle> styles;
        private final boolean open;

        HeaderEntry(final int y, final int width, final StyleSource source, final List<AnimationStyle> styles,
                    final boolean open) {
            super(0, y, width, HEADER_HEIGHT);
            this.source = source;
            this.styles = styles;
            this.open = open;
        }

        @Override
        void render(final GuiGraphics graphics, final int x, final int y, final boolean hovered, final int mouseX,
                    final int mouseY) {
            final Font font = font();
            if (hovered)
                graphics.fill(x, y, x + this.width, y + this.height, 0x30FFFFFF);
            graphics.fill(x, y + this.height - 1, x + this.width, y + this.height, 0x50FFFFFF);

            final int textY = y + (this.height - 8) / 2;
            chevron(graphics, x + 5, y + this.height / 2, this.open, hovered ? 0xFFFFFFFF : 0xFFA0A0A0);
            this.source.icon().render(graphics, x + 13, y + (this.height - StyleSource.Icon.SIZE) / 2);

            final AnimationStyle selected = StyleBrowser.this.phase.configuredStyle();
            final boolean holdsSelected = selected != null && this.styles.contains(selected);
            final Component count = Component.literal(String.valueOf(this.styles.size()));
            final Component chosen = holdsSelected && !this.open
                    ? Component.literal("✔ ").append(selected.displayName()) : null;

            int right = x + this.width - 4 - font.width(count);
            graphics.drawString(font, count, right, textY, 0xFF808080);
            if (chosen != null) {
                right -= 8 + font.width(chosen);
                graphics.drawString(font, chosen, right, textY, AAConfigScreen.SELECTED);
            }

            final Component author = this.source.author();
            final Component title = author == null ? this.source.name() : this.source.name().copy()
                    .append(Component.literal("  ").append(author).withStyle(ChatFormatting.DARK_GRAY));
            graphics.drawString(font, ellipsize(title, right - (x + 34) - 6), x + 34, textY, 0xFFFFFFFF);
        }

        @Override
        boolean click(final int x, final int y, final double mouseX, final double mouseY, final int button) {
            if (!StyleBrowser.this.filter.isEmpty())
                return true;
            if (!StyleBrowser.this.collapsed.remove(this.source.id()))
                StyleBrowser.this.collapsed.add(this.source.id());
            playClick();
            StyleBrowser.this.layout();
            return true;
        }
    }

    private final class StyleEntry extends Entry {

        private final AnimationStyle style;
        private final boolean tile;

        StyleEntry(final int x, final int y, final int width, final int height, final AnimationStyle style,
                   final boolean tile) {
            super(x, y, width, height);
            this.style = style;
            this.tile = tile;
        }

        private int gearX(final int x) {
            return this.tile ? x + this.width - GEAR_SIZE - 6 : x + this.width - GEAR_SIZE - 8;
        }

        private int gearY(final int y) {
            return this.tile ? y + 6 : y + (this.height - GEAR_SIZE) / 2;
        }

        @Override
        void render(final GuiGraphics graphics, final int x, final int y, final boolean hovered, final int mouseX,
                    final int mouseY) {
            final Phase phase = StyleBrowser.this.phase;
            final boolean selected = phase.configuredStyle() == this.style;
            final Font font = font();

            graphics.fill(x, y, x + this.width, y + this.height, selected ? 0xE0182028 : hovered ? 0xC0202020 : 0xB0101010);
            final int border = selected ? AAConfigScreen.SELECTED : hovered ? 0xFFA0A0A0 : 0xFF3A3A3A;
            graphics.renderOutline(x, y, this.width, this.height, border);
            if (selected)
                graphics.renderOutline(x + 1, y + 1, this.width - 2, this.height - 2, border);

            final int previewX = x + 3;
            final int previewY = y + 3;
            final int previewWidth = this.tile ? this.width - 6 : THUMB_WIDTH;
            final int previewHeight = this.tile ? this.height - 3 - TILE_NAME : this.height - 6;
            StylePreview.render(graphics, this.style, phase, previewX, previewY, previewWidth, previewHeight, true);
            if (!phase.enabled())
                graphics.fill(previewX, previewY, previewX + previewWidth, previewY + previewHeight, 0x90000000);

            final Component name = selected ? Component.literal("✔ ").append(this.style.displayName())
                    : this.style.displayName();
            final int nameColor = selected ? AAConfigScreen.SELECTED : 0xFFE0E0E0;
            final int gearX = this.gearX(x);
            final int gearY = this.gearY(y);

            if (this.tile) {
                final FormattedCharSequence line = ellipsize(name, this.width - 8);
                graphics.drawString(font, line, x + (this.width - font.width(line)) / 2,
                        y + this.height - TILE_NAME + 4, nameColor);
            } else {
                final int textX = previewX + previewWidth + 8;
                final int textWidth = gearX - 8 - textX;
                graphics.drawString(font, ellipsize(name, textWidth), textX, y + 6, nameColor);
                final List<FormattedCharSequence> lines = font.split(this.style.description(), textWidth - font.width("…"));
                for (int i = 0; i < Math.min(2, lines.size()); i++) {
                    final FormattedCharSequence line = i == 1 && lines.size() > 2
                            ? FormattedCharSequence.composite(lines.get(i), Component.literal("…").getVisualOrderText())
                            : lines.get(i);
                    graphics.drawString(font, line, textX, y + 18 + i * 10, 0xFF909090);
                }
            }

            final boolean overGear = hovered && within(mouseX, mouseY, gearX, gearY, GEAR_SIZE);
            if (hovered || selected || !this.tile)
                gear(graphics, gearX, gearY, overGear);

            if (hovered && overGear)
                StyleBrowser.this.tooltip = List.of(label("open_settings").getVisualOrderText());
            else if (hovered && this.tile)
                StyleBrowser.this.tooltip = this.tooltipLines();
            else if (hovered)
                StyleBrowser.this.popup = this.style;
        }

        private List<FormattedCharSequence> tooltipLines() {
            final Font font = font();
            final List<FormattedCharSequence> lines = new ArrayList<>();
            lines.add(this.style.displayName().getVisualOrderText());
            lines.add(this.style.source().name().copy().withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
            lines.addAll(font.split(this.style.description().copy().withStyle(ChatFormatting.GRAY), 220));
            return lines;
        }

        @Override
        boolean click(final int x, final int y, final double mouseX, final double mouseY, final int button) {
            final boolean overGear = within(mouseX, mouseY, this.gearX(x), this.gearY(y), GEAR_SIZE);
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT || (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && overGear)) {
                playClick();
                StyleBrowser.this.onOpenSettings.accept(this.style);
            } else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                playClick();
                StyleBrowser.this.onSelect.accept(this.style);
                StyleBrowser.this.layout();
            }
            return true;
        }
    }
}
