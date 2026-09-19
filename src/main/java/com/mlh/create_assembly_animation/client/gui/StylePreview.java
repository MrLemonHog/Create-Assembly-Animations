package com.mlh.create_assembly_animation.client.gui;

import com.mlh.create_assembly_animation.client.AnimationPreview;
import com.mlh.create_assembly_animation.client.AnimationStyle;
import com.mlh.create_assembly_animation.client.Phase;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;

final class StylePreview {

    private static final int PAPER = 0xFFF9F2DE;
    private static final int PAPER_GRID = 0x0C2E3032;
    private static final int GRID = 10;

    private StylePreview() {
    }

    static void render(final GuiGraphics graphics, final AnimationStyle style, final Phase phase, final int x,
                       final int y, final int width, final int height, final boolean compact) {
        final float ticks = (Util.getMillis() % 3_600_000L) / 50f;

        graphics.enableScissor(x, y, x + width, y + height);
        graphics.fill(x, y, x + width, y + height, PAPER);
        for (int gx = x + (width / 2) % GRID; gx < x + width; gx += GRID)
            graphics.fill(gx, y, gx + 1, y + height, PAPER_GRID);
        for (int gy = y + (height / 2) % GRID; gy < y + height; gy += GRID)
            graphics.fill(x, gy, x + width, gy + 1, PAPER_GRID);

        AnimationPreview.render(graphics, style, phase, x, y, width, height, ticks, compact);
        graphics.disableScissor();
    }

    static void invalidate() {
        AnimationPreview.invalidate();
    }
}
