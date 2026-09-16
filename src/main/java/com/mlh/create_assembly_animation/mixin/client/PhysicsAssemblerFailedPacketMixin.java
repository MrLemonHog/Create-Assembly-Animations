package com.mlh.create_assembly_animation.mixin.client;

import com.mlh.create_assembly_animation.client.ClientAssemblyTracker;
import dev.simulated_team.simulated.network.packets.physics_assembler.PhysicsAssemblerFailedPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PhysicsAssemblerFailedPacket.class)
public abstract class PhysicsAssemblerFailedPacketMixin {

    @Inject(method = "handle", at = @At("HEAD"))
    private void caa$observeFailure(final CallbackInfo ci) {
        ClientAssemblyTracker.failed(((PhysicsAssemblerFailedPacket) (Object) this).pos());
    }
}
