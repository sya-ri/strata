package dev.s7a.strata.resource

/**
 * Immutable profile identifier backed by Java UUID on JVM and Kotlin Uuid on JavaScript.
 * Use [parseProfileUuid] to create identifiers from canonical UUID text in shared code.
 */
public expect class ProfileUuid

/**
 * Parses a profile identifier with the platform's standard UUID parser.
 * Canonical dashed hexadecimal UUIDs are accepted on every target; invalid text throws IllegalArgumentException.
 */
public expect fun parseProfileUuid(value: String): ProfileUuid
