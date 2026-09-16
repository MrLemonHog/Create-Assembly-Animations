package com.mlh.create_assembly_animation;

import com.mlh.create_assembly_animation.client.AnimationStyle;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Locale;

public final class AAConfig {

    public static final double MIN_BRIGHTNESS = 0.0;
    public static final double MAX_BRIGHTNESS = 2.0;
    public static final double MIN_SPEED = 0.25;
    public static final double MAX_SPEED = 4.0;

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue PARTICLES;
    public static final ModConfigSpec.BooleanValue SOUNDS;

    public static final PhaseSettings ASSEMBLY;
    public static final PhaseSettings DISASSEMBLY;

    public static final StyleSettings DIAGRAM;
    public static final ModConfigSpec.BooleanValue DIAGRAM_FORCE_ARROWS;
    public static final ModConfigSpec.BooleanValue DIAGRAM_MEASUREMENTS;
    public static final ModConfigSpec.BooleanValue DIAGRAM_ROTATION_GIZMO;

    public static final StyleSettings SCANNER;
    public static final ModConfigSpec.EnumValue<LaserColor> SCANNER_LASER_COLOR;
    public static final ModConfigSpec.BooleanValue SCANNER_HALO;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        PARTICLES = builder
                .comment("Spawn particles with the animations.")
                .define("particles", true);
        SOUNDS = builder
                .comment("Play the sounds that accompany the animations.")
                .define("sounds", true);

        builder.comment("Assembly: a structure in the world becomes a physics object (physics on).").push("assembly");
        ASSEMBLY = PhaseSettings.define(builder);
        builder.pop();

        builder.comment("Disassembly: a ship lines up and turns back into blocks (physics off).",
                "The style also plays while the ship lines up.").push("disassembly");
        DISASSEMBLY = PhaseSettings.define(builder);
        builder.pop();

        builder.comment("Settings of the DIAGRAM style.").push("diagram");
        DIAGRAM = StyleSettings.define(builder);
        DIAGRAM_FORCE_ARROWS = builder
                .comment("Draw the force arrows for propulsion, lift, balloon lift and levitation.")
                .define("force_arrows", true);
        DIAGRAM_MEASUREMENTS = builder
                .comment("Draw the dimension lines and the mass of the structure.")
                .define("measurements", true);
        DIAGRAM_ROTATION_GIZMO = builder
                .comment("Turn the rotation gizmo of the diagram beneath the ship while it lines up for disassembly.")
                .define("rotation_gizmo", true);
        builder.pop();

        builder.comment("Settings of the SCANNER style.").push("scanner");
        SCANNER = StyleSettings.define(builder);
        SCANNER_LASER_COLOR = builder
                .comment("Colour of the laser sheet.")
                .defineEnum("laser_color", LaserColor.CYAN);
        SCANNER_HALO = builder
                .comment("Draw the halo sticking out of the hull where the sheet cuts it.")
                .define("halo", true);
        builder.pop();

        SPEC = builder.build();
    }

    private AAConfig() {
    }

    public enum LaserColor {

        CYAN(0xC7FFFF),
        GREEN(0x9CFFA8),
        GOLD(0xFFD670),
        MAGENTA(0xFF8CF2),
        RED(0xFF7A6B),
        WHITE(0xFFFFFF);

        private final int rgb;
        private final float[] components;

        LaserColor(final int rgb) {
            this.rgb = rgb;
            this.components = new float[]{(rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f};
        }

        public int rgb() {
            return this.rgb;
        }

        public float[] components() {
            return this.components;
        }

        public Component displayName() {
            return Component.translatable(CreateAssemblyAnimation.ID + ".laser_color." + this.name().toLowerCase(Locale.ROOT));
        }
    }

    public record PhaseSettings(ModConfigSpec.BooleanValue enabled, ModConfigSpec.EnumValue<AnimationStyle> style) {

        private static PhaseSettings define(final ModConfigSpec.Builder builder) {
            return new PhaseSettings(
                    builder.comment("Play an animation.")
                            .define("enabled", true),
                    builder.comment("The look of the animation.",
                                    "DIAGRAM - the structure is sketched and redrawn like a page of the Contraption Diagram, with its forces.",
                                    "SCANNER - a laser sheet sweeps up or down the structure.")
                            .defineEnum("style", AnimationStyle.DIAGRAM));
        }
    }

    public record StyleSettings(ModConfigSpec.DoubleValue brightness, ModConfigSpec.DoubleValue speed) {

        private static StyleSettings define(final ModConfigSpec.Builder builder) {
            return new StyleSettings(
                    builder.comment("How strongly the structure glows. 0 hides the glow but keeps particles and sounds.")
                            .defineInRange("brightness", 1.0, MIN_BRIGHTNESS, MAX_BRIGHTNESS),
                    builder.comment("Playback speed of the animations.")
                            .defineInRange("speed", 1.0, MIN_SPEED, MAX_SPEED));
        }
    }
}
