package com.mlh.create_assembly_animation.client;

import com.mlh.create_assembly_animation.AAConfig;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

import static com.mlh.create_assembly_animation.client.ShipAnimation.inOutSine;
import static com.mlh.create_assembly_animation.client.ShipAnimation.smooth;

final class ScannerStyle {

    private static final float LINE_HALF_HEIGHT = 0.25f;
    private static final float HALO_REACH = 0.9f;
    private static final float SWEEP_SPEED = 0.9f;

    private ScannerStyle() {
    }

    static ShipAnimation assemble(@Nullable final UUID subLevel, final GlowShape shape) {
        return new Sweep(ShipAnimation.Role.ASSEMBLE, subLevel, shape, true);
    }

    static ShipAnimation align(@Nullable final UUID subLevel, final GlowShape shape) {
        return new Patrol(subLevel, shape);
    }

    static ShipAnimation disassemble(final GlowShape shape) {
        return new Sweep(ShipAnimation.Role.DISASSEMBLE, null, shape, false);
    }

    private static void laser(final GlowCanvas canvas, final GlowShape shape, final float plane, final float strength) {
        if (strength <= 0f)
            return;

        final float[] color = AAConfig.SCANNER_LASER_COLOR.get().components();
        final boolean halo = AAConfig.SCANNER_HALO.get();

        for (final int block : shape.shell) {
            final int x = shape.x[block];
            final int y = shape.y[block];
            final int z = shape.z[block];
            final float inside = plane - y;
            if (inside < -LINE_HALF_HEIGHT || inside > 1f + LINE_HALF_HEIGHT)
                continue;

            canvas.stripe(x, y, z, shape.faces[block], plane, LINE_HALF_HEIGHT, 0.02f,
                    color[0], color[1], color[2], 0.9f * strength);
            if (halo && inside >= 0f && inside < 1f)
                canvas.halo(x, y, z, shape.faces[block], inside, HALO_REACH, color[0], color[1], color[2], 0.55f * strength);
        }
    }

    private static final class Sweep extends ShipAnimation {

        private static final int TAIL_TICKS = 4;
        private static final int SPARKS_PER_TICK = 5;

        private final boolean upward;
        private final float bottom;
        private final float top;

        private float start;
        private int sweepTicks;
        @Nullable
        private Patrol patrol;

        Sweep(final Role role, @Nullable final UUID subLevel, final GlowShape shape, final boolean upward) {
            super(role, subLevel, shape);
            this.upward = upward;
            this.bottom = shape.minY - 1.5f;
            this.top = shape.minY + shape.height() + 1.5f;
            this.start = upward ? this.bottom : this.top;
            this.sweepTicks = Mth.clamp(Mth.ceil((this.top - this.bottom) / SWEEP_SPEED), 10, 35);
        }

        @Override
        void follow(final ShipAnimation previous) {
            if (!this.upward && previous instanceof final Patrol alignment)
                this.patrol = alignment;
        }

        @Override
        protected float lifetime() {
            return this.sweepTicks + TAIL_TICKS;
        }

        private float plane(final float time) {
            final float progress = inOutSine(time / this.sweepTicks);
            return Mth.lerp(progress, this.start, this.upward ? this.top : this.bottom);
        }

        @Override
        protected void draw(final GlowCanvas canvas, final float time) {
            final float strength = Mth.clamp(time / FADE_TICKS, 0f, 1f)
                    * (1f - smooth((time - this.sweepTicks) / TAIL_TICKS));
            laser(canvas, this.shape, this.plane(time), strength);
        }

        @Override
        protected void onStart(final ClientLevel level, @Nullable final Pose3dc pose) {
            if (this.patrol != null) {
                this.start = Mth.clamp(this.patrol.plane(this.patrol.age * this.patrol.step()), this.bottom, this.top);
                this.sweepTicks = Mth.clamp(Mth.ceil((this.start - this.bottom) / SWEEP_SPEED), 5, 35);
            }
            this.sound(level, pose, SoundEvents.BEACON_POWER_SELECT, 0.6f, this.upward ? 1.6f : 1.1f);
        }

        @Override
        protected void onTick(final ClientLevel level, @Nullable final Pose3dc pose, final float time) {
            if (time < this.sweepTicks)
                this.planeParticles(level, pose, this.plane(time), SPARKS_PER_TICK, ParticleTypes.ELECTRIC_SPARK, 0.08);

            if (this.crossed(time, this.sweepTicks)) {
                if (this.upward)
                    this.sound(level, pose, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 1.4f);
                else
                    this.sound(level, pose, SoundEvents.BEACON_DEACTIVATE, 0.5f, 1.4f);
            }
        }
    }

    private static final class Patrol extends ShipAnimation {

        private static final float PERIOD = 32f;

        Patrol(@Nullable final UUID subLevel, final GlowShape shape) {
            super(Role.ALIGN, subLevel, shape);
        }

        @Override
        protected float lifetime() {
            return 20 * 60 * this.step();
        }

        private float plane(final float time) {
            return this.shape.minY + this.shape.height() + 0.5f
                    - (0.5f - 0.5f * Mth.cos(time / PERIOD * Mth.TWO_PI)) * (this.shape.height() + 1f);
        }

        @Override
        protected void draw(final GlowCanvas canvas, final float time) {
            laser(canvas, this.shape, this.plane(time), smooth(time / 10f));
        }

        @Override
        protected void onStart(final ClientLevel level, @Nullable final Pose3dc pose) {
            this.sound(level, pose, SoundEvents.BEACON_AMBIENT, 0.4f, 1.4f);
        }

        @Override
        protected void onTick(final ClientLevel level, @Nullable final Pose3dc pose, final float time) {
            if (this.age % 2 == 0)
                this.planeParticles(level, pose, this.plane(time), 1, ParticleTypes.ELECTRIC_SPARK, 0.06);
        }
    }
}
