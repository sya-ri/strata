package dev.s7a.strata.runtime.minecraft.fabric;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jetbrains.annotations.NotNull;

/**
 * Bounded opaque plugin-message bytes shared by the modern native stream codecs.
 */
public record FabricRemotePayload(byte[] bytes) implements CustomPacketPayload {
    public static final Type<FabricRemotePayload> TYPE = FabricRemotePayloadTypes.create();
    public static final StreamCodec<FriendlyByteBuf, FabricRemotePayload> CODEC = CustomPacketPayload.codec(FabricRemotePayload::write, FabricRemotePayload::read);

    public FabricRemotePayload {
        if (bytes.length < 17 || 24576 < bytes.length) throw new IllegalArgumentException("Invalid Strata frame length.");
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() { return bytes.clone(); }

    @Override
    public @NotNull Type<FabricRemotePayload> type() { return TYPE; }

    private void write(FriendlyByteBuf buffer) { buffer.writeBytes(bytes); }

    private static FabricRemotePayload read(FriendlyByteBuf buffer) {
        int count = buffer.readableBytes();
        if (count < 17 || 24576 < count) throw new IllegalArgumentException("Invalid Strata frame length.");
        byte[] bytes = new byte[count];
        buffer.readBytes(bytes);
        return new FabricRemotePayload(bytes);
    }
}
