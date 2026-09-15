package dev.s7a.strata.resource

/**
 * Immutable universally unique identifier backed by Java UUID on JVM and Kotlin Uuid on JavaScript.
 * Use [parseUuid] to create identifiers from canonical UUID text in shared code.
 */
public expect class Uuid

/**
 * Parses an identifier with the platform's standard UUID parser.
 * Canonical dashed hexadecimal UUIDs are accepted on every target; invalid text throws IllegalArgumentException.
 */
public expect fun parseUuid(value: String): Uuid
