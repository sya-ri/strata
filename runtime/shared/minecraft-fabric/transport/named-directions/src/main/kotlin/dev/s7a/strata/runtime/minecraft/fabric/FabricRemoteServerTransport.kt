package dev.s7a.strata.runtime.minecraft.fabric

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.network.ServerGamePacketListenerImpl

/**
 * Registers the selected native payload directions and transports authenticated opaque Strata frames.
 */
public object FabricRemoteServerTransport {
    /**
     * Registers both wire directions before connections exist; received payloads enqueue detached bytes for their player.
     */
    @JvmStatic
    public fun initialize() {
        PayloadTypeRegistry.serverboundPlay().register(FabricRemotePayload.TYPE, FabricRemotePayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(FabricRemotePayload.TYPE, FabricRemotePayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(FabricRemotePayload.TYPE) { payload, context ->
            FabricServerUiServices.enqueue(context.player(), payload.bytes())
        }
    }

    /**
     * Sends a copied protocol fragment to the authenticated native player.
     */
    @JvmStatic
    public fun send(
        connection: ServerGamePacketListenerImpl,
        bytes: ByteArray,
    ) {
        ServerPlayNetworking.send(connection.player, FabricRemotePayload(bytes))
    }
}
