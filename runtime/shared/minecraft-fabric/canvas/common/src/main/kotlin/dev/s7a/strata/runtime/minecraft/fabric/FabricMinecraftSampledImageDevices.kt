package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDriver
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.IdentityHashMap

/**
 * Resolves one sampled-image cache manager per stable physical device driver.
 *
 * The common native-device registry owns terminal callbacks after first resolution; this registry retains only active device identities and is never a screen owner.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object FabricMinecraftSampledImageDevices {
    private val devices = IdentityHashMap<NativeCanvasDriver, FabricMinecraftSampledImageDevice>()

    /**
     * Resolves and registers the current version driver's sampled-image manager without allocating a texture.
     *
     * @param driver stable driver identity for the active physical device.
     * @return device-owned cache manager confined to the render thread.
     */
    @JvmSynthetic
    internal fun device(driver: NativeCanvasDriver): FabricMinecraftSampledImageDevice =
        devices.getOrPut(driver) {
            FabricMinecraftSampledImageDevice(driver).also { manager ->
                NativeCanvasDevices.device(driver).registerGuiResourceManager(manager)
            }
        }

    /**
     * Removes one successfully drained manager without affecting a newer manager registered for the same driver identity.
     *
     * @param driver physical device whose terminal lifecycle completed.
     * @param manager exact terminal manager whose resources were physically acknowledged.
     */
    @JvmSynthetic
    internal fun acknowledge(
        driver: NativeCanvasDriver,
        manager: FabricMinecraftSampledImageDevice,
    ) {
        if (devices[driver] === manager) devices.remove(driver)
    }
}
