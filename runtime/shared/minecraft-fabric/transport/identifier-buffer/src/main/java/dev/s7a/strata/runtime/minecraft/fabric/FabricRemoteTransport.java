package dev.s7a.strata.runtime.minecraft.fabric;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.multiplayer.ClientPacketListener;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

/** Fabric-managed client registration and opaque Strata transport. */
public final class FabricRemoteTransport {
    private FabricRemoteTransport() { }

    /** Installs the channel receiver before Fabric advertises it on play connections. */
    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(FabricRemoteServerTransport.ID, (client, handler, buffer, response) -> FabricRemoteScreens.INSTANCE.enqueue(handler.getConnection(), FabricRemoteServerTransport.read(buffer)));
    }

    /** Sends a fragment on the current play connection after identity validation by its owner. */
    public static void send(ClientPacketListener connection, byte[] bytes) {
        ClientPlayNetworking.send(FabricRemoteServerTransport.ID, new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes.clone())));
    }
}
