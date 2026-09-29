package com.mlh.create_assembly_animation.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mlh.create_assembly_animation.AAConfig;
import com.mlh.create_assembly_animation.CreateAssemblyAnimation;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.server.packs.resources.IoSupplier;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoubleFunction;

public final class PackAnimations extends SimplePreparableReloadListener<List<PackAnimations.Definition>> {

    public static final String DIRECTORY = "assembly_animations";

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateAssemblyAnimation.ID);
    private static final String SHADER_EXTENSION = ".fsh";
    private static final String HEADER = "#version 150\n#moj_import <" + CreateAssemblyAnimation.ID + ":assembly_animation.glsl>\n";
    private static final ResourceLocation VERTEX_SHADER = CreateAssemblyAnimation.asResource("shaders/core/pack_animation.vsh");
    private static final ResourceLocation UNKNOWN_PACK = ResourceLocation.withDefaultNamespace("textures/misc/unknown_pack.png");
    private static final float DEFAULT_DURATION = 3f;
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final java.util.regex.Pattern SWITCH =
            java.util.regex.Pattern.compile("(?m)^[ \\t]*#define[ \\t]+(WRITES_DEPTH|RAW_OUTPUT)\\b.*$");
    private static final java.util.regex.Pattern WRITES_DEPTH =
            java.util.regex.Pattern.compile("(?m)^[ \\t]*#define[ \\t]+WRITES_DEPTH\\b");
    private static final String MOD_PACK = "mod/" + CreateAssemblyAnimation.ID;

    private static final List<ShaderStyle.Shader> SHADERS = new ArrayList<>();
    private static final List<ResourceLocation> ICONS = new ArrayList<>();
    private static final List<ResourceLocation> STRIPS = new ArrayList<>();

    private PackAnimations() {
    }

    public static void register(final RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new PackAnimations());
    }

    @Override
    protected List<Definition> prepare(final ResourceManager manager, final ProfilerFiller profiler) {
        final Map<ResourceLocation, Resource> shaders = manager.listResources(DIRECTORY,
                location -> location.getPath().endsWith(SHADER_EXTENSION));

        final Map<String, List<Definition>> byPack = new LinkedHashMap<>();
        manager.listPacks().forEach(pack -> byPack.put(pack.packId(), new ArrayList<>()));

        for (final Map.Entry<ResourceLocation, Resource> entry : shaders.entrySet()) {
            final ResourceLocation file = entry.getKey();
            final String path = file.getPath();
            final ResourceLocation id = ResourceLocation.fromNamespaceAndPath(file.getNamespace(),
                    path.substring(DIRECTORY.length() + 1, path.length() - SHADER_EXTENSION.length()));
            final Resource resource = entry.getValue();

            try {
                final String source;
                try (InputStream stream = resource.open()) {
                    source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
                final JsonObject meta = meta(manager, id);
                byPack.computeIfAbsent(resource.sourcePackId(), key -> new ArrayList<>())
                        .add(new Definition(id, resource.source(), source, meta));
            } catch (final IOException | RuntimeException e) {
                LOGGER.error("Could not read the assembly animation {}", file, e);
            }
        }

        final List<Definition> definitions = new ArrayList<>();
        final List<String> packs = new ArrayList<>(byPack.keySet());
        for (int i = packs.size() - 1; i >= 0; i--) {
            final List<Definition> pack = byPack.get(packs.get(i));
            pack.sort((a, b) -> a.id.toString().compareTo(b.id.toString()));
            definitions.addAll(pack);
        }
        return definitions;
    }

    private static JsonObject meta(final ResourceManager manager, final ResourceLocation id) {
        final ResourceLocation file = id.withPath(path -> DIRECTORY + "/" + path + ".json");
        final Optional<Resource> resource = manager.getResource(file);
        if (resource.isEmpty())
            return new JsonObject();

        try (Reader reader = resource.get().openAsReader()) {
            final JsonElement root = JsonParser.parseReader(reader);
            if (root.isJsonObject())
                return root.getAsJsonObject();
            LOGGER.warn("{} should hold a JSON object; it is ignored.", file);
        } catch (final IOException | RuntimeException e) {
            LOGGER.warn("Could not read {}; the animation uses its defaults.", file, e);
        }
        return new JsonObject();
    }

    @Override
    protected void apply(final List<Definition> definitions, final ResourceManager manager, final ProfilerFiller profiler) {
        SHADERS.forEach(ShaderStyle.Shader::close);
        SHADERS.clear();
        ICONS.forEach(Minecraft.getInstance().getTextureManager()::release);
        ICONS.clear();
        STRIPS.forEach(Minecraft.getInstance().getTextureManager()::release);
        STRIPS.clear();

        final Map<String, StyleSource> sources = new HashMap<>();
        final List<AnimationStyle> styles = new ArrayList<>();
        final List<ResourceLocation> failed = new ArrayList<>();

        for (final Definition definition : definitions) {
            final List<ShaderStyle.Texture> textures = textures(manager, definition);
            final List<Option> options = options(definition.id, definition.meta);
            final PackEffects.Program program;
            try {
                final Map<String, Option> byId = new LinkedHashMap<>();
                options.forEach(option -> byId.put(option.id(), option));
                program = PackEffects.Program.read(definition.id, definition.meta, byId);
            } catch (final RuntimeException e) {
                LOGGER.error("The assembly animation {} has a formula that does not work: {}", definition.id,
                        e.getMessage());
                failed.add(definition.id);
                continue;
            }

            final ShaderInstance shader;
            final AnimationStyle style;
            try {
                shader = compile(manager, definition, textures, options, program);
            } catch (final Exception e) {
                LOGGER.error("Could not compile the assembly animation {}", definition.id, e);
                failed.add(definition.id);
                continue;
            }

            final ShaderStyle.Shader holder = new ShaderStyle.Shader(shader);
            SHADERS.add(holder);
            final StyleSource source = MOD_PACK.equals(definition.pack.packId()) ? StyleSource.BUILT_IN
                    : sources.computeIfAbsent(definition.pack.packId(), key -> source(definition.pack));
            try {
                style = style(definition, source, holder, textures, options, program);
            } catch (final RuntimeException e) {
                LOGGER.error("The assembly animation {} has a formula that does not work: {}", definition.id,
                        e.getMessage());
                failed.add(definition.id);
                continue;
            }
            styles.add(style);
        }

        AnimationStyle.replaceFromPacks(styles);
        AnimationPreview.invalidate();

        if (!failed.isEmpty())
            SystemToast.add(Minecraft.getInstance().getToasts(), SystemToast.SystemToastId.PACK_LOAD_FAILURE,
                    Component.translatable(CreateAssemblyAnimation.ID + ".pack_animation.failed", failed.size()),
                    Component.literal(failed.get(0).toString()));
    }

    private static AnimationStyle style(final Definition definition, final StyleSource source,
                                       final ShaderStyle.Shader shader, final List<ShaderStyle.Texture> textures,
                                       final List<Option> options, final PackEffects.Program program) {
        final ResourceLocation id = definition.id;
        final JsonObject meta = definition.meta;
        final String key = id.getNamespace() + ".style." + id.getPath().replace('/', '.');

        final Component name = text(meta, "name", key, prettify(id.getPath()));
        final Component description = text(meta, "description", key + ".description", "");
        final Component author = meta.has("author") ? Component.literal(string(meta, "author", "")) : null;
        final JsonElement duration = meta.get("duration");
        final JsonObject durations = duration != null && duration.isJsonObject() ? duration.getAsJsonObject() : null;
        final Expr.Compiled assembly = durations == null ? duration(duration, program) : duration(durations.get("assembly"), program);
        final Expr.Compiled disassembly = durations == null ? duration(duration, program)
                : duration(durations.get("disassembly"), program);

        final boolean screen = "screen".equalsIgnoreCase(string(meta, "area", "structure"));
        final float margin = (float) Math.max(0.0, Math.min(16.0, number(meta, "margin", 3.0)));
        final boolean depth = WRITES_DEPTH.matcher(definition.source).find();
        final List<StyleOption> widgets = new ArrayList<>();
        options.forEach(option -> widgets.add(option.widget()));
        final ShaderStyle animations = new ShaderStyle(shader, textures, sounds(id, meta, program.names),
                particles(id, meta), options, program, assembly, disassembly, screen, margin, depth);
        final AnimationStyle style = new AnimationStyle(id, source, name, description, author,
                setting(id, "brightness", 1.0, AAConfig.MIN_BRIGHTNESS, AAConfig.MAX_BRIGHTNESS),
                setting(id, "speed", 1.0, AAConfig.MIN_SPEED, AAConfig.MAX_SPEED),
                animations, color(meta, "charge_color"), widgets);
        animations.bind(style);
        return style;
    }

    private static Expr.Compiled duration(@Nullable final JsonElement value, final PackEffects.Program program) {
        if (value == null)
            return Expr.Compiled.constant(DEFAULT_DURATION);
        return PackEffects.Program.formula(value, program.names, "duration");
    }

    private static ShaderInstance compile(final ResourceManager manager, final Definition definition,
                                          final List<ShaderStyle.Texture> textures, final List<Option> options,
                                          final PackEffects.Program effects) throws IOException {
        final ResourceLocation id = definition.id;
        final String program = DIRECTORY + "/" + id.getPath();
        final String vertexName = id.getNamespace() + ":" + program + "_vertex";
        final String fragmentName = id.getNamespace() + ":" + program;
        forget(Program.Type.VERTEX, vertexName);
        forget(Program.Type.FRAGMENT, fragmentName);

        final ResourceLocation json = id.withPath("shaders/core/" + program + ".json");
        final ResourceLocation vertex = id.withPath("shaders/core/" + program + "_vertex.vsh");
        final ResourceLocation fragment = id.withPath("shaders/core/" + program + SHADER_EXTENSION);
        final String source = definition.source.contains("#version") ? definition.source : wrap(definition.source);
        final List<String> uniforms = new ArrayList<>(effects.uniformNames());
        options.stream().map(Option::uniform).filter(java.util.Objects::nonNull).forEach(uniforms::add);
        final String programJson = programJson(vertexName, fragmentName, textures, uniforms);

        final ResourceProvider provider = location -> {
            if (location.equals(json))
                return Optional.of(new Resource(definition.pack, bytes(programJson)));
            if (location.equals(fragment))
                return Optional.of(new Resource(definition.pack, bytes(source)));
            if (location.equals(vertex))
                return manager.getResource(VERTEX_SHADER);
            return manager.getResource(location);
        };
        return new ShaderInstance(provider, id.withPath(program), DefaultVertexFormat.POSITION);
    }

    private static List<ShaderStyle.Texture> textures(final ResourceManager manager, final Definition definition) {
        final JsonElement element = definition.meta.get("textures");
        if (element == null || !element.isJsonObject())
            return List.of();

        final List<ShaderStyle.Texture> textures = new ArrayList<>();
        for (final Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            final String name = entry.getKey();
            if (!NAME.matcher(name).matches()) {
                LOGGER.warn("{} cannot be the name of a texture of {}; it has to be a plain word.", name, definition.id);
                continue;
            }

            try {
                if (entry.getValue().isJsonArray()) {
                    final List<ResourceLocation> frames = new ArrayList<>();
                    entry.getValue().getAsJsonArray().forEach(frame -> frames.add(ResourceLocation.parse(frame.getAsString())));
                    if (frames.isEmpty())
                        continue;
                    textures.add(new ShaderStyle.Texture(name, strip(manager, definition, name, frames), frames.size()));
                } else {
                    final ResourceLocation location = ResourceLocation.parse(entry.getValue().getAsString());
                    Minecraft.getInstance().getTextureManager().getTexture(location);
                    textures.add(new ShaderStyle.Texture(name, location, 1));
                }
            } catch (final IOException | RuntimeException e) {
                LOGGER.error("Could not read the texture {} of the assembly animation {}", name, definition.id, e);
            }
        }
        return textures;
    }

    private static ResourceLocation strip(final ResourceManager manager, final Definition definition, final String name,
                                          final List<ResourceLocation> frames) throws IOException {
        final List<NativeImage> images = new ArrayList<>(frames.size());
        try {
            for (final ResourceLocation frame : frames) {
                try (InputStream stream = manager.getResourceOrThrow(frame).open()) {
                    images.add(NativeImage.read(stream));
                }
            }

            final int width = images.get(0).getWidth();
            final int height = images.get(0).getHeight();
            final NativeImage strip = new NativeImage(width, height * images.size(), false);
            for (int i = 0; i < images.size(); i++) {
                final NativeImage image = images.get(i);
                if (image.getWidth() != width || image.getHeight() != height)
                    throw new IOException("the frames of " + name + " have different sizes");
                for (int x = 0; x < width; x++) {
                    for (int y = 0; y < height; y++)
                        strip.setPixelRGBA(x, i * height + y, image.getPixelRGBA(x, y));
                }
            }

            final ResourceLocation location = CreateAssemblyAnimation.asResource("pack_textures/"
                    + definition.id.getNamespace() + "/" + definition.id.getPath().replace('/', '_') + "_"
                    + name.toLowerCase(Locale.ROOT));
            Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(strip));
            STRIPS.add(location);
            return location;
        } finally {
            images.forEach(NativeImage::close);
        }
    }

    private static String wrap(final String source) {
        final StringBuilder switches = new StringBuilder();
        final java.util.regex.Matcher matcher = SWITCH.matcher(source);
        while (matcher.find())
            switches.append(matcher.group().trim()).append('\n');
        final String body = SWITCH.matcher(source).replaceAll("");
        return HEADER.replace("#moj_import", switches + "#moj_import") + body;
    }

    private static void forget(final Program.Type type, final String name) {
        final Program program = type.getPrograms().get(name);
        if (program != null)
            program.close();
    }

    private static IoSupplier<InputStream> bytes(final String text) {
        final byte[] data = text.getBytes(StandardCharsets.UTF_8);
        return () -> new ByteArrayInputStream(data);
    }

    private static String programJson(final String vertex, final String fragment,
                                      final List<ShaderStyle.Texture> textures, final List<String> extra) {
        final StringBuilder samplers = new StringBuilder();
        final StringBuilder uniforms = new StringBuilder();
        for (final String uniform : new java.util.LinkedHashSet<>(extra))
            uniforms.append(",\n                        { \"name\": \"").append(uniform)
                    .append("\", \"type\": \"float\", \"count\": 1, \"values\": [ 0 ] }");
        for (final ShaderStyle.Texture texture : textures) {
            samplers.append(", { \"name\": \"").append(texture.name()).append("\" }");
            uniforms.append(",\n                        { \"name\": \"").append(texture.name())
                    .append("Frames\", \"type\": \"float\", \"count\": 1, \"values\": [ 1 ] }");
        }
        return """
                {
                    "vertex": "%1$s",
                    "fragment": "%2$s",
                    "samplers": [ { "name": "SceneColor" }, { "name": "SceneDepth" }, { "name": "Mask" }, { "name": "Heights" }, { "name": "Solids" }%3$s ],
                    "uniforms": [
                        { "name": "InvViewProj", "type": "matrix4x4", "count": 16, "values": [ 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 ] },
                        { "name": "SceneToLocal", "type": "matrix4x4", "count": 16, "values": [ 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 ] },
                        { "name": "LocalToClip", "type": "matrix4x4", "count": 16, "values": [ 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 ] },
                        { "name": "Progress", "type": "float", "count": 1, "values": [ 0 ] },
                        { "name": "Time", "type": "float", "count": 1, "values": [ 0 ] },
                        { "name": "Duration", "type": "float", "count": 1, "values": [ 1 ] },
                        { "name": "Stage", "type": "int", "count": 1, "values": [ 0 ] },
                        { "name": "Brightness", "type": "float", "count": 1, "values": [ 1 ] },
                        { "name": "Fade", "type": "float", "count": 1, "values": [ 1 ] },
                        { "name": "Daylight", "type": "float", "count": 1, "values": [ 1 ] },
                        { "name": "BoundsMin", "type": "float", "count": 3, "values": [ 0, 0, 0 ] },
                        { "name": "BoundsMax", "type": "float", "count": 3, "values": [ 1, 1, 1 ] },
                        { "name": "Reach", "type": "float", "count": 1, "values": [ 1 ] },
                        { "name": "ScreenSize", "type": "float", "count": 2, "values": [ 1, 1 ] },
                        { "name": "Area", "type": "int", "count": 1, "values": [ 0 ] },
                        { "name": "Margin", "type": "float", "count": 1, "values": [ 0 ] },
                        { "name": "StartRight", "type": "float", "count": 3, "values": [ 1, 0, 0 ] },
                        { "name": "SolidsGrid", "type": "float", "count": 1, "values": [ 1 ] }%4$s
                    ]
                }
                """.formatted(vertex, fragment, samplers.toString(), uniforms.toString());
    }

    private static StyleSource source(final PackResources pack) {
        final String id = pack.packId();
        final Component title = id.startsWith("mod/")
                ? Component.literal(ModList.get().getModContainerById(id.substring(4))
                .map(container -> container.getModInfo().getDisplayName()).orElse(id.substring(4)))
                : pack.location().title();
        return new StyleSource(id, title, null, icon(pack));
    }

    private static StyleSource.Icon icon(final PackResources pack) {
        final IoSupplier<InputStream> png = pack.getRootResource("pack.png");
        if (png != null) {
            try (InputStream stream = png.get()) {
                final NativeImage image = NativeImage.read(stream);
                final int size = image.getWidth();
                final ResourceLocation texture = CreateAssemblyAnimation.asResource("pack_icons/"
                        + Integer.toHexString(pack.packId().hashCode()));
                Minecraft.getInstance().getTextureManager().register(texture, new DynamicTexture(image));
                ICONS.add(texture);
                return StyleSource.Icon.texture(texture, size);
            } catch (final IOException | RuntimeException e) {
                LOGGER.warn("Could not read the icon of the pack {}", pack.packId(), e);
            }
        }
        return StyleSource.Icon.texture(UNKNOWN_PACK, 64);
    }

    record Definition(ResourceLocation id, PackResources pack, String source, JsonObject meta) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SETTINGS = FMLPaths.CONFIGDIR.get().resolve(CreateAssemblyAnimation.ID + "-pack-styles.json");
    private static JsonObject settings = new JsonObject();
    private static boolean settingsLoaded;
    private static boolean settingsDirty;

    public static void saveSettings() {
        if (!settingsDirty)
            return;
        settingsDirty = false;
        try (Writer writer = Files.newBufferedWriter(SETTINGS, StandardCharsets.UTF_8)) {
            GSON.toJson(settings, writer);
        } catch (final IOException e) {
            LOGGER.warn("Could not save {}.", SETTINGS, e);
        }
    }

    @Nullable
    private static JsonObject entry(final ResourceLocation style, final boolean create) {
        if (!settingsLoaded) {
            settingsLoaded = true;
            if (Files.isRegularFile(SETTINGS)) {
                try (Reader reader = Files.newBufferedReader(SETTINGS, StandardCharsets.UTF_8)) {
                    final JsonElement root = JsonParser.parseReader(reader);
                    if (root.isJsonObject())
                        settings = root.getAsJsonObject();
                } catch (final IOException | RuntimeException e) {
                    LOGGER.warn("Could not read {}; the settings of pack animations start over.", SETTINGS, e);
                }
            }
        }
        final JsonElement entry = settings.get(style.toString());
        if (entry != null && entry.isJsonObject())
            return entry.getAsJsonObject();
        if (!create)
            return null;
        final JsonObject created = new JsonObject();
        settings.add(style.toString(), created);
        return created;
    }

    private static <T> StyleOption.Setting<T> kept(final ResourceLocation style, final String key, final T fallback,
                                                   final java.util.function.Function<JsonElement, T> read,
                                                   final java.util.function.Function<T, JsonElement> write) {
        return new StyleOption.Setting<>() {
            @Override
            public T get() {
                final JsonObject entry = entry(style, false);
                final JsonElement value = entry == null ? null : entry.get(key);
                final T parsed = value == null || !value.isJsonPrimitive() ? null : read.apply(value);
                return parsed == null ? fallback : parsed;
            }

            @Override
            public void set(final T value) {
                final JsonObject entry = entry(style, true);
                final JsonElement json = write.apply(value);
                if (entry != null && !json.equals(entry.get(key))) {
                    entry.add(key, json);
                    settingsDirty = true;
                }
            }

            @Override
            public T defaultValue() {
                return fallback;
            }
        };
    }

    static StyleOption.Setting<Double> setting(final ResourceLocation style, final String key, final double fallback,
                                               final double min, final double max) {
        return kept(style, key, fallback,
                json -> json.getAsJsonPrimitive().isNumber() ? Math.max(min, Math.min(max, json.getAsDouble())) : null,
                com.google.gson.JsonPrimitive::new);
    }

    record Option(String id, @Nullable String uniform, StyleOption.Setting<?> setting, List<String> values,
                  StyleOption widget) {

        float asFloat() {
            final Object value = this.setting.get();
            if (value instanceof final Boolean toggle)
                return toggle ? 1f : 0f;
            if (value instanceof final Double number)
                return number.floatValue();
            return Math.max(0, this.values.indexOf(value));
        }
    }

    private static List<Option> options(final ResourceLocation style, final JsonObject meta) {
        final JsonElement element = meta.get("options");
        if (element == null || !element.isJsonArray())
            return List.of();

        final String base = style.getNamespace() + ".style." + style.getPath().replace('/', '.') + ".option.";
        final List<Option> options = new ArrayList<>();
        for (final JsonElement entry : element.getAsJsonArray()) {
            if (!entry.isJsonObject())
                continue;
            final JsonObject json = entry.getAsJsonObject();
            final String id = string(json, "id", "");
            if (!NAME.matcher(id).matches() || options.stream().anyMatch(option -> option.id().equals(id))) {
                LOGGER.warn("An option of the assembly animation {} needs an id of its own made of letters, digits "
                        + "and _; {} is ignored.", style, id);
                continue;
            }
            final String uniform = string(json, "uniform", null);
            if (uniform != null && !NAME.matcher(uniform).matches()) {
                LOGGER.warn("{} cannot be the uniform of an option of {}; it has to be a plain word.", uniform, style);
                continue;
            }

            final String key = base + id;
            final Component name = text(json, "name", key, prettify(id));
            final Component tooltip = text(json, "tooltip", key + ".tooltip", "");
            final String stored = "option." + id;
            switch (string(json, "type", "toggle").toLowerCase(Locale.ROOT)) {
                case "choice" -> {
                    final List<String> values = new ArrayList<>();
                    final JsonElement list = json.get("values");
                    if (list != null && list.isJsonArray())
                        list.getAsJsonArray().forEach(value -> values.add(value.getAsString()));
                    if (values.isEmpty()) {
                        LOGGER.warn("The choice {} of the assembly animation {} has no values; it is ignored.", id, style);
                        continue;
                    }
                    final String wanted = string(json, "default", values.get(0));
                    final StyleOption.Setting<String> value = kept(style, stored,
                            values.contains(wanted) ? wanted : values.get(0),
                            v -> values.contains(v.getAsString()) ? v.getAsString() : null,
                            com.google.gson.JsonPrimitive::new);
                    final JsonObject names = json.has("value_names") && json.get("value_names").isJsonObject()
                            ? json.getAsJsonObject("value_names") : new JsonObject();
                    options.add(new Option(id, uniform, value, List.copyOf(values),
                            new StyleOption.Choice<>(key, value, List.copyOf(values),
                                    v -> text(names, v, key + "." + v, prettify(v)), name, tooltip)));
                }
                case "slider" -> {
                    final double min = number(json, "min", 0.0);
                    final double max = Math.max(min + 1.0e-6, number(json, "max", 1.0));
                    final StyleOption.Setting<Double> value = setting(style, stored,
                            Math.max(min, Math.min(max, number(json, "default", min))), min, max);
                    options.add(new Option(id, uniform, value, List.of(),
                            new StyleOption.Slider(key, value, min, max, number(json, "step", 0.0),
                                    format(string(json, "format", "number")), name, tooltip)));
                }
                default -> {
                    final StyleOption.Setting<Boolean> value = kept(style, stored, bool(json, "default", true),
                            v -> v.getAsJsonPrimitive().isBoolean() ? v.getAsBoolean() : null,
                            com.google.gson.JsonPrimitive::new);
                    options.add(new Option(id, uniform, value, List.of(),
                            new StyleOption.Toggle(key, value, name, tooltip)));
                }
            }
        }
        return List.copyOf(options);
    }

    private static DoubleFunction<String> format(final String format) {
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "percent" -> value -> Math.round(value * 100) + "%";
            case "multiplier" -> value -> String.format(Locale.ROOT, "%.2f×", value);
            default -> value -> String.format(Locale.ROOT, "%.2f", value);
        };
    }

    private static final int DEFAULT_STAGES = (1 << ShaderStyle.ASSEMBLY) | (1 << ShaderStyle.DISASSEMBLY);
    private static final float MAX_RATE = 200f;

    private static List<ShaderStyle.Cue> sounds(final ResourceLocation id, final JsonObject meta, final Expr.Names names) {
        final List<ShaderStyle.Cue> cues = new ArrayList<>();
        final Expr.Compiled never = Expr.Compiled.constant(-1f);
        final Expr.Compiled zero = Expr.Compiled.constant(0f);
        sound(meta, "sound").ifPresent(sound -> cues.add(new ShaderStyle.Cue(sound, 0f, never, zero, zero, never,
                zero, 0.8f, 1f, 0f, DEFAULT_STAGES)));
        sound(meta, "end_sound").ifPresent(sound -> cues.add(new ShaderStyle.Cue(sound, 1f, never, zero, zero, never,
                zero, 0.8f, 1f, 0f, DEFAULT_STAGES)));

        for (final JsonObject entry : objects(id, meta, "sounds")) {
            final ResourceLocation sound = ResourceLocation.tryParse(string(entry, "sound", ""));
            if (sound == null) {
                LOGGER.warn("A sound of the assembly animation {} has no id and is ignored.", id);
                continue;
            }
            cues.add(new ShaderStyle.Cue(SoundEvent.createVariableRangeEvent(sound), (float) number(entry, "at", 0.0),
                    seconds(entry, "time", -1f, names), seconds(entry, "every", 0f, names),
                    seconds(entry, "from", 0f, names), seconds(entry, "to", -1f, names),
                    seconds(entry, "jitter", 0f, names),
                    (float) Math.max(0.0, number(entry, "volume", 0.8)),
                    (float) Math.max(0.01, number(entry, "pitch", 1.0)),
                    (float) Math.max(0.0, number(entry, "pitch_spread", 0.0)), stages(id, entry)));
        }
        return List.copyOf(cues);
    }

    private static Expr.Compiled seconds(final JsonObject entry, final String field, final float fallback,
                                         final Expr.Names names) {
        final JsonElement value = entry.get(field);
        return value == null ? Expr.Compiled.constant(fallback) : PackEffects.Program.formula(value, names, field);
    }

    private static List<ShaderStyle.Emitter> particles(final ResourceLocation id, final JsonObject meta) {
        final List<ShaderStyle.Emitter> emitters = new ArrayList<>();
        for (final JsonObject entry : objects(id, meta, "particles")) {
            final String name = string(entry, "type", "");
            final ResourceLocation parsed = ResourceLocation.tryParse(name);
            final ParticleType<?> type = parsed == null ? null : BuiltInRegistries.PARTICLE_TYPE.get(parsed);
            if (!(type instanceof final SimpleParticleType simple)) {
                LOGGER.warn("{} is not a particle the assembly animation {} can spawn; it is ignored.", name, id);
                continue;
            }
            final ShaderStyle.Where where = switch (string(entry, "where", "shell").toLowerCase(Locale.ROOT)) {
                case "volume" -> ShaderStyle.Where.VOLUME;
                case "wave" -> ShaderStyle.Where.WAVE;
                default -> ShaderStyle.Where.SHELL;
            };
            emitters.add(new ShaderStyle.Emitter((ParticleOptions) simple, where, (float) number(entry, "from", 0.0),
                    (float) number(entry, "to", 1.0),
                    (float) Math.min(MAX_RATE, Math.max(0.0, number(entry, "rate", 10.0))),
                    (float) number(entry, "speed", 0.02),
                    (float) Math.min(1.0, Math.max(0.0, number(entry, "chance", 1.0))), stages(id, entry)));
        }
        return List.copyOf(emitters);
    }

    private static List<JsonObject> objects(final ResourceLocation id, final JsonObject meta, final String field) {
        final JsonElement element = meta.get(field);
        if (element == null)
            return List.of();
        if (!element.isJsonArray()) {
            LOGGER.warn("{} of the assembly animation {} should be a list; it is ignored.", field, id);
            return List.of();
        }
        final List<JsonObject> entries = new ArrayList<>();
        for (final JsonElement entry : element.getAsJsonArray()) {
            if (entry.isJsonObject())
                entries.add(entry.getAsJsonObject());
        }
        return entries;
    }

    private static int stages(final ResourceLocation id, final JsonObject entry) {
        final JsonElement element = entry.get("stages");
        if (element == null)
            return DEFAULT_STAGES;

        final JsonArray names = element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
        if (element.isJsonPrimitive()) {
            names.add(element.getAsString());
        } else if (!element.isJsonArray()) {
            LOGGER.warn("stages of the assembly animation {} should be a name or a list of names.", id);
            return DEFAULT_STAGES;
        }

        int stages = 0;
        for (final JsonElement name : names) {
            stages |= switch (name.getAsString().toLowerCase(Locale.ROOT)) {
                case "assembly" -> 1 << ShaderStyle.ASSEMBLY;
                case "alignment" -> 1 << ShaderStyle.ALIGNMENT;
                case "disassembly" -> 1 << ShaderStyle.DISASSEMBLY;
                default -> {
                    LOGGER.warn("{} is not a stage of the assembly animation {}.", name.getAsString(), id);
                    yield 0;
                }
            };
        }
        return stages == 0 ? DEFAULT_STAGES : stages;
    }

    private static Optional<SoundEvent> sound(final JsonObject meta, final String field) {
        final ResourceLocation id = ResourceLocation.tryParse(string(meta, field, ""));
        return meta.has(field) && id != null ? Optional.of(SoundEvent.createVariableRangeEvent(id)) : Optional.empty();
    }

    @Nullable
    static String string(final JsonObject object, final String field, @Nullable final String fallback) {
        final JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    static double number(final JsonObject object, final String field, final double fallback) {
        final JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? value.getAsDouble() : fallback;
    }

    static boolean bool(final JsonObject object, final String field, final boolean fallback) {
        final JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean() : fallback;
    }

    @Nullable
    private static float[] color(final JsonObject object, final String field) {
        final JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 3)
            return null;
        final float[] color = new float[3];
        for (int i = 0; i < 3; i++) {
            final JsonElement part = value.getAsJsonArray().get(i);
            if (!part.isJsonPrimitive() || !part.getAsJsonPrimitive().isNumber())
                return null;
            color[i] = Math.max(0f, Math.min(1f, part.getAsFloat()));
        }
        return color;
    }

    private static Component text(final JsonObject object, final String field, final String key, final String fallback) {
        final String value = string(object, field, null);
        return value != null ? Component.translatableWithFallback(value, value)
                : Component.translatableWithFallback(key, fallback);
    }

    private static String prettify(final String path) {
        final String name = path.substring(path.lastIndexOf('/') + 1).replace('_', ' ').replace('-', ' ');
        return name.isEmpty() ? path : name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }
}
