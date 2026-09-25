package dev.s7a.strata.integration.minecraft.fabric.mixin;

import dev.s7a.strata.integration.minecraft.fabric.RemoteNativeServerFixture;
import dev.s7a.strata.runtime.minecraft.fabric.FabricRemoteTransport;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
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
        if (FabricRemoteTransport.ID.equals(packet.getIdentifier())) {
            FriendlyByteBuf data = packet.getData();
            int count = data.readableBytes();
            if (count < 1 || 24576 < count) throw new IllegalArgumentException("Invalid fixture frame length.");
            byte[] bytes = new byte[count];
            data.getBytes(data.readerIndex(), bytes);
            RemoteNativeServerFixture.INSTANCE.enqueue(((ServerGamePacketListenerImpl) (Object) this).player, bytes);
            // ServerboundCustomPayloadPacket.handle owns this buffer and releases it after the listener returns.
            callback.cancel();
        }
    }
}
