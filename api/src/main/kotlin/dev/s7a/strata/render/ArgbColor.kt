package dev.s7a.strata.render

/**
 * A non-premultiplied ARGB color represented in one platform-neutral four-byte integer.
 *
 * Bits 31 through 24 hold alpha, bits 23 through 16 hold red, bits 15 through 8 hold green, and bits 7 through 0 hold blue.
 * The value is not premultiplied.
 * Adapters decide how to pass the channels to their drawing backend.
 *
 * @property value the ARGB bits, including the alpha channel.
 */
@JvmInline
public value class ArgbColor(
    public val value: Int,
)
