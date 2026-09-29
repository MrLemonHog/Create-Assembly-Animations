package com.mlh.create_assembly_animation.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class PackEffects {

    private static final String[] SHIP = {"time", "stage", "reach", "size", "faces", "lo_x", "lo_y", "lo_z", "hi_x",
            "hi_y", "hi_z", "center_x", "center_y", "center_z", "right_x", "right_y", "right_z"};
    private static final int TIME = 0;
    private static final int RIGHT = 14;

    private static final String[] POINT = {"spot_x", "spot_y", "spot_z", "normal_x", "normal_y", "normal_z", "block_x",
            "block_y", "block_z", "color_r", "color_g", "color_b", "seed"};

    private static final String[] SPRITE_OUT = {"x", "y", "z", "size", "roll", "flip", "r", "g", "b", "alpha", "picture"};
    private static final String[] ITEM_OUT = {"x", "y", "z", "scale", "yaw", "pitch"};

    private static final int MAX_POINTS = 20000;
    private static final int MAX_COPIES = 64;
    private static final int FALLBACK_COLOR = 0x8A7B68;
    private static final Map<ResourceLocation, Integer> AVERAGES = new HashMap<>();

    private PackEffects() {
    }

    static void clearColors() {
        AVERAGES.clear();
    }

    private record Assign(int slot, Expr.Compiled value) {
    }

    private record Uniform(String name, Expr.Compiled value) {
    }

    private enum Source {
        FACES, BLOCKS, TOP, CENTER
    }

    private enum Kind {
        SPRITE, ITEM
    }

    private record Layer(Kind kind, List<ResourceLocation> pictures, boolean glow, int copies, int copySlot,
                         List<Assign> vars, @Nullable Expr.Compiled when, int[] out, Expr.Compiled[] outputs,
                         int size) {
    }

    private record Effect(Source source, @Nullable Expr.Compiled amount, boolean items, int start, int end, int size,
                          List<Assign> vars, @Nullable Expr.Compiled when, List<Layer> layers) {
    }

    static final class Program {

        final Expr.Names names;
        private final List<Assign> vars;
        private final List<Uniform> uniforms;
        private final List<Effect> effects;
        private final Map<String, PackAnimations.Option> options;

        private Program(final Expr.Names names, final List<Assign> vars, final List<Uniform> uniforms,
                        final List<Effect> effects, final Map<String, PackAnimations.Option> options) {
            this.names = names;
            this.vars = vars;
            this.uniforms = uniforms;
            this.effects = effects;
            this.options = options;
        }

        List<String> uniformNames() {
            return this.uniforms.stream().map(Uniform::name).toList();
        }

        static Program read(final ResourceLocation style, final JsonObject meta,
                            final Map<String, PackAnimations.Option> options) {
            final Expr.Names names = new Expr.Names();
            for (final String name : SHIP)
                names.add(name, name.equals("time"));
            for (final String option : options.keySet()) {
                if (names.has(option))
                    throw new IllegalArgumentException("the option " + option + " takes a name the mod keeps");
                names.add(option, true);
            }

            final List<Assign> vars = assigns(meta, "vars", names);

            final List<Uniform> uniforms = new ArrayList<>();
            final JsonElement uniformJson = meta.get("uniforms");
            if (uniformJson != null && uniformJson.isJsonObject()) {
                for (final Map.Entry<String, JsonElement> entry : uniformJson.getAsJsonObject().entrySet())
                    uniforms.add(new Uniform(entry.getKey(), formula(entry.getValue(), names, entry.getKey())));
            }

            final List<Effect> effects = new ArrayList<>();
            final JsonElement effectJson = meta.get("effects");
            if (effectJson != null && effectJson.isJsonArray()) {
                for (final JsonElement entry : effectJson.getAsJsonArray()) {
                    if (entry.isJsonObject())
                        effects.add(effect(style, entry.getAsJsonObject(), names));
                }
            }
            return new Program(names, vars, List.copyOf(uniforms), List.copyOf(effects), options);
        }

        private static Effect effect(final ResourceLocation style, final JsonObject json, final Expr.Names ship) {
            final Source source = switch (PackAnimations.string(json, "on", "center")) {
                case "faces" -> Source.FACES;
                case "blocks" -> Source.BLOCKS;
                case "top" -> Source.TOP;
                case "center" -> Source.CENTER;
                default -> throw new IllegalArgumentException("\"on\" is faces, blocks, top or center");
            };
            final JsonElement amountJson = json.get(source == Source.FACES ? "per_face" : "share");
            final Expr.Compiled amount = amountJson == null ? null : formula(amountJson, ship, "the amount");

            final Expr.Names names = ship.extend();
            final int start = names.size();
            for (final String name : POINT)
                names.add(name, false);
            final List<Assign> vars = assigns(json, "vars", names);
            final int end = names.size();
            final Expr.Compiled when = json.has("when") ? formula(json.get("when"), names, "when") : null;

            final List<Layer> layers = new ArrayList<>();
            final JsonElement draw = json.get("draw");
            if (draw == null || !draw.isJsonArray())
                throw new IllegalArgumentException("an effect needs a \"draw\" list");
            int size = end;
            for (final JsonElement entry : draw.getAsJsonArray()) {
                if (!entry.isJsonObject())
                    continue;
                final Layer layer = layer(style, entry.getAsJsonObject(), names.extend());
                layers.add(layer);
                size = Math.max(size, layer.size);
            }
            return new Effect(source, amount, PackAnimations.bool(json, "items", false), start, end, size,
                    vars, when, List.copyOf(layers));
        }

        private static Layer layer(final ResourceLocation style, final JsonObject json, final Expr.Names names) {
            final boolean item = json.has("item") && PackAnimations.bool(json, "item", false);
            final List<ResourceLocation> pictures = new ArrayList<>();
            final JsonElement sprite = json.get("sprite");
            if (sprite != null && sprite.isJsonArray()) {
                sprite.getAsJsonArray().forEach(p -> pictures.add(picture(style, p.getAsString())));
            } else if (sprite != null && sprite.isJsonPrimitive()) {
                pictures.add(picture(style, sprite.getAsString()));
            }
            if (!item && pictures.isEmpty())
                throw new IllegalArgumentException("a drawing needs \"sprite\" pictures or \"item\": true");

            final int copies = (int) Math.max(1, Math.min(MAX_COPIES, PackAnimations.number(json, "copies", 1)));
            final int copySlot = names.add("copy", true);
            final String[] outputs = item ? ITEM_OUT : SPRITE_OUT;
            final List<Assign> vars = assigns(json, "vars", names, outputs);
            final Expr.Compiled when = json.has("when") ? formula(json.get("when"), names, "when") : null;

            final float[] defaults = item ? new float[]{0, 0, 0, 1, 0, 0} : new float[]{0, 0, 0, 0.25f, 0, 1, 1, 1, 1, 1, 0};
            final int[] out = new int[outputs.length];
            final Expr.Compiled[] formulas = new Expr.Compiled[outputs.length];
            for (int i = 0; i < outputs.length; i++) {
                formulas[i] = json.has(outputs[i]) ? formula(json.get(outputs[i]), names, outputs[i])
                        : Expr.Compiled.constant(defaults[i]);
                out[i] = names.add(outputs[i], true);
            }
            return new Layer(item ? Kind.ITEM : Kind.SPRITE, List.copyOf(pictures),
                    PackAnimations.bool(json, "glow", false), copies, copySlot, vars, when, out, formulas, names.size());
        }

        private static ResourceLocation picture(final ResourceLocation style, final String name) {
            final ResourceLocation id = name.indexOf(':') < 0 ? ResourceLocation.tryBuild(style.getNamespace(), name)
                    : ResourceLocation.tryParse(name);
            if (id == null)
                throw new IllegalArgumentException(name + " is not a picture name");
            return id;
        }

        private static List<Assign> assigns(final JsonObject json, final String field, final Expr.Names names,
                                            final String... reserved) {
            final JsonElement element = json.get(field);
            if (element == null)
                return List.of();
            if (!element.isJsonObject())
                throw new IllegalArgumentException("\"" + field + "\" should be names with formulas");
            final List<Assign> assigns = new ArrayList<>();
            for (final Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                final Expr.Compiled value = formula(entry.getValue(), names, entry.getKey());
                if (names.has(entry.getKey()) || List.of(reserved).contains(entry.getKey()))
                    throw new IllegalArgumentException(entry.getKey() + " is already taken");
                assigns.add(new Assign(names.add(entry.getKey(), value.dynamic()), value));
            }
            return List.copyOf(assigns);
        }

        static Expr.Compiled formula(final JsonElement value, final Expr.Names names, final String what) {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())
                return Expr.Compiled.constant(value.getAsFloat());
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean())
                return Expr.Compiled.constant(value.getAsBoolean() ? 1f : 0f);
            if (!value.isJsonPrimitive())
                throw new IllegalArgumentException(what + " should be a number or a formula");
            try {
                return Expr.compile(value.getAsString(), names);
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(what + ": " + e.getMessage(), e);
            }
        }

        Run start(final ShipAnimation animation, final int stage) {
            return new Run(this, animation, stage);
        }
    }

    static final class Run {

        private final Program program;
        private final GlowShape shape;
        final float[] ship;
        private final List<Points> points = new ArrayList<>();
        private int light = LightTexture.FULL_BRIGHT;
        private boolean prepared;
        private final Quaternionf rotation = new Quaternionf();

        private Run(final Program program, final ShipAnimation animation, final int stage) {
            this.program = program;
            this.shape = animation.shape;
            this.ship = new float[program.names.size()];

            int faces = 0;
            for (final int block : this.shape.shell)
                faces += Integer.bitCount(this.shape.faces[block]);
            final GlowShape s = this.shape;
            final float[] builtins = {0f, stage, Math.max(s.maxDistance, 1f), s.size, faces, s.minX, s.minY, s.minZ,
                    s.maxX + 1f, s.maxY + 1f, s.maxZ + 1f, s.centerX, s.centerY, s.centerZ, 1f, 0f, 0f};
            System.arraycopy(builtins, 0, this.ship, 0, builtins.length);
            for (final Effect effect : program.effects)
                this.points.add(new Points(effect));
            this.update(0f);
        }

        void update(final float time) {
            this.ship[TIME] = time;
            for (final Map.Entry<String, PackAnimations.Option> option : this.program.options.entrySet())
                this.ship[this.program.names.slot(option.getKey())] = option.getValue().asFloat();
            for (final Assign assign : this.program.vars)
                this.ship[assign.slot()] = assign.value().eval(this.ship);
        }

        float eval(final Expr.Compiled formula) {
            return formula.eval(this.ship);
        }

        void prepare(final BlockGetter blocks, final Vector3fc right) {
            this.ship[RIGHT] = right.x();
            this.ship[RIGHT + 1] = right.y();
            this.ship[RIGHT + 2] = right.z();
            this.update(this.ship[TIME]);
            for (final Points group : this.points)
                group.looks = looks(this.shape, blocks);
            this.prepared = true;
        }

        void uniforms(final ShaderInstance shader) {
            for (final Uniform uniform : this.program.uniforms)
                shader.safeGetUniform(uniform.name()).set(uniform.value().eval(this.ship));
        }

        void tick(final ClientLevel level, @Nullable final Pose3dc pose, final int age) {
            if (age % 20 != 0 || this.program.effects.isEmpty())
                return;
            final Vector3d at = new Vector3d(this.shape.origin.getX() + this.shape.centerX,
                    this.shape.origin.getY() + this.shape.maxY + 1.5, this.shape.origin.getZ() + this.shape.centerZ);
            if (pose != null)
                pose.transformPosition(at);
            this.light = LevelRenderer.getLightColor(level, BlockPos.containing(at.x, at.y, at.z));
        }

        void draw(final GlowCanvas canvas) {
            if (!this.prepared)
                return;
            for (final Points group : this.points)
                group.draw(canvas);
        }

        private final class Points {

            private final Effect effect;
            private final float[] buffer;
            @Nullable
            private Look[] looks;
            private float amount = Float.NaN;
            private int count;
            private float[] values = new float[0];
            private ItemStack[] items = new ItemStack[0];
            private final TextureAtlasSprite[][] sprites;

            Points(final Effect effect) {
                this.effect = effect;
                this.buffer = new float[effect.size];
                this.sprites = new TextureAtlasSprite[effect.layers.size()][];
            }

            private int width() {
                return this.effect.end - this.effect.start;
            }

            private void place() {
                final Run run = Run.this;
                final GlowShape shape = run.shape;
                this.amount = this.effect.amount == null ? 1f : this.effect.amount.eval(run.ship);
                final RandomSource random = RandomSource.create(shape.origin.asLong() ^ 0x9E3779B97F4A7C15L);
                final List<float[]> spots = new ArrayList<>();
                final List<ItemStack> stacks = new ArrayList<>();

                switch (this.effect.source) {
                    case FACES -> {
                        for (final int block : shape.shell) {
                            for (final Direction face : Direction.values()) {
                                if ((shape.faces[block] & (1 << face.ordinal())) == 0)
                                    continue;
                                int n = (int) this.amount;
                                if (random.nextFloat() < this.amount - n)
                                    n++;
                                for (int i = 0; i < n && spots.size() < MAX_POINTS; i++) {
                                    final float u = 0.06f + random.nextFloat() * 0.88f;
                                    final float v = 0.06f + random.nextFloat() * 0.88f;
                                    final int sx = face.getStepX();
                                    final int sy = face.getStepY();
                                    final int sz = face.getStepZ();
                                    spots.add(this.spot(block,
                                            shape.x[block] + (sx == 0 ? u : sx > 0 ? 1f : 0f),
                                            shape.y[block] + (sy == 0 ? (sx == 0 ? v : u) : sy > 0 ? 1f : 0f),
                                            shape.z[block] + (sz == 0 ? v : sz > 0 ? 1f : 0f), sx, sy, sz,
                                            random.nextFloat()));
                                    stacks.add(this.item(block));
                                }
                            }
                        }
                    }
                    case BLOCKS -> {
                        for (int block = 0; block < shape.size && spots.size() < MAX_POINTS; block++) {
                            if (Expr.hash(shape.x[block] + 7919, shape.y[block] - 104729, shape.z[block] + 1299709) >= this.amount)
                                continue;
                            final ItemStack stack = this.item(block);
                            if (this.effect.items && stack.isEmpty())
                                continue;
                            spots.add(this.spot(block, shape.x[block] + 0.5f, shape.y[block] + 0.5f,
                                    shape.z[block] + 0.5f, 0, 0, 0, random.nextFloat()));
                            stacks.add(stack);
                        }
                    }
                    case TOP -> {
                        int best = -1;
                        float nearest = Float.MAX_VALUE;
                        for (final int block : shape.shell) {
                            if (shape.y[block] != shape.maxY)
                                continue;
                            final float dx = shape.x[block] + 0.5f - shape.centerX;
                            final float dz = shape.z[block] + 0.5f - shape.centerZ;
                            if (dx * dx + dz * dz < nearest) {
                                nearest = dx * dx + dz * dz;
                                best = block;
                            }
                        }
                        if (best >= 0) {
                            spots.add(this.spot(best, shape.x[best] + 0.5f, shape.y[best] + 0.5f, shape.z[best] + 0.5f,
                                    0, 1, 0, random.nextFloat()));
                            stacks.add(this.item(best));
                        }
                    }
                    case CENTER -> {
                        spots.add(this.spot(-1, shape.centerX, shape.centerY, shape.centerZ, 0, 0, 0,
                                random.nextFloat()));
                        stacks.add(ItemStack.EMPTY);
                    }
                }

                final int width = this.width();
                this.count = spots.size();
                this.values = new float[this.count * width];
                this.items = stacks.toArray(ItemStack[]::new);
                for (int i = 0; i < this.count; i++)
                    System.arraycopy(spots.get(i), 0, this.values, i * width, width);
            }

            private float[] spot(final int block, final float x, final float y, final float z, final float nx,
                                 final float ny, final float nz, final float seed) {
                final Run run = Run.this;
                final int base = this.effect.start;
                System.arraycopy(run.ship, 0, this.buffer, 0, base);
                final Look look = block >= 0 && this.looks != null ? this.looks[block] : null;
                final float[] point = {x, y, z, nx, ny, nz,
                        block >= 0 ? run.shape.x[block] : (float) Math.floor(x),
                        block >= 0 ? run.shape.y[block] : (float) Math.floor(y),
                        block >= 0 ? run.shape.z[block] : (float) Math.floor(z),
                        look == null ? 0.54f : look.r, look == null ? 0.48f : look.g, look == null ? 0.41f : look.b,
                        seed};
                System.arraycopy(point, 0, this.buffer, base, point.length);
                for (final Assign assign : this.effect.vars)
                    this.buffer[assign.slot()] = assign.value().eval(this.buffer);

                final float[] kept = new float[this.width()];
                System.arraycopy(this.buffer, base, kept, 0, kept.length);
                return kept;
            }

            private ItemStack item(final int block) {
                final Look look = this.looks == null ? null : this.looks[block];
                return look == null ? ItemStack.EMPTY : look.item;
            }

            void draw(final GlowCanvas canvas) {
                final Run run = Run.this;
                final float amount = this.effect.amount == null ? 1f : this.effect.amount.eval(run.ship);
                if (amount != this.amount)
                    this.place();

                for (int layer = 0; layer < this.sprites.length; layer++) {
                    final List<ResourceLocation> pictures = this.effect.layers.get(layer).pictures;
                    if (this.sprites[layer] == null || this.sprites[layer].length != pictures.size())
                        this.sprites[layer] = new TextureAtlasSprite[pictures.size()];
                    for (int i = 0; i < pictures.size(); i++)
                        this.sprites[layer][i] = particle(pictures.get(i));
                }

                final int base = this.effect.start;
                final int width = this.width();
                final float[] v = this.buffer;
                for (int i = 0; i < this.count; i++) {
                    System.arraycopy(run.ship, 0, v, 0, base);
                    System.arraycopy(this.values, i * width, v, base, width);
                    for (final Assign assign : this.effect.vars) {
                        if (assign.value().dynamic())
                            v[assign.slot()] = assign.value().eval(v);
                    }
                    if (this.effect.when != null && this.effect.when.eval(v) == 0f)
                        continue;

                    for (int l = 0; l < this.effect.layers.size(); l++) {
                        final Layer layer = this.effect.layers.get(l);
                        for (int copy = 0; copy < layer.copies; copy++) {
                            v[layer.copySlot] = copy;
                            for (final Assign assign : layer.vars)
                                v[assign.slot()] = assign.value().eval(v);
                            if (layer.when != null && layer.when.eval(v) == 0f)
                                continue;
                            for (int o = 0; o < layer.out.length; o++)
                                v[layer.out[o]] = layer.outputs[o].eval(v);
                            this.emit(canvas, layer, l, i, v);
                        }
                    }
                }
            }

            private void emit(final GlowCanvas canvas, final Layer layer, final int index, final int point,
                              final float[] v) {
                final int[] o = layer.out;
                if (layer.kind == Kind.ITEM) {
                    final ItemStack stack = this.items[point];
                    if (stack.isEmpty())
                        return;
                    Run.this.rotation.identity().rotateY(v[o[4]]).rotateX(v[o[5]]);
                    canvas.item(stack, v[o[0]], v[o[1]], v[o[2]], v[o[3]], Run.this.rotation, Run.this.light, point);
                    return;
                }
                final TextureAtlasSprite[] sprites = this.sprites[index];
                final int picture = Math.max(0, Math.min(sprites.length - 1, (int) v[o[10]]));
                canvas.sprite(sprites[picture], layer.glow, v[o[0]], v[o[1]], v[o[2]], v[o[3]], v[o[4]], v[o[5]],
                        v[o[6]], v[o[7]], v[o[8]], v[o[9]]);
            }
        }
    }

    private record Look(float r, float g, float b, ItemStack item) {
    }

    @Nullable
    static TextureAtlasSprite particle(@Nullable final ResourceLocation id) {
        if (id == null)
            return null;
        final TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_PARTICLES);
        return atlas.getSprite(id);
    }

    private static Look[] looks(final GlowShape shape, final BlockGetter blocks) {
        final Look[] looks = new Look[shape.size];
        final Map<BlockState, Look> byState = new HashMap<>();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int block = 0; block < shape.size; block++) {
            pos.set(shape.position(block));
            final BlockState state = blocks.getBlockState(pos);
            if (state.isAir())
                continue;
            looks[block] = byState.computeIfAbsent(state, key -> look(key, blocks, pos.immutable()));
        }
        return looks;
    }

    private static Look look(final BlockState state, final BlockGetter blocks, final BlockPos pos) {
        final Minecraft minecraft = Minecraft.getInstance();
        final TextureAtlasSprite sprite = minecraft.getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
        int rgb = AVERAGES.computeIfAbsent(sprite.contents().name(), name -> average(sprite));

        if (tinted(minecraft.getBlockRenderer().getBlockModel(state), state, sprite)) {
            int tint;
            try {
                tint = minecraft.getBlockColors().getColor(state,
                        blocks instanceof final BlockAndTintGetter level ? level : null,
                        blocks instanceof BlockAndTintGetter ? pos : null, 0);
            } catch (final RuntimeException e) {
                tint = -1;
            }
            if (tint != -1)
                rgb = multiply(rgb, tint);
        }
        return new Look((rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f,
                new ItemStack(state.getBlock().asItem()));
    }

    private static boolean tinted(final BakedModel model, final BlockState state, final TextureAtlasSprite sprite) {
        final RandomSource random = RandomSource.create(42L);
        for (final Direction side : Direction.values()) {
            if (anyTinted(model, state, side, random, sprite))
                return true;
        }
        return anyTinted(model, state, null, random, sprite);
    }

    private static boolean anyTinted(final BakedModel model, final BlockState state, @Nullable final Direction side,
                                     final RandomSource random, final TextureAtlasSprite sprite) {
        try {
            for (final BakedQuad quad : model.getQuads(state, side, random)) {
                if (quad.isTinted() && quad.getSprite() == sprite)
                    return true;
            }
        } catch (final RuntimeException ignored) {
        }
        return false;
    }

    private static int average(final TextureAtlasSprite sprite) {
        try {
            final NativeImage image = sprite.contents().getOriginalImage();
            final int width = sprite.contents().width();
            final int height = Math.min(sprite.contents().height(), image.getHeight());
            long r = 0;
            long g = 0;
            long b = 0;
            long count = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    final int abgr = image.getPixelRGBA(x, y);
                    if ((abgr >>> 24) < 128)
                        continue;
                    r += abgr & 0xFF;
                    g += abgr >> 8 & 0xFF;
                    b += abgr >> 16 & 0xFF;
                    count++;
                }
            }
            if (count == 0)
                return FALLBACK_COLOR;
            return (int) (r / count) << 16 | (int) (g / count) << 8 | (int) (b / count);
        } catch (final RuntimeException e) {
            return FALLBACK_COLOR;
        }
    }

    private static int multiply(final int a, final int b) {
        final int r = (a >> 16 & 0xFF) * (b >> 16 & 0xFF) / 255;
        final int g = (a >> 8 & 0xFF) * (b >> 8 & 0xFF) / 255;
        final int bl = (a & 0xFF) * (b & 0xFF) / 255;
        return r << 16 | g << 8 | bl;
    }
}
