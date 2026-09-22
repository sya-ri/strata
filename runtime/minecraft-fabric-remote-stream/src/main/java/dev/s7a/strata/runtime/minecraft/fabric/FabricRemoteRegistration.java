package dev.s7a.strata.runtime.minecraft.fabric;

import dev.s7a.strata.runtime.remote.RemoteConnection;
import java.nio.charset.StandardCharsets;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Outbound-only native channel advertisement, separate from any installed Fabric networking registry.
 */
public record FabricRemoteRegistration() implements CustomPacketPayload {
    public static final Type<FabricRemoteRegistration> TYPE = FabricRemotePayloadTypes.registration();

    @Override
    public Type<FabricRemoteRegistration> type() { return TYPE; }

    /**
     * Encodes the complete native payload, including its standard identifier prefix.
     */
    public void write(FriendlyByteBuf buffer) {
        FabricRemotePayloadTypes.writeRegistrationIdentifier(buffer);
        buffer.writeBytes(RemoteConnection.CHANNEL.getBytes(StandardCharsets.UTF_8));
    }
}
