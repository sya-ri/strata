package dev.s7a.strata.runtime.minecraft.fabric

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.multiplayer.ClientPacketListener

/**
 * Registers Fabric's typed client receiver and sends copied Strata fragments on the current play connection.
 */
public object FabricRemoteTransport {
    /**
     * Installs the channel receiver before Fabric advertises it, borrowing the current client without taking ownership.
     */
    @JvmStatic
    public fun initialize() {
        ClientPlayNetworking.registerGlobalReceiver(FabricRemotePayload.TYPE) { payload, context ->
            val connection = context.client().connection ?: return@registerGlobalReceiver
            FabricRemoteScreens.enqueue(connection.connection, payload.bytes())
        }
    }

    /**
     * Sends copied bytes after the owning endpoint has validated the current connection identity.
     */
    @JvmStatic
    @Suppress("UnusedParameter") // The public Java signature carries the identity already validated before Fabric's global send.
    public fun send(connection: ClientPacketListener, bytes: ByteArray) {
        ClientPlayNetworking.send(FabricRemotePayload(bytes))
    }
}
