package com.mlh.create_assembly_animation.client;

import com.mlh.create_assembly_animation.AAConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.UUID;

abstract class ShipAnimation {

    enum Role {
        CHARGE,
        ASSEMBLE,
        ALIGN,
        DISASSEMBLE,
        FAIL
    }

    protected static final int FADE_TICKS = 6;
    protected static final int ALL_FACES = 0x3F;
    protected static final int SIDE_FACES = (1 << Direction.NORTH.ordinal()) | (1 << Direction.SOUTH.ordinal())
            | (1 << Direction.WEST.ordinal()) | (1 << Direction.EAST.ordinal());

    private static final int MAX_WAIT_TICKS = 100;

    final Role role;
    @Nullable
    final UUID subLevelId;
    final GlowShape shape;
    final AnimationStyle style;

    protected final RandomSource random = RandomSource.create();
    protected final float[] rgb = new float[3];

    private final float playbackSpeed;

    private boolean started;
    private boolean finished;
    private int waited;
    protected int age;
    private int fadeStart = -1;

    @Nullable
    private ShipAnimation successor;

    ShipAnimation(final Role role, @Nullable final UUID subLevelId, final GlowShape shape) {
        this.role = role;
        this.subLevelId = subLevelId;
        this.shape = shape;
        this.style = Phase.of(role, subLevelId).style();
        this.playbackSpeed = this.style.settings().speed().get().floatValue();
    }

    protected abstract float lifetime();

    protected abstract void draw(GlowCanvas canvas, float time);

    protected void onStart(final ClientLevel level, @Nullable final Pose3dc pose) {
    }

    protected void onTick(final ClientLevel level, @Nullable final Pose3dc pose, final float time) {
    }

    protected void drawDirect(final ClientLevel level, final Matrix4f matrix, final float time, final float fade) {
    }

    void acceptForces(final Forces forces) {
    }

    void close() {
    }

    protected final float step() {
        return this.playbackSpeed;
    }

    protected final boolean crossed(final float time, final float moment) {
        return time - this.playbackSpeed < moment && moment <= time;
    }

    boolean isChargeAt(final BlockPos assembler) {
        return false;
    }

    final boolean isAlignmentOf(@Nullable final UUID subLevel) {
        return this.role == Role.ALIGN && this.subLevelId != null && this.subLevelId.equals(subLevel);
    }

    final boolean isFinished() {
        return this.finished;
    }

    final boolean hasSuccessor() {
        return this.successor != null;
    }

    final void handOffTo(final ShipAnimation next) {
        if (this.successor == null) {
            this.successor = next;
            next.follow(this);
        }
    }

    void follow(final ShipAnimation previous) {
    }

    final void fadeOut() {
        if (this.fadeStart < 0)
            this.fadeStart = this.age;
    }

    final void tick(final ClientLevel level) {
        if (this.finished)
            return;

        final ClientSubLevel subLevel = this.subLevel(level);
        if (this.subLevelId != null && subLevel == null && this.started) {
            this.finished = true;
            return;
        }
        final Pose3dc pose = subLevel != null ? subLevel.logicalPose() : null;

        if (!this.started) {
            final boolean spaceReady = this.subLevelId == null || (subLevel != null && subLevel.isFinalized());
            final boolean waitsForBlocks = this.role == Role.ASSEMBLE || this.role == Role.DISASSEMBLE;
            final boolean ready = spaceReady && (!waitsForBlocks || this.shape.readiness(level) >= 0.85f);

            if (!ready && ++this.waited < MAX_WAIT_TICKS)
                return;
            if (!spaceReady) {
                this.finished = true;
                return;
            }

            this.started = true;
            this.onStart(level, pose);
        } else {
            this.age++;
        }

        if (this.successor != null && (this.successor.started || this.successor.finished))
            this.fadeOut();

        final float time = this.age * this.playbackSpeed;
        if ((this.fadeStart >= 0 && this.age - this.fadeStart >= FADE_TICKS) || time >= this.lifetime()) {
            this.finished = true;
            return;
        }

        this.onTick(level, pose, time);
    }

    final void render(final ClientLevel level, final PoseStack poseStack, final VertexConsumer glow,
                      final VertexConsumer ink, final VertexConsumer overlay, final GlowCanvas canvas,
                      final Vec3 camera, final float partialTick, final float brightness) {
        if (!this.started || this.finished)
            return;

        final Pose3dc pose;
        if (this.subLevelId == null) {
            pose = null;
        } else {
            final ClientSubLevel subLevel = this.subLevel(level);
            if (subLevel == null)
                return;
            pose = subLevel.renderPose(partialTick);
        }

        final float ticks = this.age + partialTick;
        final float fade = this.fadeStart < 0 ? 1f : 1f - clamp01((ticks - this.fadeStart) / FADE_TICKS);
        if (fade <= 0f)
            return;

        final BlockPos origin = this.shape.origin;
        poseStack.pushPose();

        if (pose == null) {
            poseStack.translate(origin.getX() - camera.x, origin.getY() - camera.y, origin.getZ() - camera.z);
        } else {
            final Vector3dc position = pose.position();
            final Vector3dc scale = pose.scale();
            final Vector3dc rotationPoint = pose.rotationPoint();
            poseStack.translate(position.x() - camera.x, position.y() - camera.y, position.z() - camera.z);
            poseStack.mulPose(new Quaternionf(pose.orientation()));
            poseStack.scale((float) scale.x(), (float) scale.y(), (float) scale.z());
            poseStack.translate(origin.getX() - rotationPoint.x(), origin.getY() - rotationPoint.y(),
                    origin.getZ() - rotationPoint.z());
        }

        final Vector3d eye = new Vector3d(camera.x, camera.y, camera.z);
        if (pose != null)
            pose.transformPositionInverse(eye);
        eye.sub(origin.getX(), origin.getY(), origin.getZ());

        final Matrix4f matrix = poseStack.last().pose();
        final float time = ticks * this.playbackSpeed;
        this.drawDirect(level, matrix, time, fade);

        canvas.begin(glow, ink, overlay, matrix, fade * brightness, fade, eye.x, eye.y, eye.z);
        this.draw(canvas, time);

        poseStack.popPose();
    }

    @Nullable
    private ClientSubLevel subLevel(final ClientLevel level) {
        if (this.subLevelId == null)
            return null;

        final ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null)
            return null;

        final SubLevel subLevel = container.getSubLevel(this.subLevelId);
        return subLevel instanceof final ClientSubLevel client && !client.isRemoved() ? client : null;
    }

    protected final void copy(final float[] color) {
        System.arraycopy(color, 0, this.rgb, 0, 3);
    }

    protected final void mix(final float[] from, final float[] to, final float t) {
        final float c = clamp01(t);
        this.rgb[0] = Mth.lerp(c, from[0], to[0]);
        this.rgb[1] = Mth.lerp(c, from[1], to[1]);
        this.rgb[2] = Mth.lerp(c, from[2], to[2]);
    }

    @Nullable
    protected final Direction randomExposedFace(final int block, final int allowed) {
        final int faces = this.shape.faces[block] & allowed;
        if (faces == 0)
            return null;

        int pick = this.random.nextInt(Integer.bitCount(faces));
        for (final Direction direction : Direction.values()) {
            if ((faces & (1 << direction.ordinal())) != 0 && pick-- == 0)
                return direction;
        }
        return null;
    }

    protected final void faceParticle(final ClientLevel level, @Nullable final Pose3dc pose, final int block,
                                      final ParticleOptions type, final double speed) {
        final Direction face = this.randomExposedFace(block, ALL_FACES);
        if (face == null)
            return;

        final double nx = face.getStepX();
        final double ny = face.getStepY();
        final double nz = face.getStepZ();

        this.particle(level, pose, type,
                this.shape.x[block] + 0.5 + nx * 0.6 + (nx == 0 ? (this.random.nextDouble() - 0.5) * 0.9 : 0),
                this.shape.y[block] + 0.5 + ny * 0.6 + (ny == 0 ? (this.random.nextDouble() - 0.5) * 0.9 : 0),
                this.shape.z[block] + 0.5 + nz * 0.6 + (nz == 0 ? (this.random.nextDouble() - 0.5) * 0.9 : 0),
                nx * speed, ny * speed, nz * speed);
    }

    protected final void planeParticles(final ClientLevel level, @Nullable final Pose3dc pose, final float plane,
                                        final int budget, final ParticleOptions type, final double speed) {
        int candidates = 0;
        for (final int block : this.shape.shell) {
            final float inside = plane - this.shape.y[block];
            if (inside >= 0f && inside < 1f && (this.shape.faces[block] & SIDE_FACES) != 0)
                candidates++;
        }
        if (candidates == 0)
            return;

        for (final int block : this.shape.shell) {
            final float inside = plane - this.shape.y[block];
            if (inside < 0f || inside >= 1f || this.random.nextInt(candidates) >= budget)
                continue;

            final Direction side = this.randomExposedFace(block, SIDE_FACES);
            if (side == null)
                continue;

            final double sx = side.getStepX();
            final double sz = side.getStepZ();
            this.particle(level, pose, type,
                    this.shape.x[block] + 0.5 + sx * 0.6 + (sx == 0 ? (this.random.nextDouble() - 0.5) * 0.9 : 0),
                    plane,
                    this.shape.z[block] + 0.5 + sz * 0.6 + (sz == 0 ? (this.random.nextDouble() - 0.5) * 0.9 : 0),
                    sx * speed, 0.0, sz * speed);
        }
    }

    protected final void particle(final ClientLevel level, @Nullable final Pose3dc pose, final ParticleOptions type,
                                  final double x, final double y, final double z,
                                  final double vx, final double vy, final double vz) {
        if (!AAConfig.PARTICLES.get())
            return;

        final BlockPos origin = this.shape.origin;
        final Vector3d at = new Vector3d(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
        final Vector3d velocity = new Vector3d(vx, vy, vz);
        if (pose != null) {
            pose.transformPosition(at);
            pose.transformNormal(velocity);
        }

        level.addParticle(type, at.x, at.y, at.z, velocity.x, velocity.y, velocity.z);
    }

    protected final void sound(final ClientLevel level, @Nullable final Pose3dc pose, final SoundEvent sound,
                               final float volume, final float pitch) {
        if (!AAConfig.SOUNDS.get())
            return;

        final Vector3d at = new Vector3d(this.shape.origin.getX() + 0.5, this.shape.origin.getY() + 0.5,
                this.shape.origin.getZ() + 0.5);
        if (pose != null)
            pose.transformPosition(at);

        level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.BLOCKS, volume, pitch, false);
    }

    static float clamp01(final float t) {
        return t < 0f ? 0f : Math.min(t, 1f);
    }

    static float smooth(final float t) {
        final float c = clamp01(t);
        return c * c * (3f - 2f * c);
    }

    static float outCubic(final float t) {
        final float inverse = 1f - clamp01(t);
        return 1f - inverse * inverse * inverse;
    }

    static float outBack(final float t) {
        final float c1 = 1.70158f;
        final float c3 = c1 + 1f;
        final float u = clamp01(t) - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    static float inOutSine(final float t) {
        return (float) (-(Math.cos(Math.PI * clamp01(t)) - 1) / 2);
    }

    static float gauss(final float x) {
        return (float) Math.exp(-x * x);
    }
}
