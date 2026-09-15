package dev.s7a.strata.resource

import kotlin.uuid.Uuid as KotlinUuid

/**
 * Uses Kotlin's standard UUID representation on JavaScript.
 */
public actual typealias Uuid = KotlinUuid

/**
 * Parses an identifier using Kotlin's standard UUID parser.
 */
public actual fun parseUuid(value: String): Uuid = KotlinUuid.parse(value)
