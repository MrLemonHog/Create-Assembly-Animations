package com.mlh.create_assembly_animation.client.gui;

import com.mlh.create_assembly_animation.AAConfig;
import com.mlh.create_assembly_animation.CreateAssemblyAnimation;
import com.mlh.create_assembly_animation.client.AnimationStyle;
import com.mlh.create_assembly_animation.client.Phase;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AAConfigScreen extends Screen {

    static final int SELECTED = 0xFFFFD34D;

    private static final int MAX_CONTENT_WIDTH = 640;
    private static final int MARGIN = 12;
    private static final int GAP = 4;
    private static final int TAB_Y = 22;
    private static final int TOOLBAR_Y = 48;
    private static final int BROWSER_Y = 74;
    private static final int FOOTER_HEIGHT = 32;

    private static Phase tab = Phase.ASSEMBLY;
    private static final Set<String> COLLAPSED = new HashSet<>();
    private static final Map<Phase, Double> SCROLL = new EnumMap<>(Phase.class);
    private static String filter = "";

    @Nullable
    private final Screen parent;
    @Nullable
    private StyleBrowser browser;
    private boolean revealed;

    public AAConfigScreen(@Nullable final Screen parent) {
        super(label("title"));
        this.parent = parent;
    }

    static MutableComponent label(final String key, final Object... args) {
        return Component.translatable(CreateAssemblyAnimation.ID + ".configuration." + key, args);
    }

    static CycleButton<Boolean> toggle(final int x, final int y, final int width, final ModConfigSpec.BooleanValue setting,
                                       final String key) {
        final CycleButton<Boolean> button = CycleButton.onOffBuilder(setting.get())
                .create(x, y, width, 20, label(key), (cycle, value) -> setting.set(value));
        button.setTooltip(Tooltip.create(label(key + ".tooltip")));
        return button;
    }

    private int contentWidth() {
        return Math.min(MAX_CONTENT_WIDTH, this.width - MARGIN * 2);
    }

    private int contentLeft() {
        return (this.width - this.contentWidth()) / 2;
    }

    private static Component tabLabel(final Phase phase) {
        final MutableComponent name = label("tab." + phase.id());
        final Component state = phase.enabled()
                ? Component.literal(" · ").append(phase.style().displayName()).withStyle(ChatFormatting.GRAY)
                : Component.literal(" · ").append(CommonComponents.OPTION_OFF).withStyle(ChatFormatting.RED);
        return (phase == tab ? name.withStyle(ChatFormatting.YELLOW) : name).append(state);
    }

    @Override
    protected void init() {
        StylePreview.invalidate();
        final int left = this.contentLeft();
        final int width = this.contentWidth();
        final int right = left + width;

        final int tabWidth = Math.min(180, (width - GAP) / 2);
        final int tabsLeft = this.width / 2 - tabWidth - GAP / 2;
        for (final Phase phase : Phase.values()) {
            final Button button = Button.builder(tabLabel(phase), pressed -> this.switchTab(phase))
                    .bounds(tabsLeft + phase.ordinal() * (tabWidth + GAP), TAB_Y, tabWidth, 20)
                    .build();
            button.setTooltip(Tooltip.create(label(phase.id() + ".enabled.tooltip")));
            this.addRenderableWidget(button);
        }

        final Phase phase = tab;
        final int sideWidth = Math.min(130, width / 4);
        final CycleButton<Boolean> enabled = CycleButton.onOffBuilder(phase.enabled())
                .create(left, TOOLBAR_Y, sideWidth, 20, label("enabled"), (button, value) -> {
                    phase.settings().enabled().set(value);
                    this.rebuildWidgets();
                });
        enabled.setTooltip(Tooltip.create(label(phase.id() + ".enabled.tooltip")));
        this.addRenderableWidget(enabled);

        final CycleButton<AAConfig.StyleView> view = CycleButton.<AAConfig.StyleView>builder(
                        value -> label("view." + value.name().toLowerCase(Locale.ROOT)))
                .withValues(AAConfig.StyleView.values())
                .withInitialValue(AAConfig.STYLE_VIEW.get())
                .create(right - sideWidth, TOOLBAR_Y, sideWidth, 20, label("view"), (button, value) -> {
                    AAConfig.STYLE_VIEW.set(value);
                    if (this.browser != null)
                        this.browser.setView(value);
                });
        view.setTooltip(Tooltip.create(label("view.tooltip")));
        this.addRenderableWidget(view);

        final int footerY = this.height - FOOTER_HEIGHT + 6;
        this.browser = new StyleBrowser(left, BROWSER_Y, width, footerY - 6 - BROWSER_Y, phase, COLLAPSED,
                this::select, style -> {
                    this.rememberScroll();
                    this.minecraft.setScreen(new StyleSettingsScreen(this, style, phase));
                });
        this.browser.setView(AAConfig.STYLE_VIEW.get());

        final int searchLeft = left + sideWidth + GAP * 2;
        final EditBox search = new EditBox(this.font, searchLeft + 1, TOOLBAR_Y + 1,
                right - sideWidth - GAP * 2 - searchLeft - 2, 18, label("search"));
        search.setHint(label("search").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(filter);
        search.setResponder(value -> {
            filter = value;
            if (this.browser != null)
                this.browser.setFilter(value);
        });
        this.addRenderableWidget(search);
        this.browser.setFilter(filter);
        this.browser.setScroll(SCROLL.getOrDefault(phase, 0.0));
        if (!this.revealed) {
            this.browser.revealSelected();
            this.revealed = true;
        }
        this.addRenderableWidget(this.browser);

        final int buttonWidth = Math.min(120, (width - GAP * 2) / 3);
        final int footerLeft = this.width / 2 - (buttonWidth * 3 + GAP * 2) / 2;
        this.addRenderableWidget(toggle(footerLeft, footerY, buttonWidth, AAConfig.PARTICLES, "particles"));
        this.addRenderableWidget(toggle(footerLeft + buttonWidth + GAP, footerY, buttonWidth, AAConfig.SOUNDS, "sounds"));
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(footerLeft + (buttonWidth + GAP) * 2, footerY, buttonWidth, 20)
                .build());
    }

    private void switchTab(final Phase phase) {
        this.rememberScroll();
        tab = phase;
        this.revealed = false;
        this.rebuildWidgets();
    }

    private void select(final AnimationStyle style) {
        tab.select(style);
        this.rememberScroll();
        this.rebuildWidgets();
    }

    @Override
    public void resize(final Minecraft minecraft, final int width, final int height) {
        this.rememberScroll();
        super.resize(minecraft, width, height);
    }

    private void rememberScroll() {
        if (this.browser != null)
            SCROLL.put(tab, this.browser.scroll());
    }

    @Override
    public void render(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);

        final int tabWidth = Math.min(180, (this.contentWidth() - GAP) / 2);
        final int underline = this.width / 2 - tabWidth - GAP / 2 + tab.ordinal() * (tabWidth + GAP);
        graphics.fill(underline + 2, TAB_Y + 21, underline + tabWidth - 2, TAB_Y + 23, SELECTED);

        if (this.browser != null) {
            this.browser.renderPopup(graphics, mouseX, mouseY, this.width, this.height);
            final List<FormattedCharSequence> tooltip = this.browser.tooltip();
            if (tooltip != null)
                this.setTooltipForNextRenderPass(tooltip);
        }
    }

    @Override
    public void onClose() {
        this.rememberScroll();
        AAConfig.SPEC.save();
        StylePreview.invalidate();
        this.minecraft.setScreen(this.parent);
    }
}
