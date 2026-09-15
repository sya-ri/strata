package dev.s7a.strata.resource

import java.util.UUID

/**
 * Keeps existing JVM UUID constructor and property descriptors binary compatible.
 */
public actual typealias Uuid = UUID

/**
 * Parses an identifier using the existing JVM UUID contract.
 */
public actual fun parseUuid(value: String): Uuid = UUID.fromString(value)
