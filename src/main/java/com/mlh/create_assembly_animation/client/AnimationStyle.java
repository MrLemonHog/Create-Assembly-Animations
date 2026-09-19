package com.mlh.create_assembly_animation.client;

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

    public static final AnimationStyle DIAGRAM = register(new AnimationStyle(
            CreateAssemblyAnimation.asResource("diagram"), StyleSource.BUILT_IN, AAConfig.DIAGRAM,
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
                    new StyleOption.Toggle(option("diagram.force_arrows"), AAConfig.DIAGRAM_FORCE_ARROWS),
                    new StyleOption.Toggle(option("diagram.measurements"), AAConfig.DIAGRAM_MEASUREMENTS),
                    new StyleOption.Toggle(option("diagram.rotation_gizmo"), AAConfig.DIAGRAM_ROTATION_GIZMO))));

    public static final AnimationStyle SCANNER = register(new AnimationStyle(
            CreateAssemblyAnimation.asResource("scanner"), StyleSource.BUILT_IN, AAConfig.SCANNER,
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
                    new StyleOption.Choice<>(option("scanner.laser_color"), AAConfig.SCANNER_LASER_COLOR,
                            List.of(AAConfig.LaserColor.values()),
                            color -> color.displayName().copy().withStyle(style -> style.withColor(TextColor.fromRgb(color.rgb())))),
                    new StyleOption.Toggle(option("scanner.halo"), AAConfig.SCANNER_HALO))));

    public static final AnimationStyle DEFAULT = DIAGRAM;

    private final ResourceLocation id;
    private final StyleSource source;
    private final AAConfig.StyleSettings settings;
    private final Animations animations;
    @Nullable
    private final float[] chargeColor;
    private final List<StyleOption> options;

    AnimationStyle(final ResourceLocation id, final StyleSource source, final AAConfig.StyleSettings settings,
                   final Animations animations, @Nullable final float[] chargeColor,
                   final List<StyleOption> ownOptions) {
        this.id = id;
        this.source = source;
        this.settings = settings;
        this.animations = animations;
        this.chargeColor = chargeColor;

        final List<StyleOption> options = new ArrayList<>();
        options.add(new StyleOption.Slider(option("brightness"), settings.brightness(), AAConfig.MIN_BRIGHTNESS,
                AAConfig.MAX_BRIGHTNESS, 0.05, value -> Math.round(value * 100) + "%"));
        options.add(new StyleOption.Slider(option("speed"), settings.speed(), AAConfig.MIN_SPEED, AAConfig.MAX_SPEED,
                0.05, value -> String.format(Locale.ROOT, "%.2f×", value)));
        options.addAll(ownOptions);
        this.options = List.copyOf(options);
    }

    private static String option(final String key) {
        return CreateAssemblyAnimation.ID + ".configuration." + key;
    }

    static synchronized AnimationStyle register(final AnimationStyle style) {
        if (STYLES.putIfAbsent(style.id, style) != null)
            throw new IllegalStateException("Duplicate animation style " + style.id);
        return style;
    }

    public static List<AnimationStyle> all() {
        final Map<String, List<AnimationStyle>> bySource = new LinkedHashMap<>();
        for (final AnimationStyle style : STYLES.values())
            bySource.computeIfAbsent(style.source.id(), key -> new ArrayList<>()).add(style);

        final List<AnimationStyle> all = new ArrayList<>(STYLES.size());
        bySource.values().forEach(all::addAll);
        return Collections.unmodifiableList(all);
    }

    @Nullable
    public static AnimationStyle byId(@Nullable final ResourceLocation id) {
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

    public AAConfig.StyleSettings settings() {
        return this.settings;
    }

    public List<StyleOption> options() {
        return this.options;
    }

    public Component displayName() {
        return Component.translatable(this.translationKey());
    }

    public Component description() {
        return Component.translatable(this.translationKey() + ".description");
    }

    private String translationKey() {
        return this.id.getNamespace() + ".style." + this.id.getPath().replace('/', '.');
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
