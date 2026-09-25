package dev.s7a.strata.integration.minecraft.fabric.mixin;

import dev.s7a.strata.integration.minecraft.fabric.RemoteNativeServerFixture;
import dev.s7a.strata.runtime.minecraft.fabric.FabricRemotePayload;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Copies native client frames into the owner-thread integration fixture without executing handlers on the network thread. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class RemoteNativeServerReceiveMixin {
    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void strataReceiveFixture(ServerboundCustomPayloadPacket packet, CallbackInfo callback) {
        if ((Object) this instanceof ServerGamePacketListenerImpl listener && packet.payload() instanceof FabricRemotePayload payload) {
            RemoteNativeServerFixture.INSTANCE.enqueue(listener.player, payload.bytes());
            callback.cancel();
        }
    }
}
