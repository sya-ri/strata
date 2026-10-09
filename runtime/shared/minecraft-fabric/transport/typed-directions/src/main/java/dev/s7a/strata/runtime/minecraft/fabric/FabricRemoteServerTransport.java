package dev.s7a.strata.runtime.minecraft.fabric;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Fabric-managed registration and authenticated server transport for opaque Strata frames. */
public final class FabricRemoteServerTransport {
    private FabricRemoteServerTransport() { }

    /** Registers the wire channel once before any play connection is constructed. */
    public static void initialize() {
        PayloadTypeRegistry.playC2S().register(FabricRemotePayload.TYPE, FabricRemotePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FabricRemotePayload.TYPE, FabricRemotePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(FabricRemotePayload.TYPE, (payload, context) -> FabricServerUiServices.INSTANCE.enqueue(context.player(), payload.bytes()));
    }

    /** Sends one detached protocol fragment to the authenticated native player. */
    public static void send(ServerGamePacketListenerImpl connection, byte[] bytes) {
        ServerPlayNetworking.send(connection.player, new FabricRemotePayload(bytes));
    }
}
