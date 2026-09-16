package com.mlh.create_assembly_animation.mixin.client;

import com.mlh.create_assembly_animation.client.Forces;
import dev.simulated_team.simulated.network.packets.contraption_diagram.DiagramDataPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DiagramDataPacket.class)
public abstract class DiagramDataPacketMixin {

    @Inject(method = "handle(Ldev/simulated_team/simulated/network/packets/contraption_diagram/DiagramDataPacket;)V", at = @At("HEAD"))
    private static void caa$captureForces(final DiagramDataPacket packet, final CallbackInfo ci) {
        Forces.received(packet);
    }
}
