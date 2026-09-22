package dev.s7a.strata.runtime.minecraft.fabric.mixin.remote;

import dev.s7a.strata.runtime.minecraft.fabric.FabricRemotePayload;
import dev.s7a.strata.runtime.minecraft.fabric.FabricRemoteScreens;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Transfers authenticated custom payload bytes to the bounded client-thread inbox.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
abstract class FabricRemoteReceiveMixin {
    @Shadow @Final protected Connection connection;

    @Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V", at = @At("HEAD"), cancellable = true)
    private void strataReceiveRemotePayload(ClientboundCustomPayloadPacket packet, CallbackInfo callback) {
        if (packet.payload() instanceof FabricRemotePayload payload) {
            FabricRemoteScreens.INSTANCE.enqueue(connection, payload.bytes());
            callback.cancel();
        }
    }
}
