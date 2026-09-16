package com.mlh.create_assembly_animation.mixin.client;

import com.mlh.create_assembly_animation.client.ClientAssemblyTracker;
import dev.simulated_team.simulated.network.packets.physics_assembler.PhysicsAssemblerFlickAndHoldLeverPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PhysicsAssemblerFlickAndHoldLeverPacket.class)
public abstract class PhysicsAssemblerLeverPacketMixin {

    @Inject(method = "handle", at = @At("TAIL"))
    private void caa$observeLever(final CallbackInfo ci) {
        final PhysicsAssemblerFlickAndHoldLeverPacket packet = (PhysicsAssemblerFlickAndHoldLeverPacket) (Object) this;
        ClientAssemblyTracker.leverFlicked(packet.pos(), packet.flicked());
    }
}
