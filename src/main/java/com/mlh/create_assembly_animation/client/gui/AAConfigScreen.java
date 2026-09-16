package com.mlh.create_assembly_animation.client.gui;

import com.mlh.create_assembly_animation.AAConfig;
import com.mlh.create_assembly_animation.CreateAssemblyAnimation;
import com.mlh.create_assembly_animation.client.AnimationStyle;
import com.mlh.create_assembly_animation.client.Phase;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;

public final class AAConfigScreen extends Screen {

    private static final int TAB_WIDTH = 150;
    private static final int TAB_GAP = 4;
    private static final int TAB_Y = 26;
    private static final int TILE_GAP = 10;
    private static final int MAX_TILE_WIDTH = 190;
    private static final int TILE_TOP = 80;

    private static Phase tab = Phase.ASSEMBLY;

    @Nullable
    private final Screen parent;

    public AAConfigScreen(@Nullable final Screen parent) {
        super(label("title"));
        this.parent = parent;
    }

    static Component label(final String key) {
        return Component.translatable(CreateAssemblyAnimation.ID + ".configuration." + key);
    }

    static CycleButton<Boolean> toggle(final int x, final int y, final int width, final ModConfigSpec.BooleanValue setting,
                                       final String key) {
        final CycleButton<Boolean> button = CycleButton.onOffBuilder(setting.get())
                .create(x, y, width, 20, label(key), (cycle, value) -> setting.set(value));
        button.setTooltip(Tooltip.create(label(key + ".tooltip")));
        return button;
    }

    private static int tabX(final int centre, final Phase phase) {
        return centre - TAB_WIDTH - TAB_GAP / 2 + phase.ordinal() * (TAB_WIDTH + TAB_GAP);
    }

    @Override
    protected void init() {
        ShipImage.invalidate();
        final int centre = this.width / 2;

        for (final Phase phase : Phase.values()) {
            final Component name = phase == tab ? phase.displayName().copy().withStyle(ChatFormatting.YELLOW) : phase.displayName();
            this.addRenderableWidget(Button.builder(name, button -> {
                tab = phase;
                this.rebuildWidgets();
            }).bounds(tabX(centre, phase), TAB_Y, TAB_WIDTH, 20).build());
        }

        final Phase phase = tab;
        final CycleButton<Boolean> enabled = CycleButton.onOffBuilder(phase.enabled())
                .create(centre - 100, TAB_Y + 26, 200, 20, label("enabled"),
                        (button, value) -> phase.settings().enabled().set(value));
        enabled.setTooltip(Tooltip.create(label(phase.id() + ".enabled.tooltip")));
        this.addRenderableWidget(enabled);

        final AnimationStyle[] styles = AnimationStyle.values();
        final int tileWidth = Math.min(MAX_TILE_WIDTH, (this.width - 40 - TILE_GAP * (styles.length - 1)) / styles.length);
        final int tileHeight = Mth.clamp(this.height - TILE_TOP - 64, 60, 160);
        int x = centre - (tileWidth * styles.length + TILE_GAP * (styles.length - 1)) / 2;
        for (final AnimationStyle style : styles) {
            this.addRenderableWidget(new StyleTile(x, TILE_TOP, tileWidth, tileHeight, style, phase,
                    () -> phase.settings().style().set(style),
                    () -> this.minecraft.setScreen(new StyleSettingsScreen(this, style, phase))));
            x += tileWidth + TILE_GAP;
        }

        final int row = TILE_TOP + tileHeight + 8;
        this.addRenderableWidget(toggle(centre - 154, row, 150, AAConfig.PARTICLES, "particles"));
        this.addRenderableWidget(toggle(centre + 4, row, 150, AAConfig.SOUNDS, "sounds"));

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(centre - 100, this.height - 28, 200, 20)
                .build());
    }

    @Override
    public void render(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

        final int underline = tabX(this.width / 2, tab);
        graphics.fill(underline + 2, TAB_Y + 21, underline + TAB_WIDTH - 2, TAB_Y + 23, 0xFFFFD34D);
    }

    @Override
    public void onClose() {
        AAConfig.SPEC.save();
        ShipImage.invalidate();
        this.minecraft.setScreen(this.parent);
    }
}
