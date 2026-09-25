package dev.s7a.strata.runtime.minecraft.fabric;

import dev.s7a.strata.runtime.remote.RemoteConnection;
import io.netty.buffer.Unpooled;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import java.nio.charset.StandardCharsets;

/**
 * Native custom payload transport for the original identifier-and-buffer packet format.
 */
public final class FabricRemoteTransport {
    public static final ResourceLocation ID = new ResourceLocation(RemoteConnection.CHANNEL);

    private FabricRemoteTransport() { }

    /**
     * Advertises the Strata channel before Paper is allowed to reply with plugin messages.
     */
    public static void register(ClientPacketListener connection) {
        byte[] bytes = RemoteConnection.CHANNEL.getBytes(StandardCharsets.UTF_8);
        connection.send(new ServerboundCustomPayloadPacket(new ResourceLocation("minecraft:register"), new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))));
    }

    /**
     * Transfers an owned byte snapshot to the native packet encoder.
     */
    public static void send(ClientPacketListener connection, byte[] bytes) {
        connection.send(new ServerboundCustomPayloadPacket(ID, new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes.clone()))));
    }
}
