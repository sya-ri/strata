package dev.s7a.strata.runtime.minecraft.fabric.mixin.remote;

import dev.s7a.strata.runtime.minecraft.fabric.FabricRemoteScreens;
import dev.s7a.strata.runtime.minecraft.fabric.FabricRemoteTransport;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copies the legacy packet bytes before the network-owned buffer can be released.
 */
@Mixin(ClientPacketListener.class)
abstract class FabricRemoteReceiveMixin {
    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void strataReceiveRemotePayload(ClientboundCustomPayloadPacket packet, CallbackInfo callback) {
        if (FabricRemoteTransport.ID.equals(packet.getIdentifier())) {
            FriendlyByteBuf data = packet.getData();
            try {
                int count = data.readableBytes();
                if (count < 1 || 24576 < count) throw new IllegalArgumentException("Invalid Strata frame length.");
                byte[] bytes = new byte[count];
                data.getBytes(data.readerIndex(), bytes);
                FabricRemoteScreens.INSTANCE.enqueue(((ClientPacketListener) (Object) this).getConnection(), bytes);
            } finally {
                data.release();
            }
            callback.cancel();
        }
    }
}
