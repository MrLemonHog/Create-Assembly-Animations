package com.mlh.create_assembly_animation.client.gui;

import com.mlh.create_assembly_animation.AAConfig;
import com.mlh.create_assembly_animation.client.AnimationStyle;
import com.mlh.create_assembly_animation.client.Phase;
import com.mlh.create_assembly_animation.client.StyleOption;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

import static com.mlh.create_assembly_animation.client.gui.AAConfigScreen.label;

final class StyleSettingsScreen extends Screen {

    private static final int COLUMN = 150;
    private static final int GAP = 8;
    private static final int ROW = 24;
    private static final int PREVIEW_Y = 34;

    private final Screen parent;
    private final AnimationStyle style;
    private Phase phase;

    private int previewX;
    private int previewY;
    private int previewWidth;
    private int previewHeight;

    StyleSettingsScreen(final Screen parent, final AnimationStyle style, final Phase phase) {
        super(label("style_settings", style.displayName()));
        this.parent = parent;
        this.style = style;
        this.phase = phase;
    }

    @Override
    protected void init() {
        final int centre = this.width / 2;
        final int leftColumn = centre - COLUMN - GAP / 2;
        final int rightColumn = centre + GAP / 2;
        final List<AbstractWidget> options = this.options();

        this.previewWidth = Math.min(COLUMN * 2 + GAP, this.width - 40);
        this.previewX = centre - this.previewWidth / 2;
        this.previewY = PREVIEW_Y;
        final int optionsHeight = ROW + (options.size() + 1) / 2 * ROW;
        this.previewHeight = Mth.clamp(this.height - this.previewY - optionsHeight - 44, 40, 160);

        int top = this.previewY + this.previewHeight + 6;
        this.addRenderableWidget(CycleButton.<Phase>builder(Phase::displayName)
                .withValues(Phase.values())
                .withInitialValue(this.phase)
                .create(leftColumn, top, COLUMN, 20, label("preview"), (button, value) -> {
                    this.phase = value;
                    this.rebuildWidgets();
                }));

        final boolean used = this.phase.configuredStyle() == this.style;
        final Button use = Button.builder(used
                        ? Component.literal("✔ ").append(label("used_for", label("tab." + this.phase.id())))
                        : label("use_for", label("tab." + this.phase.id())), button -> {
                    this.phase.select(this.style);
                    this.rebuildWidgets();
                })
                .bounds(rightColumn, top, COLUMN, 20)
                .build();
        use.active = !used;
        this.addRenderableWidget(use);

        top += ROW + 4;
        for (int i = 0; i < options.size(); i++) {
            final AbstractWidget option = options.get(i);
            option.setPosition(i % 2 == 0 ? leftColumn : rightColumn, top + i / 2 * ROW);
            this.addRenderableWidget(option);
        }

        this.addRenderableWidget(Button.builder(label("reset"), button -> this.reset())
                .bounds(leftColumn, this.height - 28, COLUMN, 20)
                .build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(rightColumn, this.height - 28, COLUMN, 20)
                .build());
    }

    private List<AbstractWidget> options() {
        final List<AbstractWidget> widgets = new ArrayList<>();
        for (final StyleOption option : this.style.options()) {
            final AbstractWidget widget = switch (option) {
                case StyleOption.Toggle toggle -> CycleButton.onOffBuilder(toggle.value().get())
                        .create(0, 0, COLUMN, 20, toggle.caption(), (button, value) -> toggle.value().set(value));
                case StyleOption.Choice<?> choice -> choice(choice);
                case StyleOption.Slider slider -> new SettingSlider(slider.caption(), slider.value(), slider.min(),
                        slider.max(), slider.step(), slider.format());
            };
            widget.setTooltip(Tooltip.create(option.tooltip()));
            widgets.add(widget);
        }
        return widgets;
    }

    private static <E extends Enum<E>> CycleButton<E> choice(final StyleOption.Choice<E> choice) {
        return CycleButton.builder(choice.format())
                .withValues(choice.values())
                .withInitialValue(choice.value().get())
                .create(0, 0, COLUMN, 20, choice.caption(), (button, value) -> choice.value().set(value));
    }

    private void reset() {
        for (final StyleOption option : this.style.options())
            reset(option.value());
        this.rebuildWidgets();
    }

    private static <T> void reset(final ModConfigSpec.ConfigValue<T> value) {
        value.set(value.getDefault());
    }

    @Override
    public void render(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        graphics.drawCenteredString(this.font, this.style.source().name().copy().withStyle(ChatFormatting.GRAY),
                this.width / 2, 19, 0xFFFFFF);
        graphics.renderOutline(this.previewX - 1, this.previewY - 1, this.previewWidth + 2, this.previewHeight + 2,
                0xFF3A3A3A);
        StylePreview.render(graphics, this.style, this.phase, this.previewX, this.previewY, this.previewWidth,
                this.previewHeight, false);
    }

    @Override
    public void onClose() {
        AAConfig.SPEC.save();
        this.minecraft.setScreen(this.parent);
    }

    private static final class SettingSlider extends AbstractSliderButton {

        private final Component caption;
        private final ModConfigSpec.DoubleValue setting;
        private final double min;
        private final double max;
        private final double step;
        private final DoubleFunction<String> format;

        private SettingSlider(final Component caption, final ModConfigSpec.DoubleValue setting, final double min,
                              final double max, final double step, final DoubleFunction<String> format) {
            super(0, 0, COLUMN, 20, caption, (setting.get() - min) / (max - min));
            this.caption = caption;
            this.setting = setting;
            this.min = min;
            this.max = max;
            this.step = step;
            this.format = format;
            this.updateMessage();
        }

        private double current() {
            final double value = this.min + this.value * (this.max - this.min);
            return Mth.clamp(this.step > 0 ? Math.round(value / this.step) * this.step : value, this.min, this.max);
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
