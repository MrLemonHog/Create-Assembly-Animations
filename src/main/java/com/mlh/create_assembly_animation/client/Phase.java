package com.mlh.create_assembly_animation.client;

import com.mlh.create_assembly_animation.AAConfig;
import com.mlh.create_assembly_animation.CreateAssemblyAnimation;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

public enum Phase {

    ASSEMBLY,
    DISASSEMBLY;

    public AAConfig.PhaseSettings settings() {
        return this == ASSEMBLY ? AAConfig.ASSEMBLY : AAConfig.DISASSEMBLY;
    }

    public boolean enabled() {
        return this.settings().enabled().get();
    }

    public AnimationStyle style() {
        return this.settings().style().get();
    }

    public String id() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    public Component displayName() {
        return Component.translatable(CreateAssemblyAnimation.ID + ".phase." + this.id());
    }

    static Phase of(final ShipAnimation.Role role, @Nullable final UUID subLevelId) {
        return switch (role) {
            case ASSEMBLE -> ASSEMBLY;
            case ALIGN, DISASSEMBLE -> DISASSEMBLY;
            case CHARGE, FAIL -> subLevelId != null ? DISASSEMBLY : ASSEMBLY;
        };
    }
}
