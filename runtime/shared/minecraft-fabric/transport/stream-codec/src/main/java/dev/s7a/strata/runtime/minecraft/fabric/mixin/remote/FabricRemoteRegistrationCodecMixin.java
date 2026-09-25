package dev.s7a.strata.runtime.minecraft.fabric.mixin.remote;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.s7a.strata.runtime.minecraft.fabric.FabricRemoteRegistrationCodec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Encodes only Strata's registration object while delegating all other payloads to the original codec.
 * Avoids claiming minecraft:register in another mod's registry or changing inbound registration decoding.
 */
@Mixin(ServerboundCustomPayloadPacket.class)
abstract class FabricRemoteRegistrationCodecMixin {
    @ModifyExpressionValue(method = "<clinit>", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;codec(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;"))
    private static StreamCodec<FriendlyByteBuf, CustomPacketPayload> strataRegistrationCodec(StreamCodec<FriendlyByteBuf, CustomPacketPayload> original) {
        return FabricRemoteRegistrationCodec.wrap(original);
    }
}
