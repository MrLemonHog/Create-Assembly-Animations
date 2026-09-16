package com.mlh.create_assembly_animation.client;

import com.mlh.create_assembly_animation.AAConfig;
import com.mlh.create_assembly_animation.CreateAssemblyAnimation;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

public enum AnimationStyle {

    DIAGRAM(new float[]{1.00f, 0.90f, 0.70f}) {
        @Override
        ShipAnimation assemble(@Nullable final UUID subLevel, final GlowShape shape) {
            return DiagramStyle.assemble(subLevel, shape);
        }

        @Override
        ShipAnimation align(@Nullable final UUID subLevel, final GlowShape shape) {
            return DiagramStyle.align(subLevel, shape);
        }

        @Override
        ShipAnimation disassemble(final GlowShape shape) {
            return DiagramStyle.disassemble(shape);
        }
    },

    SCANNER(null) {
        @Override
        ShipAnimation assemble(@Nullable final UUID subLevel, final GlowShape shape) {
            return ScannerStyle.assemble(subLevel, shape);
        }

        @Override
        ShipAnimation align(@Nullable final UUID subLevel, final GlowShape shape) {
            return ScannerStyle.align(subLevel, shape);
        }

        @Override
        ShipAnimation disassemble(final GlowShape shape) {
            return ScannerStyle.disassemble(shape);
        }
    };

    @Nullable
    private final float[] chargeColor;

    AnimationStyle(@Nullable final float[] chargeColor) {
        this.chargeColor = chargeColor;
    }

    abstract ShipAnimation assemble(@Nullable UUID subLevel, GlowShape shape);

    abstract ShipAnimation align(@Nullable UUID subLevel, GlowShape shape);

    abstract ShipAnimation disassemble(GlowShape shape);

    @Nullable
    float[] chargeColor() {
        return this.chargeColor;
    }

    public AAConfig.StyleSettings settings() {
        return switch (this) {
            case DIAGRAM -> AAConfig.DIAGRAM;
            case SCANNER -> AAConfig.SCANNER;
        };
    }

    public Component displayName() {
        return Component.translatable(this.translationKey());
    }

    public Component description() {
        return Component.translatable(this.translationKey() + ".description");
    }

    private String translationKey() {
        return CreateAssemblyAnimation.ID + ".style." + this.name().toLowerCase(Locale.ROOT);
    }
}
