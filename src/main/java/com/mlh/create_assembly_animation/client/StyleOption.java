package com.mlh.create_assembly_animation.client;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.DoubleFunction;
import java.util.function.Function;

public sealed interface StyleOption {

    String key();

    Setting<?> value();

    @Nullable
    Component label();

    @Nullable
    Component hint();

    default Component caption() {
        return this.label() != null ? this.label() : Component.translatable(this.key());
    }

    default Component tooltip() {
        return this.hint() != null ? this.hint() : Component.translatable(this.key() + ".tooltip");
    }

    record Toggle(String key, Setting<Boolean> value, @Nullable Component label, @Nullable Component hint)
            implements StyleOption {

        public Toggle(final String key, final Setting<Boolean> value) {
            this(key, value, null, null);
        }
    }

    record Choice<T>(String key, Setting<T> value, List<T> values, Function<T, Component> format,
                     @Nullable Component label, @Nullable Component hint) implements StyleOption {

        public Choice(final String key, final Setting<T> value, final List<T> values, final Function<T, Component> format) {
            this(key, value, values, format, null, null);
        }
    }

    record Slider(String key, Setting<Double> value, double min, double max, double step,
                  DoubleFunction<String> format, @Nullable Component label, @Nullable Component hint)
            implements StyleOption {

        public Slider(final String key, final Setting<Double> value, final double min, final double max,
                      final double step, final DoubleFunction<String> format) {
            this(key, value, min, max, step, format, null, null);
        }
    }

    interface Setting<T> {
        T get();

        void set(T value);

        T defaultValue();

        default void reset() {
            this.set(this.defaultValue());
        }

        static <T> Setting<T> of(final ModConfigSpec.ConfigValue<T> value) {
            return new Setting<>() {
                @Override
                public T get() {
                    return value.get();
                }

                @Override
                public void set(final T newValue) {
                    value.set(newValue);
                }

                @Override
                public T defaultValue() {
                    return value.getDefault();
                }
            };
        }
    }
}
