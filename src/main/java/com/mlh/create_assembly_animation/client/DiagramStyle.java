package com.mlh.create_assembly_animation.client;

import com.mlh.create_assembly_animation.AAConfig;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.simulated_team.simulated.index.SimSoundEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.UUID;

import static com.mlh.create_assembly_animation.client.ShipAnimation.clamp01;
import static com.mlh.create_assembly_animation.client.ShipAnimation.gauss;
import static com.mlh.create_assembly_animation.client.ShipAnimation.outBack;
import static com.mlh.create_assembly_animation.client.ShipAnimation.outCubic;
import static com.mlh.create_assembly_animation.client.ShipAnimation.smooth;

final class DiagramStyle {

    private static final int INK = 0x2E3032;
    private static final int INK_SHADOW = 0x696965;

    private static final float[] PENCIL = {1.00f, 0.86f, 0.55f};
    private static final float INK_WIDTH = 0.06f;
    private static final float INK_GROW = 0.01f;
    private static final int MAX_PENCILS = 32;

    private static final String CENTER_OF_MASS = "⊕";
    private static final String CHECK_MARK = "✔";

    private DiagramStyle() {
    }

    static ShipAnimation assemble(@Nullable final UUID subLevel, final GlowShape shape) {
        return new Draft(subLevel, shape);
    }

    static ShipAnimation align(@Nullable final UUID subLevel, final GlowShape shape) {
        return new Pinned(subLevel, shape);
    }

    static ShipAnimation disassemble(final GlowShape shape) {
        return new Approve(shape);
    }

    private static boolean reversed(final GlowShape shape, final int block, final int edge) {
        return (((int) (shape.noise[block] * 4096f) >> edge) & 1) == 1;
    }

    private static int inkFor(final int edge) {
        for (final Direction face : GlowShape.EDGE_FACES[edge]) {
            if (face == Direction.DOWN || face == Direction.SOUTH || face == Direction.EAST)
                return INK;
        }
        return INK_SHADOW;
    }

    private static void edge(final GlowCanvas canvas, final GlowShape shape, final int block, final int edge,
                             final float progress, final float width, final float alpha) {
        canvas.inkEdge(shape.x[block], shape.y[block], shape.z[block], edge, progress, reversed(shape, block, edge),
                width, INK_GROW, inkFor(edge), alpha);
    }

    private static void pencil(final GlowCanvas canvas, final GlowShape shape, final int block, final int edge,
                               final float progress, final float alpha) {
        final Direction first = GlowShape.EDGE_FACES[edge][0];
        final Direction second = GlowShape.EDGE_FACES[edge][1];
        final float[] at = {shape.x[block], shape.y[block], shape.z[block]};

        at[first.getAxis().ordinal()] += first.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1f : 0f;
        at[second.getAxis().ordinal()] += second.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1f : 0f;
        at[3 - first.getAxis().ordinal() - second.getAxis().ordinal()] += reversed(shape, block, edge) ? 1f - progress : progress;

        canvas.spark(at[0], at[1], at[2], 0.09f, PENCIL[0], PENCIL[1], PENCIL[2], alpha);
    }

    private static float annotationScale(final GlowShape shape) {
        return Mth.clamp(shape.maxDistance / 8f, 1f, 4f);
    }

    private static void callout(final GlowCanvas canvas, final GlowShape shape, final float grow, final float alpha,
                                final String mark, final float markScale, @Nullable final Forces forces) {
        if (grow <= 0f || alpha <= 0f)
            return;

        final float scale = annotationScale(shape);
        final float top = shape.maxY + 1f + 1.5f * scale;
        final float height = (top - shape.centerY) * clamp01(grow);
        canvas.inkPost(shape.centerX, shape.centerY, shape.centerZ, height, 0.035f, INK, alpha);

        if (grow < 1f)
            return;

        final float markY = top + 0.15f * markScale;
        canvas.label(shape.centerX, markY, shape.centerZ, Component.literal(mark), INK, alpha, markScale, 0, false);
        if (forces != null && AAConfig.DIAGRAM_MEASUREMENTS.get())
            canvas.label(shape.centerX, markY - 0.2f * markScale, shape.centerZ,
                    Component.translatable("simulated.contraption_diagram.mass", String.format("%,.2f", forces.mass())),
                    INK, alpha, scale, 1, false);
    }

    private static void dimensions(final GlowCanvas canvas, final GlowShape shape, final float grow, final float alpha) {
        if (!AAConfig.DIAGRAM_MEASUREMENTS.get() || grow <= 0f || alpha <= 0f)
            return;

        final float y = shape.minY + 0.03f;
        final float scale = annotationScale(shape);
        dimension(canvas, true, shape.minX, shape.maxX + 1f, shape.maxZ + 1f, y, grow, alpha, scale);
        dimension(canvas, false, shape.minZ, shape.maxZ + 1f, shape.maxX + 1f, y, grow, alpha, scale);
    }

    private static void dimension(final GlowCanvas canvas, final boolean alongX, final float from, final float to,
                                  final float hull, final float y, final float grow, final float alpha, final float scale) {
        final float offset = 1.2f * Math.min(scale, 2f);
        final float line = hull + offset;
        final float width = 0.03f * scale;
        final float mid = (from + to) / 2f;
        final float half = (to - from) / 2f * clamp01(grow);

        final float reach = (offset + 0.3f) * clamp01(grow * 2f);
        flat(canvas, alongX, from - width, from + width, hull + 0.15f, hull + 0.15f + reach, y, alpha);
        flat(canvas, alongX, to - width, to + width, hull + 0.15f, hull + 0.15f + reach, y, alpha);

        if (half <= 0.01f)
            return;

        final float head = Math.min(0.35f * scale, half);
        flat(canvas, alongX, mid - half + head, mid + half - head, line - width, line + width, y, alpha);
        arrow(canvas, alongX, mid - half, mid - half + head, line, head * 0.5f, y, alpha);
        arrow(canvas, alongX, mid + half, mid + half - head, line, head * 0.5f, y, alpha);

        final float labelAlpha = alpha * clamp01((grow - 0.7f) / 0.3f);
        final String length = Math.round(to - from) + "m";
        if (alongX)
            canvas.label(mid, y + 0.35f * scale, line, length, INK, labelAlpha, scale * 1.3f);
        else
            canvas.label(line, y + 0.35f * scale, mid, length, INK, labelAlpha, scale * 1.3f);
    }

    private static void flat(final GlowCanvas canvas, final boolean alongX, final float a0, final float a1,
                             final float c0, final float c1, final float y, final float alpha) {
        if (alongX)
            canvas.inkQuad(a0, y, c0, a1, y, c0, a1, y, c1, a0, y, c1, INK, alpha);
        else
            canvas.inkQuad(c0, y, a0, c0, y, a1, c1, y, a1, c1, y, a0, INK, alpha);
    }

    private static void arrow(final GlowCanvas canvas, final boolean alongX, final float tip, final float base,
                              final float at, final float spread, final float y, final float alpha) {
        if (alongX)
            canvas.inkQuad(tip, y, at, base, y, at - spread, base, y, at + spread, base, y, at + spread, INK, alpha);
        else
            canvas.inkQuad(at, y, tip, at - spread, y, base, at + spread, y, base, at + spread, y, base, INK, alpha);
    }

    private static void gizmo(final GlowCanvas canvas, final GlowShape shape, final float time, final float alpha) {
        if (!AAConfig.DIAGRAM_ROTATION_GIZMO.get())
            return;

        final float dx = Math.max(Math.abs(shape.minX - shape.centerX), Math.abs(shape.maxX + 1 - shape.centerX));
        final float dz = Math.max(Math.abs(shape.minZ - shape.centerZ), Math.abs(shape.maxZ + 1 - shape.centerZ));
        final float radius = (float) Math.sqrt(dx * dx + dz * dz) + 1.2f;
        final float y = shape.minY - 0.25f;
        final float width = 0.07f;
        final int dashes = Mth.clamp(Mth.ceil(radius * 3f), 24, 96);
        final float step = Mth.TWO_PI / dashes;
        final float phase = time * 0.03f;
        final float inner = radius - width;
        final float outer = radius + width;
        final float cx = shape.centerX;
        final float cz = shape.centerZ;

        for (int i = 0; i < dashes; i++) {
            final float a0 = phase + i * step;
            final float a1 = a0 + step * 0.55f;
            canvas.inkQuad(cx + Mth.cos(a0) * inner, y, cz + Mth.sin(a0) * inner,
                    cx + Mth.cos(a1) * inner, y, cz + Mth.sin(a1) * inner,
                    cx + Mth.cos(a1) * outer, y, cz + Mth.sin(a1) * outer,
                    cx + Mth.cos(a0) * outer, y, cz + Mth.sin(a0) * outer, INK, alpha);
        }

        final float head = phase + Mth.PI * 0.5f;
        final float tip = head + 0.6f / radius;
        canvas.inkQuad(cx + Mth.cos(tip) * radius, y, cz + Mth.sin(tip) * radius,
                cx + Mth.cos(head) * (radius - 0.25f), y, cz + Mth.sin(head) * (radius - 0.25f),
                cx + Mth.cos(head) * (radius + 0.25f), y, cz + Mth.sin(head) * (radius + 0.25f),
                cx + Mth.cos(head) * (radius + 0.25f), y, cz + Mth.sin(head) * (radius + 0.25f), INK, alpha);
    }

    private static void outline(final GlowCanvas canvas, final GlowShape shape, final int block, final float progress,
                                final float width, final float alpha) {
        final int edges = shape.edges[block] & 0xFFFF;
        for (int e = 0; e < GlowShape.EDGE_FACES.length; e++) {
            if ((edges & (1 << e)) != 0)
                edge(canvas, shape, block, e, progress, width, alpha);
        }
    }

    private static final int PAPER = 0xF9F2DE;

    private static final int MAX_LABELS = 10;

    private static void forceArrows(final GlowCanvas canvas, final GlowShape shape, @Nullable final Forces forces,
                                    final float grow, final float alpha, final float scale) {
        if (!AAConfig.DIAGRAM_FORCE_ARROWS.get() || forces == null || forces.arrows().isEmpty() || grow <= 0f || alpha <= 0f)
            return;

        final float extent = Math.max(Math.max(shape.maxX - shape.minX, shape.maxY - shape.minY), shape.maxZ - shape.minZ) + 1;
        final float radius = Math.max(extent * 0.55f, 2f);
        final BlockPos origin = shape.origin;
        final float width = 0.045f * scale;
        int labelled = 0;

        for (final Forces.Arrow arrow : forces.arrows()) {
            final Vector3f base = new Vector3f((float) (arrow.x() - origin.getX()), (float) (arrow.y() - origin.getY()),
                    (float) (arrow.z() - origin.getZ()));
            final Vector3f direction = new Vector3f((float) arrow.force().x, (float) arrow.force().y, (float) arrow.force().z).normalize();
            final float length = (float) (Math.max(0.25, arrow.magnitude() / forces.maxMagnitude()) * radius * 0.5) * grow;
            final Vector3f view = canvas.towardsEye(base).normalize();
            final float facing = direction.dot(view);
            final int color = arrow.color();

            final Vector3f labelAt;
            if (Math.abs(facing) > 0.85f) {
                final float ring = 0.3f * scale * Math.min(grow, 1f);
                canvas.overlayRing(base, ring, width * 2.2f, PAPER, alpha);
                canvas.overlayRing(base, ring, width, color, alpha);
                if (facing > 0f) {
                    canvas.overlayDot(base, 0.1f * scale, color, alpha);
                } else {
                    final Vector3f right = canvas.screenRight(base).mul(ring * 0.7f);
                    final Vector3f up = canvas.screenUp(base).mul(ring * 0.7f);
                    canvas.overlayRibbon(new Vector3f(base).sub(right).sub(up), new Vector3f(base).add(right).add(up), width, color, alpha);
                    canvas.overlayRibbon(new Vector3f(base).sub(right).add(up), new Vector3f(base).add(right).sub(up), width, color, alpha);
                }
                labelAt = new Vector3f(canvas.screenUp(base)).mul(ring + 0.45f * scale).add(base);
            } else {
                final Vector3f tip = new Vector3f(direction).mul(length).add(base);
                final Vector3f side = new Vector3f(direction).cross(view).normalize();
                final float head = Math.min(0.45f * scale, length * 0.5f);
                final Vector3f back = new Vector3f(direction).mul(-head);
                final Vector3f left = new Vector3f(tip).add(back).add(new Vector3f(side).mul(head * 0.6f));
                final Vector3f right = new Vector3f(tip).add(back).sub(new Vector3f(side).mul(head * 0.6f));

                for (int pass = 0; pass < 2; pass++) {
                    final int colour = pass == 0 ? PAPER : color;
                    final float thickness = pass == 0 ? width * 2.2f : width;
                    canvas.overlayDot(base, (pass == 0 ? 0.13f : 0.09f) * scale, colour, alpha);
                    canvas.overlayRibbon(base, tip, thickness, colour, alpha);
                    canvas.overlayRibbon(tip, left, thickness, colour, alpha);
                    canvas.overlayRibbon(tip, right, thickness, colour, alpha);
                }
                labelAt = new Vector3f(direction).mul(0.55f * scale).add(tip);
            }

            if (grow >= 0.95f && labelled < MAX_LABELS) {
                labelled++;
                canvas.label(labelAt.x, labelAt.y, labelAt.z, arrow.name(), color, alpha, 0.9f * scale, 0, true);
                canvas.label(labelAt.x, labelAt.y, labelAt.z,
                        Component.translatable("simulated.contraption_diagram.force_arrow_magnitude",
                                String.format("%,.2f", arrow.magnitude())),
                        INK, alpha, 0.9f * scale, 1, true);
            }
        }
    }

    private abstract static class Page extends ShipAnimation {

        protected final DiagramRedraw model;
        @Nullable
        protected Forces forces;
        protected float forcesSince = -1f;

        Page(final Role role, @Nullable final UUID subLevel, final GlowShape shape) {
            super(role, subLevel, shape);
            this.model = new DiagramRedraw(shape);
        }

        @Override
        void acceptForces(final Forces forces) {
            this.forces = forces;
            if (this.forcesSince < 0f)
                this.forcesSince = this.age * this.step();
        }

        protected float forceGrowth(final float time, final float from) {
            return this.forcesSince < 0f ? 0f : outBack((time - Math.max(from, this.forcesSince)) / 10f);
        }

        @Override
        void close() {
            this.model.close();
        }
    }

    private static final class Draft extends Page {

        private static final float EDGE_TICKS = 3f;
        private static final float EDGE_GAP = 0.6f;
        private static final float ANNOTATE_TICKS = 14f;
        private static final float HOLD_TICKS = 40f;
        private static final float ERASE_TICKS = 18f;

        private final float reach;
        private final float sketchEnd;
        private final float developStart;
        private final float developTicks;
        private final float annotateStart;
        private final float eraseStart;
        private final float[] strokeStart;

        Draft(@Nullable final UUID subLevel, final GlowShape shape) {
            super(Role.ASSEMBLE, subLevel, shape);
            this.reach = Math.max(shape.maxDistance, 1f);

            final float sketchTicks = Mth.clamp(shape.maxDistance / 0.9f, 16f, 50f);
            this.strokeStart = new float[shape.size];
            for (int i = 0; i < shape.size; i++)
                this.strokeStart[i] = (shape.distance[i] / this.reach * 0.85f + shape.noise[i] * 0.15f) * sketchTicks;

            this.sketchEnd = sketchTicks + 11 * EDGE_GAP + EDGE_TICKS;
            this.developStart = sketchTicks * 0.45f;
            this.developTicks = Mth.clamp(sketchTicks * 0.8f, 12f, 40f);
            this.annotateStart = Math.max(this.sketchEnd, this.developStart + this.developTicks) + 2f;
            this.eraseStart = this.annotateStart + ANNOTATE_TICKS + HOLD_TICKS;
        }

        @Override
        protected float lifetime() {
            return this.eraseStart + ERASE_TICKS + 2f;
        }

        private float erase(final float time) {
            return clamp01((time - this.eraseStart) / ERASE_TICKS);
        }

        @Override
        protected void drawDirect(final ClientLevel level, final Matrix4f matrix, final float time, final float fade) {
            this.model.draw(level, matrix, (time - this.developStart) / this.developTicks,
                    Math.max(this.erase(time), 1f - fade), true);
        }

        @Override
        protected void draw(final GlowCanvas canvas, final float time) {
            final float erase = this.erase(time);
            int pencils = 0;

            for (final int block : this.shape.shell) {
                final int edges = this.shape.edges[block] & 0xFFFF;
                final float near = 1f - this.shape.distance[block] / this.reach;
                final float remaining = erase <= 0f ? 1f : 1f - clamp01((erase - near * 0.35f) / 0.5f);
                int order = 0;

                for (int e = 0; e < GlowShape.EDGE_FACES.length; e++) {
                    if ((edges & (1 << e)) == 0)
                        continue;

                    final float drawn = clamp01((time - this.strokeStart[block] - order++ * EDGE_GAP) / EDGE_TICKS);
                    final float progress = Math.min(drawn, remaining);
                    if (progress <= 0f)
                        continue;

                    edge(canvas, this.shape, block, e, progress, INK_WIDTH, 0.95f);
                    if (drawn < 1f && erase <= 0f && pencils < MAX_PENCILS) {
                        pencil(canvas, this.shape, block, e, progress, 0.6f);
                        pencils++;
                    }
                }
            }

            final float annotate = clamp01((time - this.annotateStart) / ANNOTATE_TICKS);
            final float annotationAlpha = 1f - clamp01((time - this.eraseStart) / 6f);
            final float scale = annotationScale(this.shape);

            callout(canvas, this.shape, outCubic(annotate * 1.6f), annotationAlpha, CHECK_MARK, scale * 2.2f, this.forces);
            dimensions(canvas, this.shape, outCubic((annotate - 0.3f) / 0.7f), annotationAlpha);
            forceArrows(canvas, this.shape, this.forces, this.forceGrowth(time, this.annotateStart + 4f), annotationAlpha, scale);
        }

        @Override
        protected void onStart(final ClientLevel level, @Nullable final Pose3dc pose) {
            this.sound(level, pose, SoundEvents.VILLAGER_WORK_CARTOGRAPHER, 0.9f, 1.0f);
        }

        @Override
        protected void onTick(final ClientLevel level, @Nullable final Pose3dc pose, final float time) {
            if (time < this.sketchEnd && this.age % 3 == 0)
                this.sound(level, pose, SimSoundEvents.DIAGRAM_TAP.event(), 0.45f, 0.9f + this.random.nextFloat() * 0.3f);
            if (this.crossed(time, this.developStart))
                this.sound(level, pose, SoundEvents.BOOK_PAGE_TURN, 0.6f, 1.1f);
            if (this.crossed(time, this.annotateStart + ANNOTATE_TICKS * 0.6f))
                this.sound(level, pose, SimSoundEvents.DIAGRAM_CHECKMARK.event(), 0.8f, 1.0f);
            if (this.crossed(time, this.eraseStart))
                this.sound(level, pose, SimSoundEvents.DIAGRAM_ERASE.event(), 0.9f, 1.0f);
        }
    }

    private static final class Pinned extends Page {

        private static final float APPEAR_TICKS = 10f;
        private static final float TRACE_PERIOD = 50f;

        private final float reach;

        Pinned(@Nullable final UUID subLevel, final GlowShape shape) {
            super(Role.ALIGN, subLevel, shape);
            this.reach = Math.max(shape.maxDistance, 1f);
        }

        @Override
        protected float lifetime() {
            return 20 * 60 * this.step();
        }

        @Override
        protected void drawDirect(final ClientLevel level, final Matrix4f matrix, final float time, final float fade) {
            this.model.draw(level, matrix, time / APPEAR_TICKS, 1f - fade, true);
        }

        @Override
        protected void draw(final GlowCanvas canvas, final float time) {
            final float appear = smooth(time / APPEAR_TICKS);
            final float front = ((time % TRACE_PERIOD) / TRACE_PERIOD) * (this.reach + 3f) - 1.5f;
            int pencils = 0;

            for (final int block : this.shape.shell) {
                outline(canvas, this.shape, block, appear, INK_WIDTH, 0.9f);

                final float retrace = gauss((this.shape.distance[block] - front) / 1.2f);
                if (retrace > 0.7f && pencils < MAX_PENCILS) {
                    final int edges = this.shape.edges[block] & 0xFFFF;
                    if (edges != 0) {
                        pencil(canvas, this.shape, block, Integer.numberOfTrailingZeros(edges), 0.5f, 0.5f * appear);
                        pencils++;
                    }
                }
            }

            final float scale = annotationScale(this.shape);
            gizmo(canvas, this.shape, time, 0.8f * appear);
            callout(canvas, this.shape, appear, appear, CENTER_OF_MASS, scale * 2.2f, this.forces);
            forceArrows(canvas, this.shape, this.forces, this.forceGrowth(time, APPEAR_TICKS), appear, scale);
        }

        @Override
        protected void onStart(final ClientLevel level, @Nullable final Pose3dc pose) {
            this.sound(level, pose, SoundEvents.VILLAGER_WORK_CARTOGRAPHER, 0.7f, 0.9f);
        }

        @Override
        protected void onTick(final ClientLevel level, @Nullable final Pose3dc pose, final float time) {
            if (this.age % 12 == 0)
                this.sound(level, pose, SimSoundEvents.DIAGRAM_TAP.event(), 0.3f, 1.0f + this.random.nextFloat() * 0.2f);
        }
    }

    private static final class Approve extends Page {

        private static final float APPEAR_TICKS = 10f;
        private static final float STAMP_AT = 14f;
        private static final float HOLD_TICKS = 36f;
        private static final float ERASE_TICKS = 18f;

        private final float reach;
        private final float eraseStart;

        Approve(final GlowShape shape) {
            super(Role.DISASSEMBLE, null, shape);
            this.reach = Math.max(shape.maxDistance, 1f);
            this.eraseStart = STAMP_AT + HOLD_TICKS;
        }

        @Override
        protected float lifetime() {
            return this.eraseStart + ERASE_TICKS + 2f;
        }

        private float erase(final float time) {
            return clamp01((time - this.eraseStart) / ERASE_TICKS);
        }

        @Override
        protected void drawDirect(final ClientLevel level, final Matrix4f matrix, final float time, final float fade) {
            this.model.draw(level, matrix, time / APPEAR_TICKS, Math.max(this.erase(time), 1f - fade), false);
        }

        @Override
        protected void draw(final GlowCanvas canvas, final float time) {
            final float appear = clamp01(time / APPEAR_TICKS);
            final float erase = this.erase(time);

            for (final int block : this.shape.shell) {
                final float gone = erase <= 0f ? 0f
                        : clamp01((erase - this.shape.distance[block] / this.reach * 0.35f) / 0.5f);
                outline(canvas, this.shape, block, Math.min(clamp01(appear * 1.5f), 1f - gone), INK_WIDTH, 0.95f);
            }

            final float annotationAlpha = 1f - clamp01((time - this.eraseStart) / 6f);
            final float scale = annotationScale(this.shape);
            final float stamp = time < STAMP_AT ? 0f : (float) Math.exp(-(time - STAMP_AT) / 2.5f);
            final float leader = time < STAMP_AT ? Math.min(outCubic((time - APPEAR_TICKS * 0.5f) / (STAMP_AT - APPEAR_TICKS * 0.5f)), 0.99f) : 1f;

            callout(canvas, this.shape, leader, annotationAlpha, CHECK_MARK, scale * 2.6f * (1f + 0.6f * stamp), this.forces);
            dimensions(canvas, this.shape, outCubic((time - APPEAR_TICKS * 0.6f) / 10f), annotationAlpha);
            forceArrows(canvas, this.shape, this.forces, this.forceGrowth(time, APPEAR_TICKS), annotationAlpha, scale);
        }

        @Override
        protected void onStart(final ClientLevel level, @Nullable final Pose3dc pose) {
            this.sound(level, pose, SoundEvents.BOOK_PAGE_TURN, 0.7f, 1.0f);
        }

        @Override
        protected void onTick(final ClientLevel level, @Nullable final Pose3dc pose, final float time) {
            if (this.crossed(time, STAMP_AT))
                this.sound(level, pose, SimSoundEvents.DIAGRAM_CHECKMARK.event(), 1.0f, 1.0f);
            if (this.crossed(time, this.eraseStart))
                this.sound(level, pose, SimSoundEvents.DIAGRAM_ERASE.event(), 0.9f, 1.0f);
        }
    }
}
