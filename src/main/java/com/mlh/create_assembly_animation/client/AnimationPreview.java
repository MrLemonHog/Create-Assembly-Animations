package com.mlh.create_assembly_animation.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import static com.mlh.create_assembly_animation.client.ShipAnimation.clamp01;

public final class AnimationPreview {

    private static final String[] SHIP = {
            "...A.....",
            "..LCC.P..",
            "OOOOOOOOO",
            ".SSSSSSS.",
            "..SSSSS..",
    };
    private static final int DEPTH = 3;

    private static final float PITCH = 24f;
    private static final float YAW = -32f;
    private static final float SCENE_Z = 150f;
    private static final float LEAD_TICKS = 8f;
    private static final float LOOP_GAP_TICKS = 10f;
    private static final float LEVER_TICKS = 3f;
    private static final float LEVER_FLICKED = 45f;
    private static final float PROPELLER_RPM = 64f;

    private static final ByteBufferBuilder GLOW_BYTES = new ByteBufferBuilder(1 << 16);
    private static final ByteBufferBuilder INK_BYTES = new ByteBufferBuilder(1 << 16);
    private static final ByteBufferBuilder OVERLAY_BYTES = new ByteBufferBuilder(1 << 12);
    private static final GlowCanvas CANVAS = new GlowCanvas();

    private static final Map<AnimationStyle, Map<Phase, ShipAnimation>> ANIMATIONS = new EnumMap<>(AnimationStyle.class);

    @Nullable
    private static Scene scene;

    private AnimationPreview() {
    }

    public static void invalidate() {
        ANIMATIONS.values().forEach(byPhase -> byPhase.values().forEach(ShipAnimation::close));
        ANIMATIONS.clear();
        scene = null;
    }

    public static void render(final GuiGraphics graphics, final AnimationStyle style, final Phase phase, final int x,
                              final int y, final int width, final int height, final float ticks) {
        final Scene scene = scene();
        final ShipAnimation animation = animation(style, phase, scene);
        final Matrix4f matrix = scene.fit(graphics.pose().last().pose(), x, y, width, height);
        final float pixelsPerBlock = matrix.getScale(new Vector3f()).x;

        final float step = animation.step();
        final float lifetime = animation.lifetime();
        final float cycle = lifetime + (LEAD_TICKS + LOOP_GAP_TICKS) * step;
        final float time = (ticks * step) % cycle - LEAD_TICKS * step;
        final float flick = time < lifetime ? clamp01((time + LEVER_TICKS * step) / (LEVER_TICKS * step))
                : 1f - clamp01((time - lifetime) / (LEVER_TICKS * step));

        graphics.flush();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        scene.drawBlocks(graphics, matrix, ticks, LEVER_FLICKED * ShipAnimation.smooth(flick));

        if (time >= 0f)
            draw(graphics, animation, scene, matrix, pixelsPerBlock, x + width / 2f, y + height / 2f, time);

        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
    }

    private static void draw(final GuiGraphics graphics, final ShipAnimation animation, final Scene scene,
                             final Matrix4f matrix, final float pixelsPerBlock, final float centerX,
                             final float centerY, final float time) {
        final Vector3f far = new Matrix4f(matrix).invert().transformPosition(centerX, centerY, 100_000f, new Vector3f());
        final Vector3d eye = new Vector3d(far.x, far.y, far.z);

        final BufferBuilder glow = new BufferBuilder(GLOW_BYTES, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        final BufferBuilder ink = new BufferBuilder(INK_BYTES, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        final BufferBuilder overlay = new BufferBuilder(OVERLAY_BYTES, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        animation.renderFrame(scene.blocks, 1f, matrix, eye, time, 1f, glow, ink, overlay, CANVAS,
                animation.style.settings().brightness().get().floatValue());

        final MeshData inkMesh = ink.build();
        if (inkMesh != null)
            RenderType.debugQuads().draw(inkMesh);

        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        final MeshData glowMesh = glow.build();
        if (glowMesh != null) {
            RenderSystem.depthMask(false);
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            BufferUploader.drawWithShader(glowMesh);
            RenderSystem.depthMask(true);
        }

        final MeshData overlayMesh = overlay.build();
        if (overlayMesh != null) {
            RenderSystem.disableDepthTest();
            RenderSystem.defaultBlendFunc();
            BufferUploader.drawWithShader(overlayMesh);
            RenderSystem.enableDepthTest();
        }

        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();

        final float labelScale = Mth.clamp(pixelsPerBlock * 0.05f, 0.35f, 0.6f);
        CANVAS.flushLabels(graphics.bufferSource(), new Matrix4f().scale(labelScale), Minecraft.getInstance().font);
    }

    private static Scene scene() {
        if (scene == null)
            scene = new Scene();
        return scene;
    }

    private static ShipAnimation animation(final AnimationStyle style, final Phase phase, final Scene scene) {
        final Map<Phase, ShipAnimation> byPhase = ANIMATIONS.computeIfAbsent(style, key -> new EnumMap<>(Phase.class));
        final ShipAnimation current = byPhase.get(phase);
        if (current != null && current.step() == style.settings().speed().get().floatValue())
            return current;

        if (current != null)
            current.close();
        final ShipAnimation animation = style.preview(phase, scene.shape);
        if (scene.forces != null)
            animation.acceptForces(scene.forces);
        byPhase.put(phase, animation);
        return animation;
    }

    private static final class Scene {

        private final Map<BlockPos, BlockState> states = new HashMap<>();
        private final BlockGetter blocks = new PreviewBlocks(this.states);
        private final GlowShape shape;
        @Nullable
        private final Forces forces;
        private final Matrix4f rotation = new Matrix4f().rotateX(PITCH * Mth.DEG_TO_RAD).rotateY(YAW * Mth.DEG_TO_RAD);
        private final float[] bounds = new float[4];

        private final Block propeller;
        @Nullable
        private final BakedModel lever;
        @Nullable
        private final BakedModel blades;

        Scene() {
            final BlockState assembler = onFloor(state("simulated:physics_assembler"), Direction.EAST);
            final BlockState casing = state("create:andesite_casing");
            final BlockState levitite = state("aeronautics:levitite");
            final BlockState propeller = facing(state("aeronautics:andesite_propeller"), Direction.UP);
            final BlockState deck = Blocks.OAK_PLANKS.defaultBlockState();
            final BlockState hull = Blocks.SPRUCE_PLANKS.defaultBlockState();

            BlockPos origin = BlockPos.ZERO;
            for (int row = 0; row < SHIP.length; row++) {
                final int y = SHIP.length - 1 - row;
                for (int column = 0; column < SHIP[row].length(); column++) {
                    final char block = SHIP[row].charAt(column);
                    if (block == '.')
                        continue;

                    final boolean superstructure = block != 'O' && block != 'S';
                    final BlockState state = switch (block) {
                        case 'A' -> assembler;
                        case 'C' -> casing;
                        case 'L' -> levitite;
                        case 'P' -> propeller;
                        case 'O' -> deck;
                        default -> hull;
                    };
                    for (int z = superstructure ? DEPTH / 2 : 0; z < (superstructure ? DEPTH / 2 + 1 : DEPTH); z++)
                        this.states.put(new BlockPos(column, y, z), state);
                    if (block == 'A')
                        origin = new BlockPos(column, y, DEPTH / 2);
                }
            }

            this.shape = GlowShape.of(origin, this.states.keySet().stream().mapToLong(BlockPos::asLong).toArray());
            this.forces = PreviewForces.measure(this.blocks, this.states.keySet(), PROPELLER_RPM);
            this.propeller = propeller.getBlock();
            this.lever = standalone("simulated:block/physics_assembler/lever");
            this.blades = standalone("aeronautics:block/andesite_propeller/propeller");

            final float minX = this.shape.minX - 0.5f;
            final float maxX = this.shape.maxX + 3f;
            final float minY = this.shape.minY;
            final float maxY = this.shape.maxY + 3.2f;
            final float minZ = this.shape.minZ - 0.5f;
            final float maxZ = this.shape.maxZ + 3f;
            this.bounds[0] = Float.MAX_VALUE;
            this.bounds[1] = Float.MAX_VALUE;
            this.bounds[2] = -Float.MAX_VALUE;
            this.bounds[3] = -Float.MAX_VALUE;
            final Vector3f corner = new Vector3f();
            for (int i = 0; i < 8; i++) {
                this.rotation.transformPosition((i & 1) == 0 ? minX : maxX, (i & 2) == 0 ? minY : maxY,
                        (i & 4) == 0 ? minZ : maxZ, corner);
                this.bounds[0] = Math.min(this.bounds[0], corner.x);
                this.bounds[1] = Math.min(this.bounds[1], corner.y);
                this.bounds[2] = Math.max(this.bounds[2], corner.x);
                this.bounds[3] = Math.max(this.bounds[3], corner.y);
            }
        }

        Matrix4f fit(final Matrix4f pose, final int x, final int y, final int width, final int height) {
            final float spanX = this.bounds[2] - this.bounds[0];
            final float spanY = this.bounds[3] - this.bounds[1];
            final float scale = Math.max(1f, Math.min((width - 8) / spanX, (height - 8) / spanY));
            final float centerX = (this.bounds[0] + this.bounds[2]) / 2f;
            final float centerY = (this.bounds[1] + this.bounds[3]) / 2f;

            return new Matrix4f(pose)
                    .translate(x + width / 2f, y + height / 2f, SCENE_Z)
                    .scale(scale, -scale, scale)
                    .translate(-centerX, -centerY, 0f)
                    .mul(this.rotation);
        }

        void drawBlocks(final GuiGraphics graphics, final Matrix4f matrix, final float ticks, final float leverAngle) {
            final BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
            final BlockPos origin = this.shape.origin;
            final PoseStack pose = new PoseStack();
            pose.mulPose(matrix);

            Lighting.setupFor3DItems();
            for (final Map.Entry<BlockPos, BlockState> entry : this.states.entrySet()) {
                final BlockPos pos = entry.getKey();
                final BlockState state = entry.getValue();
                pose.pushPose();
                pose.translate(pos.getX() - origin.getX(), pos.getY() - origin.getY(), pos.getZ() - origin.getZ());
                dispatcher.renderSingleBlock(state, pose, graphics.bufferSource(), LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY);

                if (pos.equals(origin) && this.lever != null) {
                    pose.rotateAround(Axis.YP.rotationDegrees(-yRotation(state)), 0.5f, 0f, 0.5f);
                    pose.rotateAround(Axis.XP.rotationDegrees(leverAngle), 0.5f, 7 / 16f, 0.5f);
                    part(dispatcher, graphics, pose, state, this.lever);
                } else if (state.getBlock() == this.propeller && this.blades != null) {
                    pose.rotateAround(Axis.YP.rotationDegrees(ticks * PROPELLER_RPM * 0.6f), 0.5f, 0f, 0.5f);
                    part(dispatcher, graphics, pose, state, this.blades);
                }
                pose.popPose();
            }
            graphics.flush();
        }

        private static void part(final BlockRenderDispatcher dispatcher, final GuiGraphics graphics,
                                 final PoseStack pose, final BlockState state, final BakedModel model) {
            dispatcher.getModelRenderer().renderModel(pose.last(),
                    graphics.bufferSource().getBuffer(Sheets.cutoutBlockSheet()), state, model, 1f, 1f, 1f,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        }

        private static float yRotation(final BlockState state) {
            if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
                return 0f;
            return switch (state.getValue(BlockStateProperties.HORIZONTAL_FACING)) {
                case EAST -> 90f;
                case SOUTH -> 180f;
                case WEST -> 270f;
                default -> 0f;
            };
        }

        @Nullable
        private static BakedModel standalone(final String id) {
            final ModelManager models = Minecraft.getInstance().getModelManager();
            final BakedModel model = models.getModel(ModelResourceLocation.standalone(ResourceLocation.parse(id)));
            return model == models.getMissingModel() ? null : model;
        }

        private static BlockState onFloor(final BlockState state, final Direction facing) {
            BlockState placed = state;
            if (placed.hasProperty(BlockStateProperties.ATTACH_FACE))
                placed = placed.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR);
            if (placed.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
                placed = placed.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            return placed;
        }

        private static BlockState facing(final BlockState state, final Direction facing) {
            return state.hasProperty(BlockStateProperties.FACING) ? state.setValue(BlockStateProperties.FACING, facing) : state;
        }

        private static BlockState state(final String id) {
            final Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
            return (block == Blocks.AIR ? Blocks.OAK_PLANKS : block).defaultBlockState();
        }
    }

    private record PreviewBlocks(Map<BlockPos, BlockState> states) implements BlockGetter {

        @Nullable
        @Override
        public BlockEntity getBlockEntity(final BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(final BlockPos pos) {
            return this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(final BlockPos pos) {
            return this.getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 64;
        }

        @Override
        public int getMinBuildHeight() {
            return -32;
        }
    }
}
