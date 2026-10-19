package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.remote.RemoteConnection
import io.netty.buffer.Unpooled
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.network.ServerGamePacketListenerImpl

/**
 * Registers the authenticated Fabric buffer transport and detaches received fragments before native buffer release.
 */
public object FabricRemoteServerTransport {
    /**
     * Native identifier for Strata's fixed external plugin-message channel.
     */
    @JvmField
    public val ID: ResourceLocation = ResourceLocation(RemoteConnection.CHANNEL)

    /**
     * Validates the unread frame length before allocating and returns independently owned bytes.
     */
    @JvmStatic
    public fun read(buffer: FriendlyByteBuf): ByteArray {
        val count = buffer.readableBytes()
        require(count in 1..24576) { "Invalid Strata frame length." }
        return ByteArray(count).also { buffer.readBytes(it) }
    }

    /**
     * Registers the channel once before play connections are constructed; callbacks only enqueue detached input.
     */
    @JvmStatic
    public fun initialize() {
        ServerPlayNetworking.registerGlobalReceiver(ID) { _, player, _, buffer, _ ->
            FabricServerUiServices.enqueue(player, read(buffer))
        }
    }

    /**
     * Sends a copied fragment to the authenticated native player.
     */
    @JvmStatic
    public fun send(
        connection: ServerGamePacketListenerImpl,
        bytes: ByteArray,
    ) {
        ServerPlayNetworking.send(connection.player, ID, FriendlyByteBuf(Unpooled.wrappedBuffer(bytes.copyOf())))
    }
}
