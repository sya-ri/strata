@file:JvmName("NativeCanvasSources")

package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Creates a native canvas source for a stable version-owned physical-device driver.
 *
 * The source is reusable across canvases, but each attachment opens and owns an independent renderer binding.
 * This owner-thread call allocates no GPU target and does not invoke the producer factory.
 * The factory and external producer source must not retain a screen through an old draw command.
 *
 * @param driver the single stable driver identity shared by all canvases on this physical device.
 * @param producerFactory owner-thread factory for one attachment-owned producer.
 * @param depth whether to allocate an optional depth attachment together with each color target.
 * @return an immutable source description usable by the common Canvas component.
 * @throws IllegalStateException when called off the render thread or after device shutdown.
 */
@InternalStrataRuntimeApi
public fun nativeCanvasSource(
    driver: NativeCanvasDriver,
    producerFactory: () -> NativeCanvasProducer,
    depth: Boolean = false,
): CanvasSource = NativeCanvasDevices.device(driver).source(producerFactory, depth)
