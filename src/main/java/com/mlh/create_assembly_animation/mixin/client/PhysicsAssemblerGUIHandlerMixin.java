package com.mlh.create_assembly_animation.mixin.client;

import com.mlh.create_assembly_animation.client.AssemblyAnimations;
import dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerGUIHandler;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(PhysicsAssemblerGUIHandler.class)
public abstract class PhysicsAssemblerGUIHandlerMixin {

    @ModifyArg(method = "release", at = @At(value = "INVOKE",
            target = "Ldev/simulated_team/simulated/network/packets/AssemblePacket;<init>(Lnet/minecraft/core/BlockPos;)V"))
    private BlockPos caa$chargeOnPull(final BlockPos assembler) {
        AssemblyAnimations.onLeverPulled(assembler);
        return assembler;
    }
}
