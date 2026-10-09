package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.remote.RemoteConnection
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
}
