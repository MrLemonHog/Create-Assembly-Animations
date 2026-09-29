package com.mlh.create_assembly_animation.client;

import com.mlh.create_assembly_animation.CreateAssemblyAnimation;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

final class ShaderStyle implements AnimationStyle.Animations {

    static final int ASSEMBLY = 0;
    static final int ALIGNMENT = 1;
    static final int DISASSEMBLY = 2;

    private static final float TAIL_TICKS = 2f;
    private static final AtomicInteger HEIGHT_MAPS = new AtomicInteger();

    private final Shader shader;
    private final List<Texture> textures;
    private final List<Cue> cues;
    private final List<Emitter> emitters;
    private final List<PackAnimations.Option> options;
    private final PackEffects.Program program;
    private final Expr.Compiled assemblySeconds;
    private final Expr.Compiled disassemblySeconds;
    private final boolean screen;
    private final float margin;
    private final boolean writesDepth;
    @Nullable
    private AnimationStyle style;

    ShaderStyle(final Shader shader, final List<Texture> textures, final List<Cue> cues, final List<Emitter> emitters,
                final List<PackAnimations.Option> options, final PackEffects.Program program,
                final Expr.Compiled assemblySeconds, final Expr.Compiled disassemblySeconds, final boolean screen,
                final float margin, final boolean writesDepth) {
        this.shader = shader;
        this.textures = textures;
        this.cues = cues;
        this.emitters = emitters;
        this.options = options;
        this.program = program;
        this.assemblySeconds = assemblySeconds;
        this.disassemblySeconds = disassemblySeconds;
        this.screen = screen;
        this.margin = margin;
        this.writesDepth = writesDepth;
    }

    void bind(final AnimationStyle style) {
        this.style = style;
    }

    @Override
    public ShipAnimation assemble(@Nullable final UUID subLevel, final GlowShape shape) {
        return new Play(ShipAnimation.Role.ASSEMBLE, subLevel, shape, ASSEMBLY);
    }

    @Override
    public ShipAnimation align(@Nullable final UUID subLevel, final GlowShape shape) {
        return new Play(ShipAnimation.Role.ALIGN, subLevel, shape, ALIGNMENT);
    }

    @Override
    public ShipAnimation disassemble(final GlowShape shape) {
        return new Play(ShipAnimation.Role.DISASSEMBLE, null, shape, DISASSEMBLY);
    }

    record Texture(String name, ResourceLocation location, int frames) {
    }

    enum Where {
        SHELL,
        VOLUME,
        WAVE
    }

    record Emitter(ParticleOptions type, Where where, float from, float to, float rate, float speed, float chance,
                   int stages) {

        boolean playsIn(final int stage) {
            return (this.stages & (1 << stage)) != 0;
        }
    }

    record Cue(SoundEvent sound, float at, Expr.Compiled time, Expr.Compiled every, Expr.Compiled from,
               Expr.Compiled to, Expr.Compiled jitter, float volume, float pitch, float pitchSpread, int stages) {

        boolean playsIn(final int stage) {
            return (this.stages & (1 << stage)) != 0;
        }
    }

    static final class Shader implements AutoCloseable {

        final ShaderInstance instance;
        private boolean closed;

        Shader(final ShaderInstance instance) {
            this.instance = instance;
        }

        boolean closed() {
            return this.closed;
        }

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                this.instance.close();
            }
        }
    }

    static Vector3f screenRight(final Matrix4f matrix) {
        final Matrix4f toLocal = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(matrix).invert();
        final Vector4f right = toLocal.transform(new Vector4f(1f, 0f, 0f, 0f));
        final Vector3f direction = new Vector3f(right.x, right.y, right.z);
        return direction.lengthSquared() < 1.0e-10f ? new Vector3f(1f, 0f, 0f) : direction.normalize();
    }

    private static NativeImage heightMap(final GlowShape shape) {
        final int width = Math.max(1, shape.maxX - shape.minX + 1);
        final int depth = Math.max(1, shape.maxZ - shape.minZ + 1);
        final int[] top = new int[width * depth];
        for (int i = 0; i < shape.size; i++) {
            final int cell = (shape.x[i] - shape.minX) + (shape.z[i] - shape.minZ) * width;
            top[cell] = Math.max(top[cell], shape.y[i] - shape.minY + 1);
        }

        final NativeImage image = new NativeImage(width, depth, false);
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                final int code = Math.min(top[x + z * width], 0xFFFF);
                image.setPixelRGBA(x, z, 0xFF000000 | ((code >> 8) & 0xFF) << 8 | (code & 0xFF));
            }
        }
        return image;
    }

    private static NativeImage solidMap(final GlowShape shape, final BlockGetter blocks, final int columns) {
        final int width = shape.maxX - shape.minX + 1;
        final int height = shape.maxY - shape.minY + 1;
        final int depth = shape.maxZ - shape.minZ + 1;
        final int rows = (height + columns - 1) / columns;
        final NativeImage image = new NativeImage(width * columns, depth * rows, true);
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int block = 0; block < shape.size; block++) {
            pos.set(shape.position(block));
            final BlockState state = blocks.getBlockState(pos);
            if (state.isAir())
                continue;
            int kind;
            try {
                kind = Block.isShapeFullBlock(state.getShape(blocks, pos)) ? 0xFF : 0x80;
            } catch (final RuntimeException e) {
                kind = 0x80;
            }
            final int layer = shape.y[block] - shape.minY;
            image.setPixelRGBA(shape.x[block] - shape.minX + (layer % columns) * width,
                    shape.z[block] - shape.minZ + (layer / columns) * depth, 0xFF000000 | kind);
        }
        return image;
    }

    private final class Play extends ShipAnimation {

        private final int stage;
        private final StructureRedraw redraw;
        private final PackEffects.Run run;
        private final float duration;
        private final float[] carried;
        private final float[] reached;
        private final float[][] timing;
        private final float[] nextCue;
        private final Vector3f startRight = new Vector3f(1f, 0f, 0f);
        @Nullable
        private ResourceLocation heights;
        @Nullable
        private ResourceLocation solids;
        private int solidsGrid = 1;
        private boolean prepared;

        Play(final Role role, @Nullable final UUID subLevel, final GlowShape shape, final int stage) {
            super(ShaderStyle.this.style, role, subLevel, shape);
            this.stage = stage;
            this.redraw = new StructureRedraw(shape);
            this.run = ShaderStyle.this.program.start(this, stage);
            final Expr.Compiled seconds = stage == DISASSEMBLY ? ShaderStyle.this.disassemblySeconds
                    : ShaderStyle.this.assemblySeconds;
            this.duration = Math.max(0.25f, Math.min(60f, this.run.eval(seconds))) * 20f;

            this.carried = new float[ShaderStyle.this.emitters.size()];
            this.reached = new float[this.carried.length];
            java.util.Arrays.fill(this.reached, -1f);

            final List<Cue> cues = ShaderStyle.this.cues;
            this.timing = new float[cues.size()][];
            this.nextCue = new float[cues.size()];
            for (int i = 0; i < cues.size(); i++) {
                final Cue cue = cues.get(i);
                this.timing[i] = new float[]{this.run.eval(cue.time()), this.run.eval(cue.every()),
                        this.run.eval(cue.from()), this.run.eval(cue.to()), this.run.eval(cue.jitter())};
                this.nextCue[i] = this.timing[i][2] * 20f;
            }
        }

        @Override
        protected float lifetime() {
            return this.stage == ALIGNMENT ? 20 * 60 * this.step() : this.duration + TAIL_TICKS;
        }

        @Override
        protected void draw(final GlowCanvas canvas, final float time) {
            this.run.update(time);
            this.run.draw(canvas);
        }

        @Override
        protected void drawDirect(final BlockGetter blocks, final float daylight, final Matrix4f matrix, final float time,
                                  final float fade) {
            if (!this.prepared) {
                this.prepared = true;
                this.startRight.set(screenRight(matrix));
                this.run.prepare(blocks, this.startRight);
                this.solidsGrid = Math.max(1, Mth.ceil(Mth.sqrt(this.shape.maxY - this.shape.minY + 1)));
                this.solids = CreateAssemblyAnimation.asResource("pack_solids/" + HEIGHT_MAPS.incrementAndGet());
                Minecraft.getInstance().getTextureManager().register(this.solids,
                        new DynamicTexture(solidMap(this.shape, blocks, this.solidsGrid)));
            }
            this.run.update(time);

            final Shader shader = ShaderStyle.this.shader;
            if (shader.closed())
                return;

            final int width = Minecraft.getInstance().getMainRenderTarget().width;
            final int height = Minecraft.getInstance().getMainRenderTarget().height;
            if (this.heights == null) {
                this.heights = CreateAssemblyAnimation.asResource("pack_heights/" + HEIGHT_MAPS.incrementAndGet());
                Minecraft.getInstance().getTextureManager().register(this.heights, new DynamicTexture(heightMap(this.shape)));
            }
            final int heightsId = Minecraft.getInstance().getTextureManager().getTexture(this.heights).getId();
            final float progress = this.stage == ALIGNMENT ? 0f : clamp01(time / this.duration);

            this.redraw.draw(blocks, matrix, shader.instance, ShaderStyle.this.writesDepth, post -> {
                post.setSampler("Heights", heightsId);
                if (this.solids != null)
                    post.setSampler("Solids", Minecraft.getInstance().getTextureManager().getTexture(this.solids).getId());
                post.safeGetUniform("SolidsGrid").set((float) this.solidsGrid);
                post.safeGetUniform("Progress").set(progress);
                post.safeGetUniform("Time").set(time / 20f);
                post.safeGetUniform("Duration").set(this.duration / 20f);
                post.safeGetUniform("Stage").set(this.stage);
                post.safeGetUniform("Brightness").set(this.style.brightness());
                post.safeGetUniform("Fade").set(fade);
                post.safeGetUniform("Daylight").set(daylight);
                post.safeGetUniform("BoundsMin").set((float) this.shape.minX, this.shape.minY, this.shape.minZ);
                post.safeGetUniform("BoundsMax").set(this.shape.maxX + 1f, this.shape.maxY + 1f, this.shape.maxZ + 1f);
                post.safeGetUniform("Reach").set(Math.max(this.shape.maxDistance, 1f));
                post.safeGetUniform("ScreenSize").set((float) width, (float) height);
                post.safeGetUniform("Area").set(ShaderStyle.this.screen ? 1 : 0);
                post.safeGetUniform("Margin").set(ShaderStyle.this.margin);
                post.safeGetUniform("StartRight").set(this.startRight.x, this.startRight.y, this.startRight.z);
                for (final Texture texture : ShaderStyle.this.textures) {
                    post.setSampler(texture.name(),
                            Minecraft.getInstance().getTextureManager().getTexture(texture.location()).getId());
                    post.safeGetUniform(texture.name() + "Frames").set((float) texture.frames());
                }
                for (final PackAnimations.Option option : ShaderStyle.this.options) {
                    if (option.uniform() != null)
                        post.safeGetUniform(option.uniform()).set(option.asFloat());
                }
                this.run.uniforms(post);
            });
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        }

        private float moment(final int cue) {
            final float time = this.timing[cue][0];
            if (time >= 0f)
                return time * 20f;
            final float at = ShaderStyle.this.cues.get(cue).at();
            if (this.stage == ALIGNMENT)
                return at <= 0f ? 0f : Float.NaN;
            return at * this.duration;
        }

        private void play(final ClientLevel level, @Nullable final Pose3dc pose, final Cue cue) {
            final float pitch = cue.pitch() + (cue.pitchSpread() > 0f ? this.random.nextFloat() * cue.pitchSpread() : 0f);
            this.sound(level, pose, cue.sound(), cue.volume(), pitch);
        }

        @Override
        protected void onStart(final ClientLevel level, @Nullable final Pose3dc pose) {
            final List<Cue> cues = ShaderStyle.this.cues;
            for (int i = 0; i < cues.size(); i++) {
                if (cues.get(i).playsIn(this.stage) && this.timing[i][1] <= 0f && this.moment(i) <= 0f)
                    this.play(level, pose, cues.get(i));
            }
        }

        @Override
        protected void onTick(final ClientLevel level, @Nullable final Pose3dc pose, final float time) {
            final float progress = this.stage == ALIGNMENT ? 0f : clamp01(time / this.duration);
            this.run.tick(level, pose, this.age);

            final List<Cue> cues = ShaderStyle.this.cues;
            for (int i = 0; i < cues.size(); i++) {
                final Cue cue = cues.get(i);
                if (!cue.playsIn(this.stage))
                    continue;

                final float every = this.timing[i][1];
                if (every > 0f) {
                    final float to = this.timing[i][3];
                    if ((to < 0f || time < to * 20f) && time >= this.nextCue[i]) {
                        this.play(level, pose, cue);
                        final float jitter = this.timing[i][4];
                        this.nextCue[i] = jitter > 0f ? time + (every + this.random.nextFloat() * jitter) * 20f
                                : this.nextCue[i] + every * 20f;
                    }
                } else {
                    final float moment = this.moment(i);
                    if (moment > 0f && this.crossed(time, moment))
                        this.play(level, pose, cue);
                }
            }

            final List<Emitter> emitters = ShaderStyle.this.emitters;
            for (int i = 0; i < emitters.size(); i++) {
                final Emitter emitter = emitters.get(i);
                if (!emitter.playsIn(this.stage))
                    continue;

                if (emitter.where() == Where.WAVE) {
                    if (this.stage == ALIGNMENT)
                        continue;
                    final float front = clamp01((progress - emitter.from()) / Math.max(emitter.to() - emitter.from(), 1.0e-4f));
                    if (progress >= emitter.from() && front > this.reached[i]) {
                        this.wave(level, pose, emitter, this.reached[i], front);
                        this.reached[i] = front;
                    }
                    continue;
                }

                if (this.stage != ALIGNMENT && (progress < emitter.from() || progress > emitter.to()))
                    continue;

                this.carried[i] += emitter.rate() * this.step() / 20f;
                final int count = Math.min(40, (int) this.carried[i]);
                this.carried[i] -= count;
                this.spawn(level, pose, emitter, count);
            }
        }

        private void wave(final ClientLevel level, @Nullable final Pose3dc pose, final Emitter emitter,
                          final float from, final float to) {
            final float reach = Math.max(this.shape.maxDistance, 1f);
            int spawned = 0;
            for (final int block : this.shape.shell) {
                final float at = this.shape.distance[block] / reach;
                if (at <= from || at > to || this.random.nextFloat() >= emitter.chance())
                    continue;
                this.faceParticle(level, pose, block, emitter.type(), emitter.speed());
                if (++spawned >= 24)
                    return;
            }
        }

        private void spawn(final ClientLevel level, @Nullable final Pose3dc pose, final Emitter emitter,
                           final int count) {
            for (int i = 0; i < count; i++) {
                if (emitter.where() == Where.SHELL) {
                    if (this.shape.shell.length == 0)
                        return;
                    this.faceParticle(level, pose, this.shape.shell[this.random.nextInt(this.shape.shell.length)],
                            emitter.type(), emitter.speed());
                } else {
                    if (this.shape.size == 0)
                        return;
                    final int block = this.random.nextInt(this.shape.size);
                    this.particle(level, pose, emitter.type(),
                            this.shape.x[block] + this.random.nextDouble(),
                            this.shape.y[block] + this.random.nextDouble(),
                            this.shape.z[block] + this.random.nextDouble(),
                            (this.random.nextDouble() - 0.5) * emitter.speed(),
                            (this.random.nextDouble() - 0.5) * emitter.speed(),
                            (this.random.nextDouble() - 0.5) * emitter.speed());
                }
            }
        }

        @Override
        void close() {
            this.redraw.close();
            if (this.heights != null) {
                Minecraft.getInstance().getTextureManager().release(this.heights);
                this.heights = null;
            }
            if (this.solids != null) {
                Minecraft.getInstance().getTextureManager().release(this.solids);
                this.solids = null;
            }
        }
    }
}
