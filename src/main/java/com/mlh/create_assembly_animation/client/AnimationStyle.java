package com.mlh.create_assembly_animation.client;

import com.mlh.create_assembly_animation.client.StyleOption.Setting;
import com.mlh.create_assembly_animation.AAConfig;
import com.mlh.create_assembly_animation.CreateAssemblyAnimation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AnimationStyle {

    private static final Map<ResourceLocation, AnimationStyle> STYLES = new LinkedHashMap<>();

    public static final AnimationStyle DIAGRAM = register(builtIn("diagram", AAConfig.DIAGRAM,
            new Animations() {
                @Override
                public ShipAnimation assemble(@Nullable final UUID subLevel, final GlowShape shape) {
                    return DiagramStyle.assemble(subLevel, shape);
                }

                @Override
                public ShipAnimation align(@Nullable final UUID subLevel, final GlowShape shape) {
                    return DiagramStyle.align(subLevel, shape);
                }

                @Override
                public ShipAnimation disassemble(final GlowShape shape) {
                    return DiagramStyle.disassemble(shape);
                }
            },
            new float[]{1.00f, 0.90f, 0.70f},
            List.of(
                    new StyleOption.Toggle(option("diagram.force_arrows"), Setting.of(AAConfig.DIAGRAM_FORCE_ARROWS)),
                    new StyleOption.Toggle(option("diagram.measurements"), Setting.of(AAConfig.DIAGRAM_MEASUREMENTS)),
                    new StyleOption.Toggle(option("diagram.rotation_gizmo"), Setting.of(AAConfig.DIAGRAM_ROTATION_GIZMO)))));

    public static final AnimationStyle SCANNER = register(builtIn("scanner", AAConfig.SCANNER,
            new Animations() {
                @Override
                public ShipAnimation assemble(@Nullable final UUID subLevel, final GlowShape shape) {
                    return ScannerStyle.assemble(subLevel, shape);
                }

                @Override
                public ShipAnimation align(@Nullable final UUID subLevel, final GlowShape shape) {
                    return ScannerStyle.align(subLevel, shape);
                }

                @Override
                public ShipAnimation disassemble(final GlowShape shape) {
                    return ScannerStyle.disassemble(shape);
                }
            },
            null,
            List.of(
                    new StyleOption.Choice<>(option("scanner.laser_color"), Setting.of(AAConfig.SCANNER_LASER_COLOR),
                            List.of(AAConfig.LaserColor.values()),
                            color -> color.displayName().copy().withStyle(style -> style.withColor(TextColor.fromRgb(color.rgb())))),
                    new StyleOption.Toggle(option("scanner.halo"), Setting.of(AAConfig.SCANNER_HALO)))));

    public static final AnimationStyle DEFAULT = DIAGRAM;

    private final ResourceLocation id;
    private final StyleSource source;
    private final Component name;
    private final Component description;
    @Nullable
    private final Component author;
    private final Setting<Double> brightness;
    private final Setting<Double> speed;
    private final Animations animations;
    @Nullable
    private final float[] chargeColor;
    private final List<StyleOption> options;

    AnimationStyle(final ResourceLocation id, final StyleSource source, final Component name,
                   final Component description, @Nullable final Component author, final Setting<Double> brightness,
                   final Setting<Double> speed, final Animations animations, @Nullable final float[] chargeColor,
                   final List<StyleOption> ownOptions) {
        this.id = id;
        this.source = source;
        this.name = name;
        this.description = description;
        this.author = author;
        this.brightness = brightness;
        this.speed = speed;
        this.animations = animations;
        this.chargeColor = chargeColor;

        final List<StyleOption> options = new ArrayList<>();
        options.add(new StyleOption.Slider(option("brightness"), brightness, AAConfig.MIN_BRIGHTNESS,
                AAConfig.MAX_BRIGHTNESS, 0.05, value -> Math.round(value * 100) + "%"));
        options.add(new StyleOption.Slider(option("speed"), speed, AAConfig.MIN_SPEED, AAConfig.MAX_SPEED,
                0.05, value -> String.format(Locale.ROOT, "%.2f×", value)));
        options.addAll(ownOptions);
        this.options = List.copyOf(options);
    }

    private static AnimationStyle builtIn(final String name, final AAConfig.StyleSettings settings,
                                          final Animations animations, @Nullable final float[] chargeColor,
                                          final List<StyleOption> options) {
        final String key = CreateAssemblyAnimation.ID + ".style." + name;
        return new AnimationStyle(CreateAssemblyAnimation.asResource(name), StyleSource.BUILT_IN,
                Component.translatable(key), Component.translatable(key + ".description"), null,
                Setting.of(settings.brightness()), Setting.of(settings.speed()), animations, chargeColor, options);
    }

    private static String option(final String key) {
        return CreateAssemblyAnimation.ID + ".configuration." + key;
    }

    static synchronized AnimationStyle register(final AnimationStyle style) {
        if (STYLES.putIfAbsent(style.id, style) != null)
            throw new IllegalStateException("Duplicate animation style " + style.id);
        return style;
    }

    static synchronized void replaceFromPacks(final List<AnimationStyle> styles) {
        STYLES.values().removeIf(style -> style.animations instanceof ShaderStyle);
        for (final AnimationStyle style : styles)
            STYLES.putIfAbsent(style.id, style);
    }

    public static synchronized List<AnimationStyle> all() {
        final Map<String, List<AnimationStyle>> bySource = new LinkedHashMap<>();
        for (final AnimationStyle style : STYLES.values())
            bySource.computeIfAbsent(style.source.id(), key -> new ArrayList<>()).add(style);

        final List<AnimationStyle> all = new ArrayList<>(STYLES.size());
        bySource.values().forEach(all::addAll);
        return Collections.unmodifiableList(all);
    }

    @Nullable
    public static synchronized AnimationStyle byId(@Nullable final ResourceLocation id) {
        return id == null ? null : STYLES.get(id);
    }

    @Nullable
    public static ResourceLocation parseId(final String value) {
        final String trimmed = value.trim();
        return trimmed.indexOf(':') < 0
                ? ResourceLocation.tryBuild(CreateAssemblyAnimation.ID, trimmed.toLowerCase(Locale.ROOT))
                : ResourceLocation.tryParse(trimmed);
    }

    ShipAnimation assemble(@Nullable final UUID subLevel, final GlowShape shape) {
        return this.animations.assemble(subLevel, shape);
    }

    ShipAnimation align(@Nullable final UUID subLevel, final GlowShape shape) {
        return this.animations.align(subLevel, shape);
    }

    ShipAnimation disassemble(final GlowShape shape) {
        return this.animations.disassemble(shape);
    }

    ShipAnimation preview(final Phase phase, final GlowShape shape) {
        return phase == Phase.ASSEMBLY ? this.assemble(null, shape) : this.disassemble(shape);
    }

    @Nullable
    float[] chargeColor() {
        return this.chargeColor;
    }

    public ResourceLocation id() {
        return this.id;
    }

    public StyleSource source() {
        return this.source;
    }

    public float brightness() {
        return this.brightness.get().floatValue();
    }

    public float speed() {
        return this.speed.get().floatValue();
    }

    public List<StyleOption> options() {
        return this.options;
    }

    public Component displayName() {
        return this.name;
    }

    public Component description() {
        return this.description;
    }

    @Nullable
    public Component author() {
        return this.author;
    }

    @Override
    public String toString() {
        return this.id.toString();
    }

    interface Animations {

        ShipAnimation assemble(@Nullable UUID subLevel, GlowShape shape);

        ShipAnimation align(@Nullable UUID subLevel, GlowShape shape);

        ShipAnimation disassemble(GlowShape shape);
    }
}
