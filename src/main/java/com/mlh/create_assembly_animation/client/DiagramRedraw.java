package com.mlh.create_assembly_animation.client;

import com.mlh.create_assembly_animation.CreateAssemblyAnimation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class DiagramRedraw implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateAssemblyAnimation.ID);

    private static final float SOFTNESS = 0.35f;

    private static final float BLOCK_GROW = 0.12f;
    private static final float BLOCK_ENTITY_GROW = 0.6f;

    private static final float DIAGRAM_EXPOSURE = 0.41f;

    @Nullable
    private static ShaderInstance shader;
    @Nullable
    private static TextureTarget scene;
    @Nullable
    private static TextureTarget mask;

    private final GlowShape shape;
    private final float reach;

    @Nullable
    private VertexBuffer volumes;
    private boolean built;

    DiagramRedraw(final GlowShape shape) {
        this.shape = shape;
        this.reach = Math.max(shape.maxDistance, 1f);
    }

    public static void registerShader(final RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    CreateAssemblyAnimation.asResource("diagram_post"), DefaultVertexFormat.POSITION), loaded -> shader = loaded);
        } catch (final IOException e) {
            LOGGER.error("Could not load the diagram shader; the diagram style will draw outlines only.", e);
        }
    }

    void draw(final BlockGetter blocks, final float daylight, final Matrix4f matrix, final float reveal,
              final float erase, final boolean eraseFromFar) {
        if (reveal <= 0f || erase >= 1f)
            return;

        if (!this.built) {
            this.built = true;
            this.volumes = this.build(blocks);
        }
        if (this.volumes != null)
            this.redraw(daylight, matrix, this.volumes, front(reveal), front(erase), eraseFromFar);
    }

    private static float front(final float progress) {
        return ShipAnimation.clamp01(progress) * (1f + 2f * SOFTNESS) - SOFTNESS;
    }

    private void redraw(final float daylight, final Matrix4f local, final VertexBuffer volumes, final float reveal,
                        final float erase, final boolean eraseFromFar) {
        final ShaderInstance post = shader;
        final ShaderInstance position = GameRenderer.getPositionShader();
        if (post == null || position == null)
            return;

        final RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        final int width = main.width;
        final int height = main.height;
        ensureTargets(width, height);

        final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        final Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrix());
        final Matrix4f modelView = new Matrix4f(view).mul(local);

        blit(main, scene, GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, width, height);
        mask.bindWrite(true);
        GlStateManager._clearColor(0f, 0f, 0f, 0f);
        GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
        blit(main, mask, GL11.GL_DEPTH_BUFFER_BIT, width, height);
        mask.bindWrite(true);

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.enableCull();
        GL11.glEnable(GL32.GL_DEPTH_CLAMP);

        volumes.bind();
        RenderSystem.setShaderColor(1f / 255f, 0f, 0f, 1f);
        GL11.glCullFace(GL11.GL_BACK);
        volumes.drawWithShader(modelView, projection, position);
        RenderSystem.setShaderColor(0f, 1f / 255f, 0f, 1f);
        GL11.glCullFace(GL11.GL_FRONT);
        volumes.drawWithShader(modelView, projection, position);
        VertexBuffer.unbind();

        GL11.glCullFace(GL11.GL_BACK);
        GL11.glDisable(GL32.GL_DEPTH_CLAMP);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();

        main.bindWrite(true);

        post.setSampler("SceneColor", scene.getColorTextureId());
        post.setSampler("SceneDepth", scene.getDepthTextureId());
        post.setSampler("Mask", mask.getColorTextureId());
        post.safeGetUniform("InvViewProj").set(new Matrix4f(projection).mul(view).invert());
        post.safeGetUniform("SceneToLocal").set(new Matrix4f(local).invert());
        post.safeGetUniform("Reveal").set(reveal);
        post.safeGetUniform("Erase").set(erase);
        post.safeGetUniform("EraseFromFar").set(eraseFromFar ? 1f : 0f);
        post.safeGetUniform("Softness").set(SOFTNESS);
        post.safeGetUniform("Reach").set(this.reach);
        post.safeGetUniform("Exposure").set(DIAGRAM_EXPOSURE / Mth.clamp(daylight, 0.25f, 1f));

        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();

        final BufferBuilder quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        quad.addVertex(-1f, -1f, 0f);
        quad.addVertex(1f, -1f, 0f);
        quad.addVertex(1f, 1f, 0f);
        quad.addVertex(-1f, 1f, 0f);
        RenderSystem.setShader(() -> post);
        BufferUploader.drawWithShader(quad.buildOrThrow());

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
    }

    private static void ensureTargets(final int width, final int height) {
        if (scene == null || mask == null) {
            scene = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            mask = new TextureTarget(width, height, true, Minecraft.ON_OSX);
        } else if (scene.width != width || scene.height != height) {
            scene.resize(width, height, Minecraft.ON_OSX);
            mask.resize(width, height, Minecraft.ON_OSX);
        }
    }

    private static void blit(final RenderTarget from, final RenderTarget to, final int buffers, final int width, final int height) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, width, height, 0, 0, width, height, buffers, GL11.GL_NEAREST);
    }

    @Nullable
    private VertexBuffer build(final BlockGetter blocks) {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        final ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 16);

        try {
            final BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);

            for (int block = 0; block < this.shape.size; block++) {
                pos.set(this.shape.position(block));
                final BlockState state = blocks.getBlockState(pos);
                if (state.isAir() || !this.canBeSeen(blocks, block, pos, neighbour))
                    continue;

                final float grow = state.hasBlockEntity() ? BLOCK_ENTITY_GROW : BLOCK_GROW;
                box(builder, this.shape.x[block], this.shape.y[block], this.shape.z[block], grow);
            }

            final MeshData mesh = builder.build();
            if (mesh == null)
                return null;

            final VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(mesh);
            VertexBuffer.unbind();
            return buffer;
        } finally {
            bytes.close();
        }
    }

    private boolean canBeSeen(final BlockGetter blocks, final int block, final BlockPos pos, final BlockPos.MutableBlockPos neighbour) {
        if (this.shape.faces[block] != 0)
            return true;

        for (final Direction direction : Direction.values()) {
            neighbour.setWithOffset(pos, direction);
            if (!blocks.getBlockState(neighbour).isSolidRender(blocks, neighbour))
                return true;
        }
        return false;
    }

    private static void box(final BufferBuilder builder, final int bx, final int by, final int bz, final float grow) {
        final float x0 = bx - grow;
        final float y0 = by - grow;
        final float z0 = bz - grow;
        final float x1 = bx + 1 + grow;
        final float y1 = by + 1 + grow;
        final float z1 = bz + 1 + grow;

        quad(builder, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(builder, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        quad(builder, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(builder, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        quad(builder, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(builder, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
    }

    private static void quad(final BufferBuilder builder,
                             final float ax, final float ay, final float az, final float bx, final float by, final float bz,
                             final float cx, final float cy, final float cz, final float dx, final float dy, final float dz) {
        builder.addVertex(ax, ay, az);
        builder.addVertex(bx, by, bz);
        builder.addVertex(cx, cy, cz);
        builder.addVertex(dx, dy, dz);
    }

    @Override
    public void close() {
        if (this.volumes != null) {
            this.volumes.close();
            this.volumes = null;
        }
    }
}
