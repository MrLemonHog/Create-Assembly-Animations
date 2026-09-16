package com.mlh.create_assembly_animation.client.gui;

import com.mlh.create_assembly_animation.AAConfig;
import com.mlh.create_assembly_animation.CreateAssemblyAnimation;
import com.mlh.create_assembly_animation.client.AnimationStyle;
import com.mlh.create_assembly_animation.client.Phase;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

final class StyleTile extends AbstractWidget {

    private static final ResourceLocation GEAR = CreateAssemblyAnimation.asResource("textures/gui/gear.png");
    private static final int NAME_HEIGHT = 20;
    private static final int GEAR_SIZE = 16;
    private static final int GEAR_MARGIN = 7;

    private final AnimationStyle style;
    private final Phase phase;
    private final Runnable onSelect;
    private final Runnable onOpenSettings;
    private final Tooltip description;
    private final Tooltip settingsHint;

    StyleTile(final int x, final int y, final int width, final int height, final AnimationStyle style, final Phase phase,
              final Runnable onSelect, final Runnable onOpenSettings) {
        super(x, y, width, height, style.displayName());
        this.style = style;
        this.phase = phase;
        this.onSelect = onSelect;
        this.onOpenSettings = onOpenSettings;
        this.description = Tooltip.create(style.description());
        this.settingsHint = Tooltip.create(AAConfigScreen.label("open_settings"));
        this.setTooltip(this.description);
    }

    private int gearX() {
        return this.getX() + this.width - GEAR_SIZE - GEAR_MARGIN;
    }

    private int gearY() {
        return this.getY() + GEAR_MARGIN;
    }

    private boolean overGear(final double mouseX, final double mouseY) {
        return mouseX >= this.gearX() && mouseX < this.gearX() + GEAR_SIZE
                && mouseY >= this.gearY() && mouseY < this.gearY() + GEAR_SIZE;
    }

    private int accent() {
        return switch (this.style) {
            case DIAGRAM -> 0xFFF2D38A;
            case SCANNER -> 0xFF000000 | AAConfig.SCANNER_LASER_COLOR.get().rgb();
        };
    }

    @Override
    protected void renderWidget(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        final int x = this.getX();
        final int y = this.getY();
        final boolean selected = this.phase.style() == this.style;
        final int accent = this.accent();

        graphics.fill(x, y, x + this.width, y + this.height, selected ? 0xE0182028 : 0xB0101010);
        final int border = selected ? accent : this.isHovered() ? 0xFFA0A0A0 : 0xFF3A3A3A;
        graphics.renderOutline(x, y, this.width, this.height, border);
        if (selected)
            graphics.renderOutline(x + 1, y + 1, this.width - 2, this.height - 2, border);

        final int previewX = x + 4;
        final int previewY = y + 4;
        final int previewWidth = this.width - 8;
        final int previewHeight = this.height - 8 - NAME_HEIGHT;
        StylePreview.render(graphics, this.style, this.phase, previewX, previewY, previewWidth, previewHeight);
        if (!this.phase.enabled())
            graphics.fill(previewX, previewY, previewX + previewWidth, previewY + previewHeight, 0x90000000);

        final Font font = Minecraft.getInstance().font;
        final Component name = selected ? Component.literal("✔ ").append(this.style.displayName()) : this.style.displayName();
        graphics.drawCenteredString(font, name, x + this.width / 2, y + this.height - NAME_HEIGHT + 5,
                selected ? accent : 0xFFC8C8C8);

        final boolean gearHovered = this.isHovered() && this.overGear(mouseX, mouseY);
        graphics.fill(this.gearX(), this.gearY(), this.gearX() + GEAR_SIZE, this.gearY() + GEAR_SIZE,
                gearHovered ? 0xE0404040 : 0xA0000000);
        graphics.renderOutline(this.gearX(), this.gearY(), GEAR_SIZE, GEAR_SIZE, gearHovered ? 0xFFFFFFFF : 0xFF808080);
        this.gear(graphics, gearHovered ? 1.0f : 0.82f);

        this.setTooltip(gearHovered ? this.settingsHint : this.description);
    }

    private void gear(final GuiGraphics graphics, final float brightness) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(brightness, brightness, brightness, 1.0f);
        graphics.blit(GEAR, this.gearX(), this.gearY(), GEAR_SIZE, GEAR_SIZE, 0f, 0f, GEAR_SIZE, GEAR_SIZE,
                GEAR_SIZE, GEAR_SIZE);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
    }

    @Override
    public void onClick(final double mouseX, final double mouseY) {
        if (this.overGear(mouseX, mouseY))
            this.onOpenSettings.run();
        else
            this.onSelect.run();
    }

    @Override
    protected void updateWidgetNarration(final NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
