package com.mlh.create_assembly_animation.client.gui;

import com.mlh.create_assembly_animation.AAConfig;
import com.mlh.create_assembly_animation.client.AnimationStyle;
import com.mlh.create_assembly_animation.client.Phase;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import static com.mlh.create_assembly_animation.client.gui.ShipImage.COLUMNS;
import static com.mlh.create_assembly_animation.client.gui.ShipImage.ROWS;
import static com.mlh.create_assembly_animation.client.gui.ShipImage.solid;

final class StylePreview {

    private static final int PAPER = 0xFFF9F2DE;
    private static final int PAPER_GRID = 0x0C2E3032;

    private static final float SWEEP_TICKS = 22f;
    private static final float SWEEP_REST_TICKS = 14f;

    private static final int INK = 0x2E3032;
    private static final int PENCIL = 0xFFB060;
    private static final int LIFT = 0x4E9F3D;
    private static final int PROPULSION = 0xD9822B;
    private static final String CHECK_MARK = "✔";

    private static final float LEAD_TICKS = 8f;

    private static final float DRAFT_SKETCH_TICKS = 16f;
    private static final float DRAFT_DEVELOP_START = 7.2f;
    private static final float DRAFT_DEVELOP_TICKS = 12.8f;
    private static final float DRAFT_ANNOTATE_START = 27.6f;
    private static final float DRAFT_ANNOTATE_TICKS = 14f;
    private static final float DRAFT_ERASE_START = 81.6f;
    private static final float DRAFT_CYCLE = LEAD_TICKS + 100f + 10f;

    private static final float APPROVE_APPEAR_TICKS = 10f;
    private static final float APPROVE_STAMP_AT = 14f;
    private static final float APPROVE_ERASE_START = 50f;
    private static final float APPROVE_CYCLE = LEAD_TICKS + 68f + 12f;

    private static final float ERASE_TICKS = 18f;

    private StylePreview() {
    }

    static void render(final GuiGraphics graphics, final AnimationStyle style, final Phase phase, final int x,
                       final int y, final int width, final int height) {
        final AAConfig.StyleSettings settings = style.settings();
        final float ticks = (Util.getMillis() % 3_600_000L) / 50f * settings.speed().get().floatValue();
        final float brightness = settings.brightness().get().floatValue();
        final int cell = Math.max(2, Math.min((width - 16) / (COLUMNS + 2), (height - 8) / (ROWS + 3)));
        final int left = x + (width - cell * COLUMNS) / 2;
        final int top = y + (height - cell * ROWS) / 2;

        graphics.enableScissor(x, y, x + width, y + height);
        graphics.fill(x, y, x + width, y + height, PAPER);
        for (int gx = left - (left - x) / cell * cell; gx < x + width; gx += cell)
            graphics.fill(gx, y, gx + 1, y + height, PAPER_GRID);
        for (int gy = top - (top - y) / cell * cell; gy < y + height; gy += cell)
            graphics.fill(x, gy, x + width, gy + 1, PAPER_GRID);

        ShipImage.drawBlocks(graphics, left, top, cell);
        final boolean assembly = phase == Phase.ASSEMBLY;
        switch (style) {
            case DIAGRAM -> {
                if (assembly)
                    draft(graphics, ticks, brightness, left, top, cell);
                else
                    approve(graphics, ticks, left, top, cell);
            }
            case SCANNER -> scanner(graphics, ticks, brightness, assembly, left, top, cell);
        }
        graphics.disableScissor();
    }

    private static void scanner(final GuiGraphics graphics, final float ticks, final float brightness,
                                final boolean upward, final int left, final int top, final int cell) {
        final float pass = ticks % (SWEEP_TICKS + SWEEP_REST_TICKS);
        if (pass >= SWEEP_TICKS)
            return;

        final float progress = inOutSine(pass / SWEEP_TICKS);
        final float low = top + ROWS * cell + 1.5f * cell;
        final float high = top - 1.5f * cell;
        final float plane = upward ? Mth.lerp(progress, low, high) : Mth.lerp(progress, high, low);
        final int lineY = Mth.floor(plane);
        final int row = Mth.floor((plane - top) / cell);
        if (row < 0 || row >= ROWS)
            return;

        final int rgb = AAConfig.SCANNER_LASER_COLOR.get().rgb();
        final int core = shade(rgb, 0.55f);
        final int glowColor = shade(rgb, 0.8f);
        final boolean halo = AAConfig.SCANNER_HALO.get();
        final boolean sparks = AAConfig.PARTICLES.get();
        final float strength = Math.min(brightness, 1f);
        final int glow = Math.max(2, cell / 2);

        for (int column = 0; column < COLUMNS; column++) {
            if (!solid(column, row))
                continue;

            int end = column;
            while (solid(end + 1, row))
                end++;
            final int x0 = left + column * cell;
            final int x1 = left + (end + 1) * cell;

            graphics.fillGradient(x0, lineY - glow, x1, lineY, argb(0f, glowColor), argb(0.6f * strength, glowColor));
            graphics.fillGradient(x0, lineY + 1, x1, lineY + 1 + glow, argb(0.6f * strength, glowColor), argb(0f, glowColor));
            graphics.fill(x0, lineY, x1, lineY + 1, argb(strength, core));

            if (halo) {
                for (int i = 0; i < cell; i++) {
                    final int color = argb(0.8f * strength * (1f - (float) i / cell), core);
                    graphics.fill(x0 - 1 - i, lineY, x0 - i, lineY + 1, color);
                    graphics.fill(x1 + i, lineY, x1 + i + 1, lineY + 1, color);
                }
            }

            if (sparks) {
                for (int k = 0; k < 2; k++) {
                    final float phase = (ticks * 0.15f + k * 0.5f + column * 0.37f) % 1f;
                    final int reach = Math.round(phase * cell * 1.5f);
                    final int dy = (k == 0 ? -1 : 1) * Math.round(phase * 2f);
                    final int color = argb((1f - phase) * strength, core);
                    graphics.fill(x0 - 1 - reach, lineY + dy, x0 - reach, lineY + dy + 1, color);
                    graphics.fill(x1 + reach, lineY + dy, x1 + reach + 1, lineY + dy + 1, color);
                }
            }

            column = end;
        }
    }

    private static void draft(final GuiGraphics graphics, final float ticks, final float brightness, final int left,
                              final int top, final int cell) {
        final float time = ticks % DRAFT_CYCLE - LEAD_TICKS;
        if (time <= 0f)
            return;

        final float erase = clamp01((time - DRAFT_ERASE_START) / ERASE_TICKS);
        final float[] strokes = new float[COLUMNS * ROWS];
        final float pencil = Math.min(brightness, 1f);

        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                if (!solid(column, row))
                    continue;

                final float distance = ShipImage.distance(column, row);
                final float start = (distance * 0.85f + ShipImage.noise(column, row) * 0.15f) * DRAFT_SKETCH_TICKS;
                final float drawn = clamp01((time - start) / 5f);
                final float remaining = erase <= 0f ? 1f : 1f - clamp01((erase - (1f - distance) * 0.35f) / 0.5f);
                strokes[row * COLUMNS + column] = Math.min(drawn, remaining);

                if (drawn > 0f && drawn < 1f && pencil > 0f) {
                    final int tip = left + column * cell + Math.round(cell * drawn);
                    final int py = top + row * cell;
                    graphics.fill(tip - 1, py - 1, tip + 2, py + 2, argb(0.9f * pencil, PENCIL));
                }
            }
        }

        ShipImage.drawDiagram(graphics, left, top, cell, (time - DRAFT_DEVELOP_START) / DRAFT_DEVELOP_TICKS, erase,
                true, strokes);

        final float annotate = clamp01((time - DRAFT_ANNOTATE_START) / DRAFT_ANNOTATE_TICKS);
        annotations(graphics, left, top, cell, outCubic(annotate * 1.6f), 1f, outCubic((annotate - 0.3f) / 0.7f),
                outBack((time - DRAFT_ANNOTATE_START - 4f) / 10f), 1f - clamp01((time - DRAFT_ERASE_START) / 6f));
    }

    private static void approve(final GuiGraphics graphics, final float ticks, final int left, final int top,
                                final int cell) {
        final float time = ticks % APPROVE_CYCLE - LEAD_TICKS;
        if (time <= 0f)
            return;

        final float appear = clamp01(time / APPROVE_APPEAR_TICKS);
        final float erase = clamp01((time - APPROVE_ERASE_START) / ERASE_TICKS);
        final float[] strokes = new float[COLUMNS * ROWS];
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                final float gone = erase <= 0f ? 0f : clamp01((erase - ShipImage.distance(column, row) * 0.35f) / 0.5f);
                strokes[row * COLUMNS + column] = Math.min(clamp01(appear * 1.5f), 1f - gone);
            }
        }

        ShipImage.drawDiagram(graphics, left, top, cell, time / APPROVE_APPEAR_TICKS, erase, false, strokes);

        final float leader = time < APPROVE_STAMP_AT
                ? Math.min(outCubic((time - APPROVE_APPEAR_TICKS * 0.5f) / (APPROVE_STAMP_AT - APPROVE_APPEAR_TICKS * 0.5f)), 0.99f)
                : 1f;
        final float stamp = time < APPROVE_STAMP_AT ? 0f : (float) Math.exp(-(time - APPROVE_STAMP_AT) / 2.5f);
        annotations(graphics, left, top, cell, leader, 1f + 0.6f * stamp,
                outCubic((time - APPROVE_APPEAR_TICKS * 0.6f) / 10f), outBack((time - APPROVE_APPEAR_TICKS) / 10f),
                1f - clamp01((time - APPROVE_ERASE_START) / 6f));
    }

    private static void annotations(final GuiGraphics graphics, final int left, final int top, final int cell,
                                    final float leader, final float markScale, final float dimensions,
                                    final float forces, final float alpha) {
        if (alpha <= 0f || leader <= 0f)
            return;

        final Font font = Minecraft.getInstance().font;
        final int ink = argb(alpha, INK);
        final boolean text = alpha > 0.05f;
        final int bottom = top + ROWS * cell;
        final int middle = left + COLUMNS * cell / 2;

        final int centerY = top + ROWS * cell * 3 / 5;
        final int peak = top - cell;
        graphics.fill(middle - 1, centerY - 1, middle + 2, centerY + 2, ink);
        graphics.fill(middle, Math.round(Mth.lerp(leader, centerY, peak)), middle + 1, centerY, ink);
        if (leader >= 1f && text) {
            final PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(middle + 0.5f, peak, 0f);
            pose.scale(markScale, markScale, 1f);
            graphics.drawString(font, CHECK_MARK, -font.width(CHECK_MARK) / 2, -font.lineHeight + 1, ink, false);
            pose.popPose();
        }

        if (AAConfig.DIAGRAM_MEASUREMENTS.get() && dimensions > 0f) {
            final int lineY = bottom + cell / 2 + 1;
            final int right = left + COLUMNS * cell;
            final int half = Math.round(COLUMNS * cell / 2f * clamp01(dimensions));
            if (half > 0) {
                graphics.fill(left, bottom + 1, left + 1, lineY + 3, ink);
                graphics.fill(right - 1, bottom + 1, right, lineY + 3, ink);
                graphics.fill(middle - half, lineY, middle + half, lineY + 1, ink);
                for (int i = 1; i <= 3 && i < half; i++) {
                    graphics.fill(middle - half + i, lineY - i, middle - half + i + 1, lineY + i + 1, ink);
                    graphics.fill(middle + half - i - 1, lineY - i, middle + half - i, lineY + i + 1, ink);
                }
            }
            if (dimensions >= 1f && text) {
                final String length = String.valueOf(COLUMNS);
                graphics.drawString(font, length, middle - font.width(length) / 2, lineY + 3, ink, false);
            }
        }

        if (AAConfig.DIAGRAM_FORCE_ARROWS.get()) {
            arrowUp(graphics, left + 6 * cell + cell / 2, top + cell, Math.round(1.4f * cell * forces), argb(alpha, LIFT));
            arrowRight(graphics, left + 8 * cell, top + 3 * cell + cell / 2, Math.round(1.2f * cell * forces),
                    argb(alpha, PROPULSION));
        }
    }

    private static void arrowUp(final GuiGraphics graphics, final int x, final int y, final int length, final int color) {
        if (length < 3)
            return;
        graphics.fill(x - 1, y - 1, x + 2, y + 2, color);
        graphics.fill(x, y - length, x + 1, y, color);
        for (int i = 1; i <= 3; i++) {
            graphics.fill(x - i, y - length + i, x - i + 1, y - length + i + 1, color);
            graphics.fill(x + i, y - length + i, x + i + 1, y - length + i + 1, color);
        }
    }

    private static void arrowRight(final GuiGraphics graphics, final int x, final int y, final int length, final int color) {
        if (length < 3)
            return;
        graphics.fill(x - 1, y - 1, x + 2, y + 2, color);
        graphics.fill(x, y, x + length, y + 1, color);
        for (int i = 1; i <= 3; i++) {
            graphics.fill(x + length - i - 1, y - i, x + length - i, y - i + 1, color);
            graphics.fill(x + length - i - 1, y + i, x + length - i, y + i + 1, color);
        }
    }

    private static int argb(final float alpha, final int rgb) {
        return Mth.clamp((int) (alpha * 255f), 0, 255) << 24 | rgb & 0xFFFFFF;
    }

    private static int shade(final int rgb, final float factor) {
        return (int) ((rgb >> 16 & 0xFF) * factor) << 16 | (int) ((rgb >> 8 & 0xFF) * factor) << 8
                | (int) ((rgb & 0xFF) * factor);
    }

    private static float clamp01(final float t) {
        return Mth.clamp(t, 0f, 1f);
    }

    private static float inOutSine(final float t) {
        return (float) (-(Math.cos(Math.PI * clamp01(t)) - 1) / 2);
    }

    private static float outCubic(final float t) {
        final float c = 1f - clamp01(t);
        return 1f - c * c * c;
    }

    private static float outBack(final float t) {
        final float c = clamp01(t) - 1f;
        return 1f + 2.70158f * c * c * c + 1.70158f * c * c;
    }
}
