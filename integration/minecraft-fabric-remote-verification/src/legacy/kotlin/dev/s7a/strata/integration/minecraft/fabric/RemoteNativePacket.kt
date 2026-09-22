package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.fabric.FabricRemoteTransport
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket
import net.minecraft.server.level.ServerPlayer

/**
 * Sends one owner-thread fixture frame using the exact native server packet boundary.
 */
internal fun sendRemoteTestPacket(
    player: ServerPlayer,
    bytes: ByteArray,
) {
    player.connection.send(ClientboundCustomPayloadPacket(FabricRemoteTransport.ID, FriendlyByteBuf(Unpooled.wrappedBuffer(bytes.copyOf()))))
}
