package dev.s7a.strata.runtime.minecraft.fabric;

import dev.s7a.strata.runtime.remote.RemoteConnection;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Fabric-managed registration and authenticated server transport for opaque Strata frames. */
public final class FabricRemoteServerTransport {
    private FabricRemoteServerTransport() { }

    public static final ResourceLocation ID = new ResourceLocation(RemoteConnection.CHANNEL);

    /** Detaches bounded bytes before Fabric releases its packet buffer. */
    public static byte[] read(FriendlyByteBuf buffer) {
        int count = buffer.readableBytes();
        if (count < 1 || 24576 < count) throw new IllegalArgumentException("Invalid Strata frame length.");
        byte[] bytes = new byte[count];
        buffer.readBytes(bytes);
        return bytes;
    }

    /** Registers the wire channel once before any play connection is constructed. */
    public static void initialize() {
        ServerPlayNetworking.registerGlobalReceiver(ID, (server, player, handler, buffer, response) -> FabricServerUiServices.INSTANCE.enqueue(player, read(buffer)));
    }

    /** Sends one detached protocol fragment to the authenticated native player. */
    public static void send(ServerGamePacketListenerImpl connection, byte[] bytes) {
        ServerPlayNetworking.send(connection.player, ID, new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes.clone())));
    }
}
