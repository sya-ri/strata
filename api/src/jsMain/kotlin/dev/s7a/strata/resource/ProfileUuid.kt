package dev.s7a.strata.resource

import kotlin.uuid.Uuid

/**
 * Uses Kotlin's standard UUID representation on JavaScript.
 */
public actual typealias ProfileUuid = Uuid

/**
 * Parses a profile identifier using Kotlin's standard UUID parser.
 */
public actual fun parseProfileUuid(value: String): ProfileUuid = Uuid.parse(value)
