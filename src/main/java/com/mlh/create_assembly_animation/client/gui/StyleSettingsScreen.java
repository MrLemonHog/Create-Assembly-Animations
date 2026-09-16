package com.mlh.create_assembly_animation.client.gui;

import com.mlh.create_assembly_animation.AAConfig;
import com.mlh.create_assembly_animation.CreateAssemblyAnimation;
import com.mlh.create_assembly_animation.client.AnimationStyle;
import com.mlh.create_assembly_animation.client.Phase;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleFunction;

import static com.mlh.create_assembly_animation.client.gui.AAConfigScreen.label;
import static com.mlh.create_assembly_animation.client.gui.AAConfigScreen.toggle;

final class StyleSettingsScreen extends Screen {

    private static final int COLUMN = 150;
    private static final int GAP = 8;
    private static final int ROW = 24;

    private final Screen parent;
    private final AnimationStyle style;
    private final Phase phase;

    private int previewX;
    private int previewY;
    private int previewWidth;
    private int previewHeight;

    StyleSettingsScreen(final Screen parent, final AnimationStyle style, final Phase phase) {
        super(Component.translatable(CreateAssemblyAnimation.ID + ".configuration.style_settings", style.displayName()));
        this.parent = parent;
        this.style = style;
        this.phase = phase;
    }

    @Override
    protected void init() {
        final int centre = this.width / 2;
        final List<AbstractWidget> options = this.options();

        this.previewWidth = Math.min(COLUMN * 2 + GAP, this.width - 40);
        this.previewX = centre - this.previewWidth / 2;
        this.previewY = 30;
        final int optionsHeight = (options.size() + 1) / 2 * ROW;
        this.previewHeight = Mth.clamp(this.height - this.previewY - optionsHeight - 48, 40, 150);

        final int top = this.previewY + this.previewHeight + 8;
        for (int i = 0; i < options.size(); i++) {
            final AbstractWidget option = options.get(i);
            option.setPosition(i % 2 == 0 ? centre - COLUMN - GAP / 2 : centre + GAP / 2, top + i / 2 * ROW);
            this.addRenderableWidget(option);
        }

        this.addRenderableWidget(Button.builder(label("reset"), button -> this.reset())
                .bounds(centre - COLUMN - GAP / 2, this.height - 28, COLUMN, 20)
                .build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(centre + GAP / 2, this.height - 28, COLUMN, 20)
                .build());
    }

    private List<AbstractWidget> options() {
        final List<AbstractWidget> options = new ArrayList<>();
        final AAConfig.StyleSettings settings = this.style.settings();
        options.add(slider(settings.brightness(), "brightness", AAConfig.MIN_BRIGHTNESS, AAConfig.MAX_BRIGHTNESS,
                value -> Math.round(value * 100) + "%"));
        options.add(slider(settings.speed(), "speed", AAConfig.MIN_SPEED, AAConfig.MAX_SPEED,
                value -> String.format(Locale.ROOT, "%.2f×", value)));

        switch (this.style) {
            case DIAGRAM -> {
                options.add(toggle(0, 0, COLUMN, AAConfig.DIAGRAM_FORCE_ARROWS, "diagram.force_arrows"));
                options.add(toggle(0, 0, COLUMN, AAConfig.DIAGRAM_MEASUREMENTS, "diagram.measurements"));
                options.add(toggle(0, 0, COLUMN, AAConfig.DIAGRAM_ROTATION_GIZMO, "diagram.rotation_gizmo"));
            }
            case SCANNER -> {
                final CycleButton<AAConfig.LaserColor> color = CycleButton.<AAConfig.LaserColor>builder(
                                value -> value.displayName().copy().withStyle(format -> format.withColor(TextColor.fromRgb(value.rgb()))))
                        .withValues(List.of(AAConfig.LaserColor.values()))
                        .withInitialValue(AAConfig.SCANNER_LASER_COLOR.get())
                        .create(0, 0, COLUMN, 20, label("scanner.laser_color"),
                                (button, value) -> AAConfig.SCANNER_LASER_COLOR.set(value));
                color.setTooltip(Tooltip.create(label("scanner.laser_color.tooltip")));
                options.add(color);
                options.add(toggle(0, 0, COLUMN, AAConfig.SCANNER_HALO, "scanner.halo"));
            }
        }
        return options;
    }

    private static SettingSlider slider(final ModConfigSpec.DoubleValue setting, final String key, final double min,
                                        final double max, final DoubleFunction<String> format) {
        final SettingSlider slider = new SettingSlider(label(key), setting, min, max, format);
        slider.setTooltip(Tooltip.create(label(key + ".tooltip")));
        return slider;
    }

    private void reset() {
        final AAConfig.StyleSettings settings = this.style.settings();
        reset(settings.brightness());
        reset(settings.speed());
        switch (this.style) {
            case DIAGRAM -> {
                reset(AAConfig.DIAGRAM_FORCE_ARROWS);
                reset(AAConfig.DIAGRAM_MEASUREMENTS);
                reset(AAConfig.DIAGRAM_ROTATION_GIZMO);
            }
            case SCANNER -> {
                reset(AAConfig.SCANNER_LASER_COLOR);
                reset(AAConfig.SCANNER_HALO);
            }
        }
        this.rebuildWidgets();
    }

    private static <T> void reset(final ModConfigSpec.ConfigValue<T> value) {
        value.set(value.getDefault());
    }

    @Override
    public void render(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        graphics.renderOutline(this.previewX - 1, this.previewY - 1, this.previewWidth + 2, this.previewHeight + 2, 0xFF3A3A3A);
        StylePreview.render(graphics, this.style, this.phase, this.previewX, this.previewY, this.previewWidth, this.previewHeight);
    }

    @Override
    public void onClose() {
        AAConfig.SPEC.save();
        this.minecraft.setScreen(this.parent);
    }

    private static final class SettingSlider extends AbstractSliderButton {

        private static final double STEP = 0.05;

        private final Component caption;
        private final ModConfigSpec.DoubleValue setting;
        private final double min;
        private final double max;
        private final DoubleFunction<String> format;

        private SettingSlider(final Component caption, final ModConfigSpec.DoubleValue setting, final double min,
                              final double max, final DoubleFunction<String> format) {
            super(0, 0, COLUMN, 20, caption, (setting.get() - min) / (max - min));
            this.caption = caption;
            this.setting = setting;
            this.min = min;
            this.max = max;
            this.format = format;
            this.updateMessage();
        }

        private double current() {
            return Mth.clamp(Math.round((this.min + this.value * (this.max - this.min)) / STEP) * STEP, this.min, this.max);
        }

        @Override
        protected void updateMessage() {
            if (this.format == null)
                return;
            this.setMessage(CommonComponents.optionNameValue(this.caption, Component.literal(this.format.apply(this.current()))));
        }

        @Override
        protected void applyValue() {
            this.setting.set(this.current());
        }
    }
}
