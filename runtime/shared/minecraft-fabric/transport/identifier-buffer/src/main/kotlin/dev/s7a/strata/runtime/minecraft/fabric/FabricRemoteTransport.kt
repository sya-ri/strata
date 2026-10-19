package dev.s7a.strata.runtime.minecraft.fabric

import io.netty.buffer.Unpooled
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.network.FriendlyByteBuf

/**
 * Registers Fabric's buffer-based client receiver and sends detached Strata fragments on the current play connection.
 */
public object FabricRemoteTransport {
    /**
     * Installs the receiver before channel advertisement and detaches bounded bytes before Fabric releases its buffer.
     */
    @JvmStatic
    public fun initialize() {
        ClientPlayNetworking.registerGlobalReceiver(FabricRemoteServerTransport.ID) { _, handler, buffer, _ ->
            FabricRemoteScreens.enqueue(handler.connection, FabricRemoteServerTransport.read(buffer))
        }
    }

    /**
     * Sends copied bytes after the owning client endpoint has validated the current connection identity.
     */
    @JvmStatic
    @Suppress("UnusedParameter") // Preserve the Java entrypoint; Fabric sends through the current owner-validated connection.
    public fun send(connection: ClientPacketListener, bytes: ByteArray) {
        ClientPlayNetworking.send(FabricRemoteServerTransport.ID, FriendlyByteBuf(Unpooled.wrappedBuffer(bytes.copyOf())))
    }
}
