package com.mlh.create_assembly_animation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

final class GlowCanvas {

    private static final float MIN_ALPHA = 0.004f;
    private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private static final int LABEL_BACKGROUND = 0xF9F2DE;
    private static final int RING_SEGMENTS = 20;

    private record Label(Vector3f position, Component text, int rgb, float alpha, float scale, int line,
                         boolean onTop) {
    }

    private final float[] point = new float[3];
    private final List<Label> labels = new ArrayList<>();

    private VertexConsumer glow;
    private VertexConsumer ink;
    private VertexConsumer overlay;
    private Matrix4f matrix;
    private float glowScale;
    private float inkScale;
    private double eyeX;
    private double eyeY;
    private double eyeZ;

    void begin(final VertexConsumer glow, final VertexConsumer ink, final VertexConsumer overlay, final Matrix4f matrix,
               final float glowScale, final float inkScale, final double eyeX, final double eyeY, final double eyeZ) {
        this.glow = glow;
        this.ink = ink;
        this.overlay = overlay;
        this.matrix = matrix;
        this.glowScale = glowScale;
        this.inkScale = inkScale;
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
    }

    void faces(final int x, final int y, final int z, final int mask, final float grow,
               final float r, final float g, final float b, final float a) {
        final float alpha = Math.min(1f, a * this.glowScale);
        if (alpha < MIN_ALPHA)
            return;
        for (final Direction face : Direction.values()) {
            if ((mask & (1 << face.ordinal())) != 0)
                this.emitRect(this.glow, face, x, y, z, 0f, 1f, 0f, 1f, grow, r, g, b, alpha);
        }
    }

    void stripe(final int x, final int y, final int z, final int mask, final float level, final float halfHeight,
                final float grow, final float r, final float g, final float b, final float a) {
        final float alpha = Math.min(1f, a * this.glowScale);
        if (alpha < MIN_ALPHA)
            return;

        final float lo = Math.max(y, level - halfHeight);
        final float hi = Math.min(y + 1f, level + halfHeight);
        if (lo >= hi)
            return;
        final float mid = Mth.clamp(level, lo, hi);
        final float alphaLo = alpha * Math.max(0f, 1f - (level - lo) / halfHeight);
        final float alphaMid = alpha * Math.max(0f, 1f - Math.abs(level - mid) / halfHeight);
        final float alphaHi = alpha * Math.max(0f, 1f - (hi - level) / halfHeight);

        for (final Direction side : SIDES) {
            if ((mask & (1 << side.ordinal())) == 0)
                continue;

            if (side.getStepX() != 0) {
                final float face = side.getStepX() > 0 ? x + 1f + grow : x - grow;
                final float z0 = z - grow;
                final float z1 = z + 1f + grow;
                this.twoSided(face, lo, z0, face, lo, z1, face, mid, z1, face, mid, z0,
                        r, g, b, alphaLo, alphaLo, alphaMid, alphaMid);
                this.twoSided(face, mid, z0, face, mid, z1, face, hi, z1, face, hi, z0,
                        r, g, b, alphaMid, alphaMid, alphaHi, alphaHi);
            } else {
                final float face = side.getStepZ() > 0 ? z + 1f + grow : z - grow;
                final float x0 = x - grow;
                final float x1 = x + 1f + grow;
                this.twoSided(x0, lo, face, x1, lo, face, x1, mid, face, x0, mid, face,
                        r, g, b, alphaLo, alphaLo, alphaMid, alphaMid);
                this.twoSided(x0, mid, face, x1, mid, face, x1, hi, face, x0, hi, face,
                        r, g, b, alphaMid, alphaMid, alphaHi, alphaHi);
            }
        }
    }

    void edges(final int x, final int y, final int z, final int mask, final float width, final float grow,
               final float r, final float g, final float b, final float a) {
        final float alpha = Math.min(1f, a * this.glowScale);
        if (alpha < MIN_ALPHA)
            return;
        for (int e = 0; e < GlowShape.EDGE_FACES.length; e++) {
            if ((mask & (1 << e)) == 0)
                continue;
            final Direction first = GlowShape.EDGE_FACES[e][0];
            final Direction second = GlowShape.EDGE_FACES[e][1];
            this.emitStrip(this.glow, first, second, x, y, z, width, grow, 0f, 1f, r, g, b, alpha);
            this.emitStrip(this.glow, second, first, x, y, z, width, grow, 0f, 1f, r, g, b, alpha);
        }
    }

    void halo(final int x, final int y, final int z, final int mask, final float height, final float reach,
              final float r, final float g, final float b, final float a) {
        final float alpha = Math.min(1f, a * this.glowScale);
        if (alpha < MIN_ALPHA)
            return;

        final float level = y + height;
        for (final Direction side : SIDES) {
            if ((mask & (1 << side.ordinal())) == 0)
                continue;

            if (side.getStepX() != 0) {
                final float face = side.getStepX() > 0 ? x + 1f : x;
                final float out = face + side.getStepX() * reach;
                this.twoSided(face, level, z, face, level, z + 1f, out, level, z + 1f, out, level, z,
                        r, g, b, alpha, alpha, 0f, 0f);
            } else {
                final float face = side.getStepZ() > 0 ? z + 1f : z;
                final float out = face + side.getStepZ() * reach;
                this.twoSided(x, level, face, x + 1f, level, face, x + 1f, level, out, x, level, out,
                        r, g, b, alpha, alpha, 0f, 0f);
            }
        }
    }

    void spark(final float x, final float y, final float z, final float size,
               final float r, final float g, final float b, final float a) {
        final float alpha = Math.min(1f, a * this.glowScale);
        if (alpha < MIN_ALPHA)
            return;
        this.twoSided(x - size, y - size, z, x + size, y - size, z, x + size, y + size, z, x - size, y + size, z,
                r, g, b, alpha, alpha, alpha, alpha);
        this.twoSided(x - size, y, z - size, x + size, y, z - size, x + size, y, z + size, x - size, y, z + size,
                r, g, b, alpha, alpha, alpha, alpha);
        this.twoSided(x, y - size, z - size, x, y + size, z - size, x, y + size, z + size, x, y - size, z + size,
                r, g, b, alpha, alpha, alpha, alpha);
    }

    boolean facesCamera(final Direction face, final int x, final int y, final int z) {
        final int axis = face.getAxis().ordinal();
        final double eye = axis == 0 ? this.eyeX : axis == 1 ? this.eyeY : this.eyeZ;
        final int coordinate = coordinate(axis, x, y, z);
        return face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? eye > coordinate + 1 : eye < coordinate;
    }

    void inkEdge(final int x, final int y, final int z, final int edge, final float progress, final boolean reversed,
                 final float width, final float grow, final int rgb, final float a) {
        final float alpha = Math.min(1f, a * this.inkScale);
        if (alpha < MIN_ALPHA || progress <= 0f)
            return;

        final float from = reversed ? 1f - progress : 0f;
        final float to = reversed ? 1f : progress;
        final Direction first = GlowShape.EDGE_FACES[edge][0];
        final Direction second = GlowShape.EDGE_FACES[edge][1];

        if (this.facesCamera(first, x, y, z))
            this.emitStrip(this.ink, first, second, x, y, z, width, grow, from, to, red(rgb), green(rgb), blue(rgb), alpha);
        if (this.facesCamera(second, x, y, z))
            this.emitStrip(this.ink, second, first, x, y, z, width, grow, from, to, red(rgb), green(rgb), blue(rgb), alpha);
    }

    void inkQuad(final float ax, final float ay, final float az, final float bx, final float by, final float bz,
                 final float cx, final float cy, final float cz, final float dx, final float dy, final float dz,
                 final int rgb, final float a) {
        this.quad(this.ink, ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, rgb, a);
    }

    void inkPost(final float x, final float y, final float z, final float height, final float width,
                 final int rgb, final float a) {
        if (height <= 0f)
            return;
        final float top = y + height;
        this.inkQuad(x - width, y, z, x + width, y, z, x + width, top, z, x - width, top, z, rgb, a);
        this.inkQuad(x, y, z - width, x, y, z + width, x, top, z + width, x, top, z - width, rgb, a);
    }

    Vector3f towardsEye(final Vector3f from) {
        return new Vector3f((float) (this.eyeX - from.x), (float) (this.eyeY - from.y), (float) (this.eyeZ - from.z));
    }

    Vector3f screenRight(final Vector3f at) {
        final Vector3f view = this.towardsEye(at).normalize();
        final Vector3f right = new Vector3f(0f, 1f, 0f).cross(view);
        if (right.lengthSquared() < 1.0e-6f)
            right.set(1f, 0f, 0f);
        return right.normalize();
    }

    Vector3f screenUp(final Vector3f at) {
        return this.towardsEye(at).normalize().cross(this.screenRight(at)).normalize();
    }

    void overlayRibbon(final Vector3f from, final Vector3f to, final float halfWidth, final int rgb, final float a) {
        final Vector3f along = new Vector3f(to).sub(from);
        final Vector3f side = along.cross(this.towardsEye(from));
        if (side.lengthSquared() < 1.0e-8f)
            return;
        side.normalize(halfWidth);

        final Vector3f extend = new Vector3f(to).sub(from);
        if (extend.lengthSquared() > 1.0e-8f)
            extend.normalize(halfWidth);
        final Vector3f a0 = new Vector3f(from).sub(extend);
        final Vector3f b0 = new Vector3f(to).add(extend);

        this.quad(this.overlay, a0.x + side.x, a0.y + side.y, a0.z + side.z, b0.x + side.x, b0.y + side.y, b0.z + side.z,
                b0.x - side.x, b0.y - side.y, b0.z - side.z, a0.x - side.x, a0.y - side.y, a0.z - side.z, rgb, a);
    }

    void overlayDot(final Vector3f center, final float half, final int rgb, final float a) {
        final Vector3f right = this.screenRight(center).mul(half);
        final Vector3f up = this.screenUp(center).mul(half);
        this.quad(this.overlay,
                center.x - right.x - up.x, center.y - right.y - up.y, center.z - right.z - up.z,
                center.x + right.x - up.x, center.y + right.y - up.y, center.z + right.z - up.z,
                center.x + right.x + up.x, center.y + right.y + up.y, center.z + right.z + up.z,
                center.x - right.x + up.x, center.y - right.y + up.y, center.z - right.z + up.z, rgb, a);
    }

    void overlayRing(final Vector3f center, final float radius, final float halfWidth, final int rgb, final float a) {
        final Vector3f right = this.screenRight(center);
        final Vector3f up = this.screenUp(center);
        final float inner = radius - halfWidth;
        final float outer = radius + halfWidth;

        for (int i = 0; i < RING_SEGMENTS; i++) {
            final float a0 = i * Mth.TWO_PI / RING_SEGMENTS;
            final float a1 = (i + 1) * Mth.TWO_PI / RING_SEGMENTS;
            final float c0 = Mth.cos(a0);
            final float s0 = Mth.sin(a0);
            final float c1 = Mth.cos(a1);
            final float s1 = Mth.sin(a1);
            this.quad(this.overlay,
                    center.x + (right.x * c0 + up.x * s0) * inner, center.y + (right.y * c0 + up.y * s0) * inner, center.z + (right.z * c0 + up.z * s0) * inner,
                    center.x + (right.x * c1 + up.x * s1) * inner, center.y + (right.y * c1 + up.y * s1) * inner, center.z + (right.z * c1 + up.z * s1) * inner,
                    center.x + (right.x * c1 + up.x * s1) * outer, center.y + (right.y * c1 + up.y * s1) * outer, center.z + (right.z * c1 + up.z * s1) * outer,
                    center.x + (right.x * c0 + up.x * s0) * outer, center.y + (right.y * c0 + up.y * s0) * outer, center.z + (right.z * c0 + up.z * s0) * outer,
                    rgb, a);
        }
    }

    void label(final float x, final float y, final float z, final String text, final int rgb, final float alpha,
               final float scale) {
        this.label(x, y, z, Component.literal(text), rgb, alpha, scale, 0, false);
    }

    void label(final float x, final float y, final float z, final Component text, final int rgb, final float alpha,
               final float scale, final int line, final boolean onTop) {
        final float visible = Math.min(1f, alpha * this.inkScale);
        if (visible < 0.03f)
            return;
        this.labels.add(new Label(this.matrix.transformPosition(x, y, z, new Vector3f()), text, rgb, visible, scale,
                line, onTop));
    }

    void flushLabels(final MultiBufferSource.BufferSource buffers, final Quaternionfc cameraRotation, final Font font) {
        if (this.labels.isEmpty())
            return;

        final Quaternionf rotation = new Quaternionf(cameraRotation);
        final PoseStack poseStack = new PoseStack();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        for (int pass = 0; pass < 2; pass++) {
            final boolean onTop = pass == 1;
            final BufferBuilder tag = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (final Label label : this.labels) {
                if (label.onTop == onTop)
                    this.tag(tag, poseStack, label, rotation, font);
            }

            final MeshData mesh = tag.build();
            if (mesh == null)
                continue;
            if (onTop)
                RenderSystem.disableDepthTest();
            else
                RenderSystem.enableDepthTest();
            BufferUploader.drawWithShader(mesh);
        }
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        for (final Label label : this.labels) {
            this.labelPose(poseStack, label, rotation);
            final int alpha = Math.max(8, Math.round(label.alpha * 255f));
            font.drawInBatch(label.text, -font.width(label.text) / 2f, this.labelTop(font, label), (alpha << 24) | label.rgb,
                    false, poseStack.last().pose(), buffers,
                    label.onTop ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            poseStack.popPose();
        }
        buffers.endBatch();

        this.labels.clear();
    }

    private void tag(final BufferBuilder tag, final PoseStack poseStack, final Label label, final Quaternionf rotation,
                     final Font font) {
        this.labelPose(poseStack, label, rotation);
        final float width = font.width(label.text);
        final float y = this.labelTop(font, label);
        final int colour = (Math.round(label.alpha * 0.8f * 255f) << 24) | LABEL_BACKGROUND;
        final Matrix4f pose = poseStack.last().pose();

        final float x0 = -width / 2f - 1f;
        final float x1 = width / 2f + 1f;
        final float y0 = y - 1f;
        final float y1 = y + font.lineHeight;
        tagVertex(tag, pose, x0, y0, colour);
        tagVertex(tag, pose, x1, y0, colour);
        tagVertex(tag, pose, x1, y1, colour);
        tagVertex(tag, pose, x0, y1, colour);
        poseStack.popPose();
    }

    private void labelPose(final PoseStack poseStack, final Label label, final Quaternionf rotation) {
        poseStack.pushPose();
        poseStack.translate(label.position.x, label.position.y, label.position.z);
        poseStack.mulPose(rotation);
        poseStack.scale(0.025f * label.scale, -0.025f * label.scale, 0.025f * label.scale);
    }

    private float labelTop(final Font font, final Label label) {
        return -font.lineHeight / 2f + label.line * (font.lineHeight + 2);
    }

    private static void tagVertex(final VertexConsumer consumer, final Matrix4f pose, final float x, final float y,
                                  final int argb) {
        consumer.addVertex(pose, x, y, -0.01f).setColor(argb);
    }

    private void quad(final VertexConsumer target,
                      final float ax, final float ay, final float az, final float bx, final float by, final float bz,
                      final float cx, final float cy, final float cz, final float dx, final float dy, final float dz,
                      final int rgb, final float a) {
        final float alpha = Math.min(1f, a * this.inkScale);
        if (alpha < MIN_ALPHA)
            return;
        final float r = red(rgb);
        final float g = green(rgb);
        final float b = blue(rgb);
        target.addVertex(this.matrix, ax, ay, az).setColor(r, g, b, alpha);
        target.addVertex(this.matrix, bx, by, bz).setColor(r, g, b, alpha);
        target.addVertex(this.matrix, cx, cy, cz).setColor(r, g, b, alpha);
        target.addVertex(this.matrix, dx, dy, dz).setColor(r, g, b, alpha);
    }

    private void emitStrip(final VertexConsumer target, final Direction face, final Direction toward,
                           final int x, final int y, final int z, final float width, final float grow,
                           final float from, final float to, final float r, final float g, final float b, final float alpha) {
        final int u = (face.getAxis().ordinal() + 1) % 3;
        final boolean positive = toward.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        final float near = positive ? 1f - width : 0f;
        final float far = positive ? 1f : width;

        if (toward.getAxis().ordinal() == u)
            this.emitRect(target, face, x, y, z, near, far, from, to, grow, r, g, b, alpha);
        else
            this.emitRect(target, face, x, y, z, from, to, near, far, grow, r, g, b, alpha);
    }

    private void emitRect(final VertexConsumer target, final Direction face, final int x, final int y, final int z,
                          final float u0, final float u1, final float v0, final float v1, final float grow,
                          final float r, final float g, final float b, final float alpha) {
        final int axis = face.getAxis().ordinal();
        final int u = (axis + 1) % 3;
        final int v = (axis + 2) % 3;
        final boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;

        final float plane = coordinate(axis, x, y, z) + (positive ? 1f + grow : -grow);
        final float span = 1f + 2f * grow;
        final float uStart = coordinate(u, x, y, z) - grow;
        final float vStart = coordinate(v, x, y, z) - grow;
        final float ua = uStart + u0 * span;
        final float ub = uStart + u1 * span;
        final float va = vStart + v0 * span;
        final float vb = vStart + v1 * span;

        if (positive) {
            this.vertex(target, axis, plane, u, ua, v, va, r, g, b, alpha);
            this.vertex(target, axis, plane, u, ub, v, va, r, g, b, alpha);
            this.vertex(target, axis, plane, u, ub, v, vb, r, g, b, alpha);
            this.vertex(target, axis, plane, u, ua, v, vb, r, g, b, alpha);
        } else {
            this.vertex(target, axis, plane, u, ua, v, va, r, g, b, alpha);
            this.vertex(target, axis, plane, u, ua, v, vb, r, g, b, alpha);
            this.vertex(target, axis, plane, u, ub, v, vb, r, g, b, alpha);
            this.vertex(target, axis, plane, u, ub, v, va, r, g, b, alpha);
        }
    }

    private void twoSided(final float ax, final float ay, final float az, final float bx, final float by, final float bz,
                          final float cx, final float cy, final float cz, final float dx, final float dy, final float dz,
                          final float r, final float g, final float b,
                          final float alphaA, final float alphaB, final float alphaC, final float alphaD) {
        this.glow.addVertex(this.matrix, ax, ay, az).setColor(r, g, b, alphaA);
        this.glow.addVertex(this.matrix, bx, by, bz).setColor(r, g, b, alphaB);
        this.glow.addVertex(this.matrix, cx, cy, cz).setColor(r, g, b, alphaC);
        this.glow.addVertex(this.matrix, dx, dy, dz).setColor(r, g, b, alphaD);

        this.glow.addVertex(this.matrix, ax, ay, az).setColor(r, g, b, alphaA);
        this.glow.addVertex(this.matrix, dx, dy, dz).setColor(r, g, b, alphaD);
        this.glow.addVertex(this.matrix, cx, cy, cz).setColor(r, g, b, alphaC);
        this.glow.addVertex(this.matrix, bx, by, bz).setColor(r, g, b, alphaB);
    }

    private void vertex(final VertexConsumer target, final int axis, final float plane, final int u, final float uValue,
                        final int v, final float vValue, final float r, final float g, final float b, final float alpha) {
        this.point[axis] = plane;
        this.point[u] = uValue;
        this.point[v] = vValue;
        target.addVertex(this.matrix, this.point[0], this.point[1], this.point[2]).setColor(r, g, b, alpha);
    }

    private static int coordinate(final int axis, final int x, final int y, final int z) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    private static float red(final int rgb) {
        return ((rgb >> 16) & 0xFF) / 255f;
    }

    private static float green(final int rgb) {
        return ((rgb >> 8) & 0xFF) / 255f;
    }

    private static float blue(final int rgb) {
        return (rgb & 0xFF) / 255f;
    }
}
