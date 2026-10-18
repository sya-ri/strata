package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.remote.RemoteConnection
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type

/**
 * Owns one bounded opaque Strata frame, independently of caller arrays and borrowed native codec buffers.
 * Identity remains per payload; every returned byte array is detached from its storage.
 */
public class FabricRemotePayload : CustomPacketPayload {
    private val ownedBytes: ByteArray

    /**
     * Validates and copies one nonempty frame of at most 24576 bytes.
     */
    public constructor(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= 24576) { "Invalid Strata frame length." }
        ownedBytes = bytes.copyOf()
    }

    private constructor(buffer: FriendlyByteBuf) {
        val count = buffer.readableBytes()
        require(0 < count && count <= 24576) { "Invalid Strata frame length." }
        ownedBytes = ByteArray(count)
        buffer.readBytes(ownedBytes)
    }

    /**
     * Returns an independently owned frame for the receiving adapter's queue.
     */
    public fun bytes(): ByteArray = ownedBytes.copyOf()

    override fun type(): Type<FabricRemotePayload> = TYPE

    private fun write(buffer: FriendlyByteBuf) {
        buffer.writeBytes(ownedBytes)
    }

    /**
     * Supplies the fixed native channel type and bounded raw-byte codec without retaining native buffers.
     */
    public companion object {
        /**
         * Namespaced native type for the external Strata plugin-message channel.
         */
        @JvmField
        public val TYPE: Type<FabricRemotePayload> = Type(parseMinecraftResourceLocation(RemoteConnection.CHANNEL))

        /**
         * Copies bounded incoming bytes once and encodes owned frames without transferring their storage.
         */
        @JvmField
        public val CODEC: StreamCodec<FriendlyByteBuf, FabricRemotePayload> = CustomPacketPayload.codec(FabricRemotePayload::write, ::FabricRemotePayload)
    }
}
