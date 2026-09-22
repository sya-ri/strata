package dev.s7a.strata.runtime.minecraft.fabric;

import dev.s7a.strata.runtime.remote.RemoteConnection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

/**
 * Opaque Strata frame for the native payload protocol preceding stream codecs.
 */
public final class FabricRemotePayload implements CustomPacketPayload {
    private final byte[] bytes;
    public static final ResourceLocation ID = new ResourceLocation(RemoteConnection.CHANNEL);

    /**
     * Copies a bounded message independently of its caller-owned storage.
     */
    public FabricRemotePayload(byte[] bytes) {
        if (bytes.length < 1 || 24576 < bytes.length) throw new IllegalArgumentException("Invalid Strata frame length.");
        this.bytes = bytes.clone();
    }

    /**
     * Returns a detached frame for the receiving adapter's queue.
     */
    public byte[] bytes() { return bytes.clone(); }

    @Override
    public @NotNull ResourceLocation id() { return ID; }

    @Override
    public void write(FriendlyByteBuf buffer) { buffer.writeBytes(bytes); }

    /**
     * Copies a bounded native payload before its packet buffer is released.
     */
    public static FabricRemotePayload read(FriendlyByteBuf buffer) {
        int count = buffer.readableBytes();
        if (count < 1 || 24576 < count) throw new IllegalArgumentException("Invalid Strata frame length.");
        byte[] bytes = new byte[count];
        buffer.readBytes(bytes);
        return new FabricRemotePayload(bytes);
    }
}
