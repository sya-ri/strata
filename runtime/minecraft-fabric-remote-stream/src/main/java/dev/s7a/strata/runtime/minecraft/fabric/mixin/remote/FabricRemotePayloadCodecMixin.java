package dev.s7a.strata.runtime.minecraft.fabric.mixin.remote;

import dev.s7a.strata.runtime.minecraft.fabric.FabricRemotePayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds the installed Strata codec to both native packet directions without requiring Fabric API.
 */
@Mixin({ClientboundCustomPayloadPacket.class, ServerboundCustomPayloadPacket.class})
abstract class FabricRemotePayloadCodecMixin {
    @ModifyArg(method = "<clinit>", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;codec(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;"), index = 1)
    private static <B extends FriendlyByteBuf> List<CustomPacketPayload.TypeAndCodec<? super B, ?>> strataPayloadCodec(List<CustomPacketPayload.TypeAndCodec<? super B, ?>> known) {
        List<CustomPacketPayload.TypeAndCodec<? super B, ?>> result = new ArrayList<>(known);
        result.add(new CustomPacketPayload.TypeAndCodec<>(FabricRemotePayload.TYPE, FabricRemotePayload.CODEC));
        return result;
    }
}
