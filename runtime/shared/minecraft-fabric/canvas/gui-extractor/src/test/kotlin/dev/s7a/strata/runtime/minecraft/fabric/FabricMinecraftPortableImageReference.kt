package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.headless.rasterizeHeadlessRegion
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals

/**
 * Builds a test-owned allocating reference and checks every pixel from the production buffer materializer.
 * The returned image never enters a native runtime artifact.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal fun FabricMinecraftPortableImage.rasterize(): HeadlessImage {
    val reference =
        if (origin == IntOffset.Zero) {
            rasterizeHeadless(commands, size, scale)
        } else {
            rasterizeHeadlessRegion(commands, IntRect(origin.x, origin.y, Math.addExact(origin.x, size.width), Math.addExact(origin.y, size.height)), scale)
        }
    val pixels = IntArray(Math.multiplyExact(physicalSize.width, physicalSize.height))
    rasterizeInto(pixels)
    assertArrayEquals(reference.copyArgb(), pixels)
    return reference
}
