package dev.s7a.strata.integration.minecraft.fabric.mixin;

import dev.s7a.strata.runtime.minecraft.fabric.FabricRemotePayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Decodes Strata payloads before the native unknown-payload path discards their bytes.
 */
@Mixin(ServerboundCustomPayloadPacket.class)
abstract class RemoteNativeServerCodecMixin {
    @Inject(method = "readPayload", at = @At("HEAD"), cancellable = true)
    private static void strataReadPayload(ResourceLocation id, FriendlyByteBuf buffer, CallbackInfoReturnable<CustomPacketPayload> callback) {
        if (FabricRemotePayload.ID.equals(id)) callback.setReturnValue(FabricRemotePayload.read(buffer));
    }
}
