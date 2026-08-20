package dev.s7a.strata.modifier

import dev.s7a.strata.semantics.Semantics

/**
 * Emits one separate unresolved semantics entry before entries produced by the modifier's content.
 * The new behavior is appended inside the existing modifier chain, so existing outer entries remain earlier.
 *
 * @param value the semantics payload to emit.
 * @return this chain with one appended semantics modifier.
 */
public fun Modifier.semantics(value: Semantics): Modifier = then(SemanticsModifier.Element(value))
