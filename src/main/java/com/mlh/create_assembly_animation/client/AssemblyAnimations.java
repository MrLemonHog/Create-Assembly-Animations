package com.mlh.create_assembly_animation.client;

import com.mlh.create_assembly_animation.CreateAssemblyAnimation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@EventBusSubscriber(modid = CreateAssemblyAnimation.ID, value = Dist.CLIENT)
public final class AssemblyAnimations {

    private static final List<ShipAnimation> ACTIVE = new ArrayList<>();
    private static final GlowCanvas CANVAS = new GlowCanvas();

    private static final ByteBufferBuilder INK_BYTES = new ByteBufferBuilder(1 << 18);
    private static final ByteBufferBuilder OVERLAY_BYTES = new ByteBufferBuilder(1 << 14);

    @Nullable
    private static ClientLevel trackedLevel;

    private AssemblyAnimations() {
    }

    public static void onLeverPulled(final BlockPos assembler) {
        charge(assembler);
    }

    static void charge(final BlockPos assembler) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null || findCharge(assembler) != null)
            return;

        final SubLevel subLevel = Sable.HELPER.getContaining(level, assembler);
        final Phase phase = subLevel != null ? Phase.DISASSEMBLY : Phase.ASSEMBLY;
        if (phase.enabled())
            ACTIVE.add(new Charge(subLevel != null ? subLevel.getUniqueId() : null, assembler, phase.style().chargeColor()));
    }

    static void assembled(final BlockPos trigger, final BlockPos origin, final UUID subLevel, final long[] blocks) {
        if (!Phase.ASSEMBLY.enabled())
            return;

        final ShipAnimation assembly = Phase.ASSEMBLY.style().assemble(subLevel, GlowShape.of(origin, blocks));

        Charge charge = findCharge(trigger);
        if (charge == null) {
            charge = new Charge(null, trigger, Phase.ASSEMBLY.style().chargeColor());
            ACTIVE.add(charge);
        }
        charge.handOffTo(assembly);
        ACTIVE.add(assembly);
    }

    static void aligning(final BlockPos assembler, final UUID subLevel, final long[] blocks) {
        if (!Phase.DISASSEMBLY.enabled())
            return;

        final ShipAnimation alignment = Phase.DISASSEMBLY.style().align(subLevel, GlowShape.of(assembler, blocks));
        handOff(alignment, assembler, subLevel);
        ACTIVE.add(alignment);
    }

    static void disassembled(final BlockPos trigger, final BlockPos origin, final UUID subLevel, final long[] blocks,
                             @Nullable final Forces forces) {
        if (!Phase.DISASSEMBLY.enabled())
            return;

        final ShipAnimation disassembly = Phase.DISASSEMBLY.style().disassemble(GlowShape.of(origin, blocks));
        if (forces != null)
            disassembly.acceptForces(forces);

        handOff(disassembly, trigger, subLevel);
        ACTIVE.add(disassembly);
    }

    static void failed(final BlockPos trigger, @Nullable final UUID subLevel) {
        if (!(subLevel != null ? Phase.DISASSEMBLY : Phase.ASSEMBLY).enabled())
            return;

        boolean replaced = false;
        for (int i = 0; i < ACTIVE.size(); i++) {
            final ShipAnimation animation = ACTIVE.get(i);
            if (animation.isAlignmentOf(subLevel) || (animation.isChargeAt(trigger) && !animation.hasSuccessor())) {
                animation.close();
                ACTIVE.set(i, new Fail(animation.subLevelId, animation.shape));
                replaced = true;
            }
        }

        if (!replaced)
            ACTIVE.add(new Fail(subLevel, GlowShape.single(trigger)));
    }

    static void receiveForces(final UUID subLevel, final Forces forces) {
        for (final ShipAnimation animation : ACTIVE) {
            if (animation.role != ShipAnimation.Role.DISASSEMBLE && subLevel.equals(animation.subLevelId))
                animation.acceptForces(forces);
        }
    }

    private static void handOff(final ShipAnimation next, final BlockPos trigger, @Nullable final UUID subLevel) {
        for (final ShipAnimation animation : ACTIVE) {
            if (animation.isAlignmentOf(subLevel) || (animation.isChargeAt(trigger) && !animation.hasSuccessor()))
                animation.handOffTo(next);
        }
    }

    @Nullable
    private static Charge findCharge(final BlockPos assembler) {
        for (final ShipAnimation animation : ACTIVE) {
            if (animation instanceof final Charge charge && charge.isChargeAt(assembler)
                    && !charge.hasSuccessor() && !charge.isFinished())
                return charge;
        }
        return null;
    }

    @SubscribeEvent
    public static void tick(final ClientTickEvent.Post event) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != trackedLevel) {
            ACTIVE.forEach(ShipAnimation::close);
            ACTIVE.clear();
            ClientAssemblyTracker.clear();
            Forces.clear();
            trackedLevel = minecraft.level;
        }

        if (minecraft.level == null || minecraft.isPaused())
            return;

        ClientAssemblyTracker.tick(minecraft.level);
        Forces.tick(minecraft.level);

        for (final ShipAnimation animation : ACTIVE)
            animation.tick(minecraft.level);

        ACTIVE.removeIf(animation -> {
            if (!animation.isFinished())
                return false;
            animation.close();
            return true;
        });
    }

    @SubscribeEvent
    public static void render(final RenderLevelStageEvent event) {
        final boolean shaderPack = ShaderPacks.inUse();
        final RenderLevelStageEvent.Stage stage = shaderPack
                ? RenderLevelStageEvent.Stage.AFTER_LEVEL : RenderLevelStageEvent.Stage.AFTER_PARTICLES;
        if (event.getStage() != stage || ACTIVE.isEmpty())
            return;

        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null)
            return;

        if (!shaderPack) {
            draw(event, minecraft, minecraft.level);
            return;
        }

        minecraft.getMainRenderTarget().bindWrite(true);
        final Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(event.getProjectionMatrix(), VertexSorting.DISTANCE_TO_ORIGIN);
        try {
            draw(event, minecraft, minecraft.level);
        } finally {
            RenderSystem.restoreProjectionMatrix();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void draw(final RenderLevelStageEvent event, final Minecraft minecraft, final ClientLevel level) {
        final float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        final Vec3 camera = event.getCamera().getPosition();

        final MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        final VertexConsumer glow = buffers.getBuffer(RenderType.lightning());
        final BufferBuilder ink = new BufferBuilder(INK_BYTES, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        final BufferBuilder overlay = new BufferBuilder(OVERLAY_BYTES, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        final PoseStack poseStack = new PoseStack();

        for (final ShipAnimation animation : ACTIVE)
            animation.render(level, poseStack, glow, ink, overlay, CANVAS, camera, partialTick,
                    animation.style.settings().brightness().get().floatValue());

        final MeshData inkMesh = ink.build();
        if (inkMesh != null)
            RenderType.debugQuads().draw(inkMesh);

        buffers.endBatch(RenderType.lightning());

        final MeshData overlayMesh = overlay.build();
        if (overlayMesh != null) {
            RenderSystem.disableDepthTest();
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferUploader.drawWithShader(overlayMesh);
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
        }

        CANVAS.flushLabels(buffers, event.getCamera().rotation(), minecraft.font);
    }

    private static final class Charge extends ShipAnimation {

        private static final int MAX_TICKS = 20 * 10;

        private final BlockPos assembler;
        @Nullable
        private final float[] color;

        Charge(@Nullable final UUID subLevelId, final BlockPos assembler, @Nullable final float[] color) {
            super(Role.CHARGE, subLevelId, GlowShape.single(assembler));
            this.assembler = assembler.immutable();
            this.color = color;
        }

        @Override
        boolean isChargeAt(final BlockPos assembler) {
            return this.assembler.equals(assembler);
        }

        @Override
        protected float lifetime() {
            return MAX_TICKS * this.step();
        }

        @Override
        protected void draw(final GlowCanvas canvas, final float time) {
            if (this.color == null)
                return;

            final float pulse = 0.5f + 0.5f * Mth.sin(time * 0.45f);
            canvas.faces(0, 0, 0, ALL_FACES, 0.02f + 0.04f * pulse, this.color[0], this.color[1], this.color[2],
                    (0.26f + 0.16f * pulse) * smooth(time / 5f));
        }
    }

    private static final class Fail extends ShipAnimation {

        private static final float[] RED = {1.00f, 0.26f, 0.20f};
        private static final int TICKS = 16;
        private static final int SMOKE = 10;

        Fail(@Nullable final UUID subLevelId, final GlowShape shape) {
            super(Role.FAIL, subLevelId, shape);
        }

        @Override
        protected float lifetime() {
            return TICKS * this.step();
        }

        @Override
        protected void draw(final GlowCanvas canvas, final float time) {
            final float ticks = time / this.step();
            final float flicker = ticks < 8f ? 0.55f + 0.45f * Math.abs(Mth.sin(ticks * 1.9f)) : 1f;
            final float intensity = 0.55f * (float) Math.exp(-ticks / 6f) * flicker;

            for (final int block : this.shape.shell)
                canvas.faces(this.shape.x[block], this.shape.y[block], this.shape.z[block], this.shape.faces[block],
                        0.01f, RED[0], RED[1], RED[2], intensity);
        }

        @Override
        protected void onStart(final ClientLevel level, @Nullable final Pose3dc pose) {
            this.sound(level, pose, SoundEvents.FIRE_EXTINGUISH, 0.3f, 1.6f);

            final int[] shell = this.shape.shell;
            for (int i = 0; i < SMOKE && shell.length > 0; i++)
                this.faceParticle(level, pose, shell[this.random.nextInt(shell.length)], ParticleTypes.SMOKE, 0.02);
        }
    }
}
