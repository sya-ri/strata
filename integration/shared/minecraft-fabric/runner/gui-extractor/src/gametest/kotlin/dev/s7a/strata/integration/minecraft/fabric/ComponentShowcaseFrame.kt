package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.headless.HeadlessImage

/**
 * Verified headless frame and its encoded PNG retained for the final component receipt.
 */
internal class ComponentShowcaseFrame(
    val image: HeadlessImage,
    val png: ByteArray,
)
