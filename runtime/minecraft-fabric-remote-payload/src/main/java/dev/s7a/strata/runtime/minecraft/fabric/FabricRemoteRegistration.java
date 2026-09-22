package dev.s7a.strata.runtime.minecraft.fabric;

import dev.s7a.strata.runtime.remote.RemoteConnection;
import java.nio.charset.StandardCharsets;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Native plugin-channel advertisement required before Paper can send the first Strata payload.
 */
public record FabricRemoteRegistration() implements CustomPacketPayload {
    private static final ResourceLocation ID = new ResourceLocation("minecraft:register");

    @Override
    public ResourceLocation id() { return ID; }

    @Override
    public void write(FriendlyByteBuf buffer) { buffer.writeBytes(RemoteConnection.CHANNEL.getBytes(StandardCharsets.UTF_8)); }
}
