package com.mlh.create_assembly_animation.client;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.function.DoubleFunction;
import java.util.function.Function;

public sealed interface StyleOption {

    String key();

    ModConfigSpec.ConfigValue<?> value();

    default Component caption() {
        return Component.translatable(this.key());
    }

    default Component tooltip() {
        return Component.translatable(this.key() + ".tooltip");
    }

    record Toggle(String key, ModConfigSpec.BooleanValue value) implements StyleOption {
    }

    record Choice<E extends Enum<E>>(String key, ModConfigSpec.EnumValue<E> value, List<E> values,
                                     Function<E, Component> format) implements StyleOption {
    }

    record Slider(String key, ModConfigSpec.DoubleValue value, double min, double max, double step,
                  DoubleFunction<String> format) implements StyleOption {
    }
}
