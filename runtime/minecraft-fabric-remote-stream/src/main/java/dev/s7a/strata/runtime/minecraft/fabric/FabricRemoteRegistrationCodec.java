package dev.s7a.strata.runtime.minecraft.fabric;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jetbrains.annotations.NotNull;

/**
 * Outbound registration wrapper whose implementation classes are independent of Mixin transformation.
 */
public final class FabricRemoteRegistrationCodec {
    private FabricRemoteRegistrationCodec() { }

    /**
     * Preserves every existing codec path except Strata's explicitly owned registration object.
     */
    public static StreamCodec<FriendlyByteBuf, CustomPacketPayload> wrap(StreamCodec<FriendlyByteBuf, CustomPacketPayload> original) {
        return new StreamCodec<>() {
            @Override
            public @NotNull CustomPacketPayload decode(@NotNull FriendlyByteBuf buffer) { return original.decode(buffer); }

            @Override
            public void encode(@NotNull FriendlyByteBuf buffer, @NotNull CustomPacketPayload payload) {
                if (payload instanceof FabricRemoteRegistration registration) registration.write(buffer);
                else original.encode(buffer, payload);
            }
        };
    }
}
