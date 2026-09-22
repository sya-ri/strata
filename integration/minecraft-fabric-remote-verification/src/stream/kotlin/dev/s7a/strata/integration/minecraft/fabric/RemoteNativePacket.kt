package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.fabric.FabricRemotePayload
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.server.level.ServerPlayer

/**
 * Sends one owner-thread fixture frame using the exact native server packet boundary.
 */
internal fun sendRemoteTestPacket(
    player: ServerPlayer,
    bytes: ByteArray,
) {
    player.connection.send(ClientboundCustomPayloadPacket(FabricRemotePayload(bytes)))
}
