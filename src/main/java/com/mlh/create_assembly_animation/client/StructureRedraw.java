package com.mlh.create_assembly_animation.client;

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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.Map;
import java.lang.reflect.Method;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import dev.ryanhcode.sable.api.block.propeller.BlockEntityPropeller;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

import java.util.function.Consumer;

final class StructureRedraw implements AutoCloseable {

    @Nullable
    private static TextureTarget scene;
    @Nullable
    private static TextureTarget mask;

    private final GlowShape shape;

    @Nullable
    private VertexBuffer volumes;
    private boolean built;

    StructureRedraw(final GlowShape shape) {
        this.shape = shape;
    }

    void draw(final BlockGetter blocks, final Matrix4f local, final ShaderInstance post,
              final Consumer<ShaderInstance> uniforms) {
        this.draw(blocks, local, post, false, uniforms);
    }

    void draw(final BlockGetter blocks, final Matrix4f local, final ShaderInstance post, final boolean depth,
              final Consumer<ShaderInstance> uniforms) {
        if (!this.built) {
            this.built = true;
            this.volumes = this.build(blocks);
        }
        if (this.volumes == null)
            return;

        final ShaderInstance position = GameRenderer.getPositionShader();
        if (position == null)
            return;

        final RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        final int width = main.width;
        final int height = main.height;
        ensureTargets(main);

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

        this.volumes.bind();
        RenderSystem.setShaderColor(1f / 255f, 0f, 0f, 1f);
        GL11.glCullFace(GL11.GL_BACK);
        this.volumes.drawWithShader(modelView, projection, position);
        RenderSystem.setShaderColor(0f, 1f / 255f, 0f, 1f);
        GL11.glCullFace(GL11.GL_FRONT);
        this.volumes.drawWithShader(modelView, projection, position);
        VertexBuffer.unbind();

        GL11.glCullFace(GL11.GL_BACK);
        GL11.glDisable(GL32.GL_DEPTH_CLAMP);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        main.bindWrite(true);

        post.setSampler("SceneColor", scene.getColorTextureId());
        post.setSampler("SceneDepth", scene.getDepthTextureId());
        post.setSampler("Mask", mask.getColorTextureId());
        post.safeGetUniform("InvViewProj").set(new Matrix4f(projection).mul(view).invert());
        post.safeGetUniform("SceneToLocal").set(new Matrix4f(local).invert());
        post.safeGetUniform("LocalToClip").set(new Matrix4f(projection).mul(modelView));
        uniforms.accept(post);

        RenderSystem.defaultBlendFunc();
        if (depth) {
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_ALWAYS);
            RenderSystem.depthMask(true);
        } else {
            RenderSystem.disableDepthTest();
        }
        RenderSystem.disableCull();

        final BufferBuilder quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        quad.addVertex(-1f, -1f, 0f);
        quad.addVertex(1f, -1f, 0f);
        quad.addVertex(1f, 1f, 0f);
        quad.addVertex(-1f, 1f, 0f);
        RenderSystem.setShader(() -> post);
        BufferUploader.drawWithShader(quad.buildOrThrow());

        RenderSystem.disableBlend();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
    }

    private static void ensureTargets(final RenderTarget main) {
        final int width = main.width;
        final int height = main.height;
        if (scene != null && scene.isStencilEnabled() != main.isStencilEnabled()) {
            scene.destroyBuffers();
            mask.destroyBuffers();
            scene = null;
            mask = null;
        }

        if (scene == null || mask == null) {
            scene = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            mask = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            if (main.isStencilEnabled()) {
                scene.enableStencil();
                mask.enableStencil();
            }
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

                final AABB bounds = Extents.of(blocks, pos, state);
                final BlockPos origin = this.shape.origin;
                box(builder, (float) (bounds.minX - origin.getX()), (float) (bounds.minY - origin.getY()),
                        (float) (bounds.minZ - origin.getZ()), (float) (bounds.maxX - origin.getX()),
                        (float) (bounds.maxY - origin.getY()), (float) (bounds.maxZ - origin.getZ()));
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

    private static void box(final BufferBuilder builder, final float x0, final float y0, final float z0,
                            final float x1, final float y1, final float z1) {
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

    static final class Extents {
        private static final float GROW = 0.12f;
        private static final float BLOCK_ENTITY_GROW = 0.3f;
        private static final double MAX_REACH = 4.0;
        private static final float BLADE_MARGIN = 0.3f;

        private static final Map<Class<?>, Optional<Method>> RADIUS = new ConcurrentHashMap<>();
        private static final Map<Class<?>, Optional<Method>> OFFSET = new ConcurrentHashMap<>();

        static AABB of(final BlockGetter blocks, final BlockPos pos, final BlockState state) {
            if (!state.hasBlockEntity())
                return new AABB(pos).inflate(GROW);

            AABB bounds = new AABB(pos).inflate(BLOCK_ENTITY_GROW);
            final BlockEntity entity = entity(blocks, pos, state);
            if (entity == null)
                return bounds;

            try {
                final BlockEntityRenderer<BlockEntity> renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher()
                        .getRenderer(entity);
                final AABB render = renderer == null ? null : renderer.getRenderBoundingBox(entity);
                if (render != null && render.getXsize() <= MAX_REACH * 2 + 1 && render.getYsize() <= MAX_REACH * 2 + 1
                        && render.getZsize() <= MAX_REACH * 2 + 1)
                    bounds = bounds.minmax(render);
            } catch (final RuntimeException ignored) {
            }

            if (entity instanceof final BlockEntityPropeller propeller) {
                try {
                    bounds = bounds.minmax(blades(entity, propeller, pos));
                } catch (final RuntimeException ignored) {
                }
            }
            return bounds;
        }

        private static AABB blades(final BlockEntity entity, final BlockEntityPropeller propeller, final BlockPos pos) {
            final Direction facing = propeller.getBlockDirection();
            final float radius = Math.min((float) MAX_REACH, Math.max(0.5f, number(RADIUS, entity, "getRadius", 1.5f)));
            final float offset = Math.min((float) MAX_REACH, Math.max(0f, number(OFFSET, entity, "getOffset", 0.25f)));

            final double spread = radius + BLADE_MARGIN;
            final double reach = 0.5 + offset + BLADE_MARGIN;
            final double cx = pos.getX() + 0.5;
            final double cy = pos.getY() + 0.5;
            final double cz = pos.getZ() + 0.5;
            final Direction.Axis axis = facing.getAxis();
            final double sign = facing.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 : -1.0;

            final double minX = axis == Direction.Axis.X ? Math.min(cx, cx + sign * reach) : cx - spread;
            final double maxX = axis == Direction.Axis.X ? Math.max(cx, cx + sign * reach) : cx + spread;
            final double minY = axis == Direction.Axis.Y ? Math.min(cy, cy + sign * reach) : cy - spread;
            final double maxY = axis == Direction.Axis.Y ? Math.max(cy, cy + sign * reach) : cy + spread;
            final double minZ = axis == Direction.Axis.Z ? Math.min(cz, cz + sign * reach) : cz - spread;
            final double maxZ = axis == Direction.Axis.Z ? Math.max(cz, cz + sign * reach) : cz + spread;
            return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }

        @Nullable
        private static BlockEntity entity(final BlockGetter blocks, final BlockPos pos, final BlockState state) {
            final BlockEntity placed = blocks.getBlockEntity(pos);
            if (placed != null)
                return placed;
            if (!(state.getBlock() instanceof final EntityBlock block))
                return null;
            try {
                return block.newBlockEntity(pos, state);
            } catch (final RuntimeException e) {
                return null;
            }
        }

        private static float number(final Map<Class<?>, Optional<Method>> cache, final Object target, final String name,
                                    final float fallback) {
            final Optional<Method> method = cache.computeIfAbsent(target.getClass(), type -> {
                try {
                    final Method found = type.getMethod(name);
                    final Class<?> result = found.getReturnType();
                    return result == float.class || result == double.class ? Optional.of(found) : Optional.empty();
                } catch (final NoSuchMethodException | SecurityException e) {
                    return Optional.empty();
                }
            });
            if (method.isEmpty())
                return fallback;
            try {
                return ((Number) method.get().invoke(target)).floatValue();
            } catch (final ReflectiveOperationException | RuntimeException e) {
                return fallback;
            }
        }
    }
}
