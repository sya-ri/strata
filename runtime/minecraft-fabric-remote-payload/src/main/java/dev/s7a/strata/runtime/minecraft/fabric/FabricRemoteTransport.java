package dev.s7a.strata.runtime.minecraft.fabric;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * Sends one bounded Strata frame through Minecraft's native play connection.
 */
public final class FabricRemoteTransport {
    private FabricRemoteTransport() { }

    /**
     * Advertises the client channel before beginning the Strata capability exchange.
     */
    public static void register(ClientPacketListener connection) {
        connection.send(new ServerboundCustomPayloadPacket(new FabricRemoteRegistration()));
    }

    /**
     * Enqueues a native custom payload on the authenticated client connection.
     */
    public static void send(ClientPacketListener connection, byte[] bytes) {
        connection.send(new ServerboundCustomPayloadPacket(new FabricRemotePayload(bytes)));
    }
}
