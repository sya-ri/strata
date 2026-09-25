package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.remote.RemoteConnection
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Bridges the Java payload codec to the existing version-specific resource-identifier adapter.
 */
internal object FabricRemotePayloadTypes {
    /**
     * Creates the namespaced Strata type without using Minecraft's default-namespace-only convenience factory.
     */
    @JvmStatic
    fun create(): CustomPacketPayload.Type<FabricRemotePayload> = CustomPacketPayload.Type(parseMinecraftResourceLocation(RemoteConnection.CHANNEL))

    /**
     * Creates the standard native channel-registration payload type.
     */
    @JvmStatic
    fun registration(): CustomPacketPayload.Type<FabricRemoteRegistration> = CustomPacketPayload.Type(parseMinecraftResourceLocation("minecraft:register"))

    /**
     * Uses the native identifier codec across the ResourceLocation/Identifier version boundary.
     */
    @JvmStatic
    fun writeRegistrationIdentifier(buffer: FriendlyByteBuf) {
        MinecraftResourceLocation.STREAM_CODEC.encode(buffer, FabricRemoteRegistration.TYPE.id())
    }
}
