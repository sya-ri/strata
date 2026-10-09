package dev.s7a.strata.runtime.minecraft.fabric;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.multiplayer.ClientPacketListener;

/** Fabric-managed client registration and opaque Strata transport. */
public final class FabricRemoteTransport {
    private FabricRemoteTransport() { }

    /** Installs the channel receiver before Fabric advertises it on play connections. */
    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(FabricRemotePayload.TYPE, (payload, context) -> {
            ClientPacketListener connection = context.client().getConnection();
            if (connection != null) FabricRemoteScreens.INSTANCE.enqueue(connection.getConnection(), payload.bytes());
        });
    }

    /** Sends a fragment on the current play connection after identity validation by its owner. */
    public static void send(ClientPacketListener connection, byte[] bytes) {
        ClientPlayNetworking.send(new FabricRemotePayload(bytes));
    }
}
