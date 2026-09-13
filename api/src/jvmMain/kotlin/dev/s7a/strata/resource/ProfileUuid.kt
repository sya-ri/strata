package dev.s7a.strata.resource

import java.util.UUID

/**
 * Keeps existing JVM UUID constructor and property descriptors binary compatible.
 */
public actual typealias ProfileUuid = UUID

/**
 * Parses a profile identifier using the existing JVM UUID contract.
 */
public actual fun parseProfileUuid(value: String): ProfileUuid = UUID.fromString(value)
