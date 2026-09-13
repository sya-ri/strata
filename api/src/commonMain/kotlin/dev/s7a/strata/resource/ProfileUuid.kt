package dev.s7a.strata.resource

/**
 * Immutable profile identifier preserving the JVM UUID representation.
 * JavaScript accepts canonical hexadecimal UUID text through [parseProfileUuid].
 */
public expect class ProfileUuid(
    mostSignificantBits: Long,
    leastSignificantBits: Long,
)

/**
 * Parses immutable profile identity text or throws IllegalArgumentException for invalid input.
 */
public expect fun parseProfileUuid(value: String): ProfileUuid
