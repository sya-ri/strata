package dev.s7a.strata.runtime.minecraft.fabric;

import dev.s7a.strata.runtime.remote.RemoteConnection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Opaque Strata frame for the native payload-record protocol preceding stream codecs.
 */
public record FabricRemotePayload(byte[] bytes) implements CustomPacketPayload {
    public static final ResourceLocation ID = new ResourceLocation(RemoteConnection.CHANNEL);

    public FabricRemotePayload {
        if (bytes.length < 17 || 24576 < bytes.length) throw new IllegalArgumentException("Invalid Strata frame length.");
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() { return bytes.clone(); }

    @Override
    public ResourceLocation id() { return ID; }

    @Override
    public void write(FriendlyByteBuf buffer) { buffer.writeBytes(bytes); }

    /**
     * Copies a bounded native payload before its packet buffer is released.
     */
    public static FabricRemotePayload read(FriendlyByteBuf buffer) {
        int count = buffer.readableBytes();
        if (count < 17 || 24576 < count) throw new IllegalArgumentException("Invalid Strata frame length.");
        byte[] bytes = new byte[count];
        buffer.readBytes(bytes);
        return new FabricRemotePayload(bytes);
    }
}
